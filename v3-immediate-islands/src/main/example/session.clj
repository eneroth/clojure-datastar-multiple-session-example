(ns example.session
  "Tab sessions: one island tree per browser tab, outliving any single SSE
  connection.

  A session is created when the page is served, and the page is rendered from
  its first frame. The tab's `@get('/stream')` then attaches to it, and the
  session belongs to that page load from then on. Datastar drops that stream
  routinely: a hidden tab closes it, network blips retry it.
  A detached session keeps its islands, and so their resources, for `grace-ms`.
  A reconnect within that window resyncs from the cached frame without
  reopening anything. After the window, every island is unmounted and
  everything they held is released.

  Each session is run by one pump: a virtual thread that owns all of the
  session's mutable state, its island runtime included, and consumes the
  session's inbox (`example.signal.Inbox`). The inbox holds lifecycle events
  (attach, detach, close) and the subscriptions marked since the pump last
  looked. A change to something the islands read only marks its subscription,
  once until the pump takes it. Rendering happens on the pump, never on the
  thread that made the change, so a shared resource's loop never renders on
  anyone's behalf. Changes that arrive while a frame is being sent coalesce into
  the next one, so a slow client gets fewer, later frames, never a backlog, and
  the inbox never holds more than one mark per subscription."
  (:require
    [clojure.string :as str]
    [co.multiply.quiescent :as q]
    [example.island :as island]
    [example.runtime :as runtime]
    [example.sse :as sse])
  (:import
    (example.signal Inbox)
    (java.util ArrayList)))


(def grace-ms 15000)


;; Minimum gap between frames. Changes within it coalesce; the latest wins.
(def frame-ms 50)


(def heartbeat-ms 10000)


;; tab -> {:tab, :uid, :inbox, :closed, :attached?}
;; Changes only on lifecycle events (create, attach, detach, close).
(defonce !sessions (atom {}))


(defn- now
  []
  (System/currentTimeMillis))


(defn- post!
  [session event]
  (Inbox/.post (:inbox session) event))


(defn- set-attached!
  [{:keys [tab inbox]} attached?]
  (swap! !sessions (fn [ss]
                     (if (identical? inbox (get-in ss [tab :inbox]))
                       (assoc-in ss [tab :attached?] attached?)
                       ss))))


(defn- unregister!
  [{:keys [tab inbox]}]
  (swap! !sessions (fn [ss]
                     (if (identical? inbox (get-in ss [tab :inbox]))
                       (dissoc ss tab)
                       ss))))


;; Pump -----------------------------------------------------------------------
;;
;; The pump's state is a map threaded through its loop:
;;   :rt          the session's island runtime
;;   :root        the root island's Call
;;   :changed     an ArrayList the pump drains the inbox's marked subscriptions into
;;   :latest      the last rendered Frame
;;   :conn        the attached SSE generator, or nil
;;   :load        the page load the session belongs to: the first to attach
;;   :sent        the Frame the client is showing; nil means unknown, so resync
;;   :ssr         a promise awaiting the first frame, for server-side rendering
;;   :closed?     the session is closed and the pump exits


(defn- detach
  [st]
  (set-attached! (:session st) false)
  (assoc st :conn nil :sent nil :detached-at (now)))


(defn- close
  "Unmounts every island, releasing what they held, and ends the pump."
  [{:keys [rt conn session] :as st}]
  (println "session: closing" (:tab session))
  (unregister! session)
  (when conn (sse/close! conn))
  (runtime/dispose! rt)
  (assoc st :closed? true :conn nil))


(defn- turn-away!
  "Answers an event that reached a closed session: a connection is closed, so
  its client retries into a new session, and a page render gets nil."
  [event]
  (when (vector? event)
    (case (first event)
      :attach (sse/close! (second event))
      :ssr    (deliver (second event) nil)
      nil)))


(defn- handle
  [st event]
  (if (:closed? st)
    (do (turn-away! event)
        st)
    (case (if (vector? event) (first event) event)
      :close   (close st)
      :ssr     (assoc st :ssr (second event))
      :attach  (let [[_ conn load] event
                     prev          (:conn st)]
                 (if (and (contains? st :load) (not= load (:load st)))
                   ;; Another page with this tab id: a copy, such as a duplicated
                   ;; tab. Reloaded, it gets a tab of its own.
                   (do (sse/reload! conn)
                       st)
                   (do (when (and prev (not (identical? prev conn)))
                         (sse/close! prev))
                       (set-attached! (:session st) true)
                       ;; Replacing a live connection: we don't know what the client got.
                       (cond-> (assoc st :conn conn :load load :detached-at nil)
                         prev (assoc :sent nil)))))
      :detach  (if (identical? (second event) (:conn st))
                 (detach st)
                 st))))


(defn- render
  "Renders a new frame when something an island read has changed. Renders
  whether or not a client is attached, so what the islands hold, and the
  actions they expose, always follow the current state."
  [{:keys [rt root ^ArrayList changed closed? session] :as st}]
  (if (and (not closed?) (pos? (Inbox/.drain (:inbox session) changed)))
    (let [frame (runtime/frame! rt root changed)]
      (ArrayList/.clear changed)
      (assoc st :latest frame))
    st))


(defn- deliver-ssr
  [{:keys [ssr latest] :as st}]
  (if (and ssr latest)
    (do (deliver ssr latest)
        (assoc st :ssr nil :sent latest))
    st))


(defn- last-slot
  "The last slot of the island id `id`: short enough for the footer."
  [id]
  (subs id (inc (or (str/last-index-of id "/") -1))))


