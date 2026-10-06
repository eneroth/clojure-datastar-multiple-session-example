(ns example.session
  "Tab sessions: one island tree per browser tab, outliving any single SSE
  connection.

  A session is created when the page is served, and the page is rendered from
  its first frame. The tab's `@get('/stream')` then attaches to it. Datastar
  drops that stream routinely: a hidden tab closes it, network blips retry it.
  A detached session keeps its islands, and so their resources, for `grace-ms`.
  A reconnect within that window resyncs from the cached frame without
  reopening anything. After the window, every island is unmounted and
  everything they held is released.

  Each session is run by one pump: a virtual thread that owns all of the
  session's mutable state, its island runtime included, and consumes a mailbox
  of events. A watched ref changing only posts to the mailbox. Rendering
  happens on the pump, never on the thread that changed the ref, so a shared
  resource's loop never renders on anyone's behalf. Changes that arrive while
  a frame is being sent coalesce into the next one, so a slow client gets
  fewer, later frames, never a backlog."
  (:require
    [clojure.string :as str]
    [co.multiply.quiescent :as q]
    [example.island :as island]
    [example.runtime :as runtime]
    [example.sse :as sse])
  (:import
    (java.util ArrayList)
    (java.util.concurrent LinkedBlockingQueue TimeUnit)))


(def grace-ms 15000)


;; Minimum gap between frames. Changes within it coalesce; the latest wins.
(def frame-ms 50)


(def heartbeat-ms 10000)


;; tab -> {:tab, :uid, :mailbox, :closed, :attached?}
;; Changes only on lifecycle events (create, attach, detach, close).
(defonce !sessions (atom {}))


(defn- now
  []
  (System/currentTimeMillis))


(defn- post!
  [session event]
  (LinkedBlockingQueue/.put (:mailbox session) event))


(defn- set-attached!
  [{:keys [tab mailbox]} attached?]
  (swap! !sessions (fn [ss]
                     (if (identical? mailbox (get-in ss [tab :mailbox]))
                       (assoc-in ss [tab :attached?] attached?)
                       ss))))


(defn- unregister!
  [{:keys [tab mailbox]}]
  (swap! !sessions (fn [ss]
                     (if (identical? mailbox (get-in ss [tab :mailbox]))
                       (dissoc ss tab)
                       ss))))


;; Pump -----------------------------------------------------------------------
;;
;; The pump's state is a map threaded through its loop:
;;   :rt          the session's island runtime
;;   :root        the root island's Call
;;   :changed     refs reported changed since the last frame
;;   :latest      the last rendered Frame
;;   :conn        the attached SSE generator, or nil
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


(defn- handle
  [st event]
  (if (:closed? st)
    st
    (case (if (vector? event) (first event) event)
      :changed (update st :changed conj (second event))
      :close   (close st)
      :ssr     (assoc st :ssr (second event))
      :attach  (let [conn (second event)
                     prev (:conn st)]
                 (when (and prev (not (identical? prev conn)))
                   (sse/close! prev))
                 (set-attached! (:session st) true)
                 ;; Replacing a live connection: we don't know what the client got.
                 (cond-> (assoc st :conn conn :detached-at nil)
                   prev (assoc :sent nil)))
      :detach  (if (identical? (second event) (:conn st))
                 (detach st)
                 st))))


(defn- render
  "Renders a new frame when something an island read has changed. Renders
  whether or not a client is attached, so what the islands hold, and the
  actions they expose, always follow the current state."
  [{:keys [rt root changed closed?] :as st}]
  (if (and (seq changed) (not closed?))
    (assoc st :latest (runtime/frame! rt root changed) :changed #{})
    st))


(defn- deliver-ssr
  [{:keys [ssr latest] :as st}]
  (if (and ssr latest)
    (do (deliver ssr latest)
        (assoc st :ssr nil :sent latest))
    st))


(defn- wire-stats
  [{:keys [rt latest frames chars]} patched patch-chars]
  {:_wire {:frames  frames
           :total   chars
           :last    patch-chars
           :page    (island/frame-chars latest)
           :patched (str/join ", " (map :id patched))
           :renders (->> (runtime/render-counts rt)
                      (map (fn [[id n]] (str id " " n)))
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


(defn- next-events
  "Blocks until an event arrives or `ms` elapse, then takes everything queued."
  [^LinkedBlockingQueue mailbox ms]
  (let [evs (ArrayList.)]
    (when-some [ev (LinkedBlockingQueue/.poll mailbox (long ms) TimeUnit/MILLISECONDS)]
      (ArrayList/.add evs ev)
      (LinkedBlockingQueue/.drainTo mailbox evs))
    evs))


(defn- finish!
  [{:keys [session ssr]}]
  (some-> ssr (deliver nil))
  (deliver (:closed session) true)
  ;; An attach that raced the close gets its connection closed, here or by
  ;; `attach!` itself, so the client retries into a new session.
  (run! #(when (and (vector? %) (= :attach (first %))) (sse/close! (second %)))
    (next-events (:mailbox session) 0))
  (println "session: closed" (:tab session)))


(defn- pump!
  [session root]
  (let [rt (runtime/runtime (select-keys session [:tab :uid]) #(post! session [:changed %]))]
    (try
      (loop [st {:session     session
                 :rt          rt
                 :root        root
                 :latest      (runtime/frame! rt root #{})
                 :changed     #{}
                 :detached-at (now)}]
        (if (:closed? st)
          (finish! st)
          (recur (as-> st st
                   (reduce handle st (next-events (:mailbox session) (wait-ms st)))
                   (render st)
                   (deliver-ssr st)
                   (sync-client st)
                   (expire st)))))
      (finally
        (runtime/dispose! rt)))))


;; API ------------------------------------------------------------------------


(defn create!
  "Starts a session for `tab`, owned by `uid`, rendering the root island
  `(root-fn {:tab tab :uid uid})`."
  [tab uid root-fn]
  (let [session {:tab tab :uid uid :mailbox (LinkedBlockingQueue.) :closed (promise) :attached? false}
        root    (root-fn {:tab tab :uid uid})]
    (swap! !sessions assoc tab session)
    (println "session: created" tab)
    (q/compel
      (q/task
        (try
          (pump! session root)
          (catch Throwable t
            (unregister! session)
            (println "session: pump died" tab t)))))
    session))


(defn obtain!
  "The session for `tab` if `uid` owns it. A fresh one if `tab` is unknown: it
  expired, or the server restarted, and the client resyncs from its first frame.
  Nil if another user owns it."
  [tab uid root-fn]
  (let [session (get @!sessions tab)]
    (cond
      (nil? session)          (create! tab uid root-fn)
      (= uid (:uid session))  session
      :else                   nil)))


(defn render-page!
  "The session's first frame, for rendering the page server-side. The session
  records that the client shows it, so attaching sends only what changed since."
  [session]
  (let [p (promise)]
    (post! session [:ssr p])
    (deref p 5000 nil)))


(defn attach!
  [session sse]
  (post! session [:attach sse])
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
