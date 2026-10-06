(ns example.session
  "Tab sessions: one reactive root per browser tab, outliving any single SSE
  connection.

  A session is created when the page is served, and the page is rendered from
  its first frame. The tab's `@get('/stream')` then attaches to it. Datastar
  drops that stream routinely: a hidden tab closes it, network blips retry it.
  A detached session keeps its root flow, and so its resources, for `grace-ms`.
  A reconnect within that window resyncs from the cached frame without
  reopening anything. After the window, the root flow is cancelled and
  everything under it is released.

  Each session is run by one pump: a virtual thread that owns all of the
  session's mutable state and consumes a mailbox of events. Flow notifications
  only post to the mailbox. Sampling (rendering) happens on the pump, never on
  the thread that changed an input, so a shared resource's loop never renders
  on anyone's behalf. A continuous flow is sampled only when the pump is ready,
  so a slow client gets fewer, later frames, never a backlog."
  (:require
    [clojure.string :as str]
    [co.multiply.quiescent :as q]
    [example.island :as island]
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
;;   :it          the root flow's iterator
;;   :dirty?      the root has notified and has not been sampled since
;;   :latest      the last sampled Frame
;;   :conn        the attached SSE generator, or nil
;;   :sent        the Frame the client is showing; nil means unknown, so resync
;;   :ssr         a promise awaiting the first frame, for server-side rendering
;;   :closing?    the session is closing; the root is cancelled and being drained
;;   :done?       the root has terminated


(defn- detach
  [st]
  (set-attached! (:session st) false)
  (assoc st :conn nil :sent nil :detached-at (now)))


(defn- close
  "Cancels the root flow. Its processes release their resources as the pump
  drains it; the pump exits once it terminates."
  [{:keys [it conn session] :as st}]
  (if (:closing? st)
    st
    (do (println "session: closing" (:tab session))
        (unregister! session)
        (when conn (sse/close! conn))
        (when-not (:done? st) (it))
        (assoc st :closing? true :conn nil))))


(defn- handle
  [st event]
  (case (if (vector? event) (first event) event)
    :dirty  (assoc st :dirty? true)
    :done   (assoc st :done? true)
    :close  (close st)
    :ssr    (assoc st :ssr (second event))
    :attach (let [conn (second event)
                  prev (:conn st)]
              (cond
                (:closing? st)
                (do (sse/close! conn) st)

                :else
                (do (when (and prev (not (identical? prev conn)))
                      (sse/close! prev))
                    (set-attached! (:session st) true)
                    ;; Replacing a live connection: we don't know what the client got.
                    (cond-> (assoc st :conn conn :detached-at nil)
                      prev (assoc :sent nil)))))
    :detach (if (identical? (second event) (:conn st))
              (detach st)
              st)))


(defn- sample
  "Samples the root when it has changed and someone will look at the result: an
  attached client, a pending server-side render, or the drain after cancellation."
  [{:keys [it dirty? conn ssr closing? latest] :as st}]
  (if (and dirty? (or conn ssr closing? (nil? latest)))
    (try
      (assoc st :latest @it :dirty? false)
      (catch Throwable t
        (when-not closing?
          (println "session: root flow failed" (ex-message t)))
        (assoc st :dirty? false)))
    st))


(defn- deliver-ssr
  [{:keys [ssr latest] :as st}]
  (if (and ssr latest)
    (do (deliver ssr latest)
        (assoc st :ssr nil :sent latest))
    st))


(defn- wire-stats
  [{:keys [latest frames chars]} patched patch-chars]
  {:_wire {:frames  frames
           :total   chars
           :last    patch-chars
           :page    (island/frame-chars latest)
           :patched (str/join ", " (map :id patched))
           :renders (->> (island/render-counts latest)
                      (sort-by key)
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
  [{:keys [conn sent latest closing? last-send] :as st}]
  (cond
    (or (nil? conn) (nil? latest) closing?)
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
  [{:keys [conn detached-at closing?] :as st}]
  (if (and (nil? conn) (not closing?) (< (+ detached-at (long grace-ms)) (now)))
    (close st)
    st))


(defn- wait-ms
  [{:keys [conn detached-at closing?]}]
  (cond
    closing? 1000
    conn     heartbeat-ms
    :else    (max 1 (- (+ detached-at (long grace-ms)) (now)))))


(defn- next-events
  "Blocks until an event arrives or `ms` elapse, then takes everything queued."
  [^LinkedBlockingQueue mailbox ms]
  (let [evs (ArrayList.)]
    (when-some [ev (LinkedBlockingQueue/.poll mailbox (long ms) TimeUnit/MILLISECONDS)]
      (ArrayList/.add evs ev)
      (LinkedBlockingQueue/.drainTo mailbox evs))
    evs))


(defn- pump!
  [session root]
  (let [it (root #(post! session :dirty) #(post! session :done))]
    (loop [st {:session session :it it :detached-at (now)}]
      ;; A root that terminates on its own (all of it static, or failed) keeps
      ;; its last frame, and the session serves it until it is closed.
      (if (and (:done? st) (:closing? st))
        (do (when-let [conn (:conn st)] (sse/close! conn))
            (some-> (:ssr st) (deliver nil))
            (unregister! session)
            (deliver (:closed session) true)
            ;; An attach that raced the close gets its connection closed, here
            ;; or by `attach!` itself, so the client retries into a new session.
            (run! #(when (and (vector? %) (= :attach (first %))) (sse/close! (second %)))
              (next-events (:mailbox session) 0))
            (println "session: closed" (:tab session)))
        (recur (as-> st st
                 (reduce handle st (next-events (:mailbox session) (wait-ms st)))
                 (sample st)
                 (deliver-ssr st)
                 (sync-client st)
                 (expire st)))))))


;; API ------------------------------------------------------------------------


(defn create!
  "Starts a session for `tab`, owned by `uid`, running the root flow
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