(defn- wire-stats
  [{:keys [rt latest frames chars]} patched patch-chars]
  {:_wire {:frames  frames
           :total   chars
           :last    patch-chars
           :page    (island/frame-chars latest)
           :patched (str/join ", " (map :id patched))
           :renders (->> (runtime/render-counts rt)
                      (map (fn [[id n]] (str (last-slot id) " " n)))
                      (str/join " · "))}})


(defn- send-frame
  [{:keys [conn sent latest] :as st}]
  (let [patched (island/patches sent latest)
        htmls   (mapv island/html patched)
        n       (transduce (map count) + htmls)
        st      (-> st (update :frames (fnil inc 0)) (update :chars (fnil + 0) n))]
    (cond
      (empty? patched)
      (assoc st :sent latest)

      (and (sse/patch! conn htmls)
           (sse/signals! conn (wire-stats st patched n)))
      (do (Thread/sleep (long frame-ms))
          (assoc st :sent latest :last-send (now)))

      :else
      (detach st))))


(defn- sync-client
  "Sends what the attached client lacks: the topmost changed islands, the whole
  root after a (re)connect, or a heartbeat so a dead connection is noticed."
  [{:keys [conn sent latest last-send] :as st}]
  (cond
    (nil? conn)
    st

    (not (identical? sent latest))
    (send-frame st)

    (< (+ (or last-send 0) (long heartbeat-ms)) (now))
    (if (sse/signals! conn {:_wire {:heartbeat (now)}})
      (assoc st :last-send (now))
      (detach st))

    :else
    st))


(defn- expire
  [{:keys [conn detached-at closed?] :as st}]
  (if (and (nil? conn) (not closed?) (< (+ detached-at (long grace-ms)) (now)))
    (close st)
    st))


(defn- wait-ms
  [{:keys [conn detached-at]}]
  (if conn
    heartbeat-ms
    (max 1 (- (+ detached-at (long grace-ms)) (now)))))


(defn- handle-events
  "Waits until the inbox has something or `ms` elapse, then handles the queued
  events. Marked subscriptions stay in the inbox for `render`."
  [st ^Inbox inbox ms]
  (Inbox/.await inbox ms)
  (loop [st st]
    (if-some [ev (Inbox/.poll inbox)]
      (recur (handle st ev))
      st)))


(defn- finish!
  [{:keys [session ssr]}]
  (some-> ssr (deliver nil))
  (deliver (:closed session) true)
  ;; Events that raced the close are turned away, here or by `attach!` and
  ;; `render-page!` themselves.
  (loop []
    (when-some [ev (Inbox/.poll (:inbox session))]
      (turn-away! ev)
      (recur)))
  (println "session: closed" (:tab session)))


(defn- pump!
  [{:keys [^Inbox inbox] :as session} root]
  (Inbox/.bind inbox)
  (let [rt (runtime/runtime (select-keys session [:tab :uid]) inbox)]
    (try
      (loop [st {:session     session
                 :rt          rt
                 :root        root
                 :latest      (runtime/frame! rt root [])
                 :changed     (ArrayList.)
                 :detached-at (now)}]
        (if (:closed? st)
          (finish! st)
          (recur (as-> st st
                   (handle-events st inbox (wait-ms st))
                   (render st)
                   (deliver-ssr st)
                   (sync-client st)
                   (expire st)))))
      (finally
        (runtime/dispose! rt)))))


;; API ------------------------------------------------------------------------


(defn- new-session
  [tab uid]
  {:tab tab :uid uid :inbox (Inbox.) :closed (promise) :attached? false})


(defn- start!
  "Starts the pump of a registered `session`, rendering the root island
  `(root-fn {:tab tab :uid uid})`."
  [{:keys [tab uid] :as session} root-fn]
  (let [root (root-fn {:tab tab :uid uid})]
    (println "session: created" tab)
    (q/compel
      (q/task
        (try
          (pump! session root)
          (catch Throwable t
            (unregister! session)
            (println "session: pump died" tab t)))))
    session))


(defn create!
  "Starts a session for the new `tab`, owned by `uid`, rendering the root island
  `(root-fn {:tab tab :uid uid})`."
  [tab uid root-fn]
  (let [session (new-session tab uid)]
    (swap! !sessions assoc tab session)
    (start! session root-fn)))


(defn obtain!
  "The session for `tab` if `uid` owns it. A fresh one if `tab` is unknown: it
  expired, or the server restarted, and the client resyncs from its first frame.
  Concurrent calls for one unknown `tab` get one session. Nil if another user
  owns it."
  [tab uid root-fn]
  (let [fresh   (new-session tab uid)
        [_ now] (swap-vals! !sessions #(if (contains? % tab) % (assoc % tab fresh)))
        session (get now tab)]
    (when (identical? session fresh)
      (start! session root-fn))
    (when (= uid (:uid session))
      session)))


(defn render-page!
  "The session's first frame, for rendering the page server-side. The session
  records that the client shows it, so attaching sends only what changed since.
  Nil if the session closed, or took longer than 5 s."
  [session]
  (let [p (promise)]
    (post! session [:ssr p])
    (when (realized? (:closed session))
      (deliver p nil))
    (deref p 5000 nil)))


(defn attach!
  "Attaches the connection `sse` from the page load `load`. The first load to
  attach owns the session. Another is a copy of the page, with the same tab id,
  and is told to reload."
  [session sse load]
  (post! session [:attach sse load])
  (when (realized? (:closed session))
    (sse/close! sse)))


(defn detach!
  [session sse]
  (post! session [:detach sse]))


(defn close!
  [session]
  (post! session :close))


(defn close-all!
  []
  (run! close! (vals @!sessions)))


(defn summary
  "Display view of `sessions`."
  [sessions]
  (let [attached (count (filter :attached? (vals sessions)))]
    {:attached attached
     :detached (- (count sessions) attached)}))


(comment
  @!sessions
  (close-all!))
