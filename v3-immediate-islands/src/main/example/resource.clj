(ns example.resource
  "Shared, reference-counted stateful resources: stand-ins for Rama proxies and
  poll loops.

  There is one physical resource per key, however many sessions watch it. It
  opens on the first subscriber. After the last subscriber leaves, it lingers for
  `linger-ms` and then closes, unless someone subscribes again first. The linger
  absorbs remount churn: switching back and forth, navigation, and reconnects.

  Keys are `[pstate-name path]`. Islands subscribe with `example.hooks/<-`, which
  holds a subscription for as long as the island's renders keep asking for it:
  a render that stops, or the island unmounting, releases it. Nobody calls
  `release!` by hand.

  `simulated-pstate` stands in for a Rama PState: it opens a resource per path."
  (:require
    [co.multiply.quiescent :as q])
  (:import
    (java.time LocalTime)
    (java.time.format DateTimeFormatter)))


(def linger-ms 5000)


;; A resource's value until its first publish.
(def pending ::pending)


(defonce ^:private lock (Object.))


;; key -> {:subscribers n, :live? bool, :!value atom, :task Task, :linger Task, :token Object}
;; Changes only on lifecycle events, never on publish: values live in `:!value`.
(defonce !entries (atom {}))


(defonce !totals (atom {:opened 0 :closed 0}))


(defn- mark-live!
  [key token]
  (locking lock
    (when (identical? token (get-in @!entries [key :token]))
      (swap! !entries assoc-in [key :live?] true))))


(defn- open!
  "Starts the physical resource for `key`. Caller holds `lock`."
  [key open-fn]
  (let [token    (Object.)
        !value   (atom pending)
        live?    (volatile! false)
        publish! (fn [v]
                   (reset! !value v)
                   (when-not @live?
                     (vreset! live? true)
                     (mark-live! key token)))]
    (println "resource: open" key)
    (swap! !totals update :opened inc)
    ;; Compelled: the resource belongs to the registry, not to whichever session
    ;; happened to subscribe first.
    {:subscribers 0
     :live?       false
     :!value      !value
     :token       token
     :task        (q/compel (open-fn publish!))}))


(defn- close-if-idle!
  [key token]
  (locking lock
    (let [{:keys [subscribers task] :as entry} (get @!entries key)]
      (when (and (identical? token (:token entry)) (zero? subscribers))
        (println "resource: close" key)
        (q/cancel task)
        (swap! !entries dissoc key)
        (swap! !totals update :closed inc)))))


(defn acquire!
  "Registers a subscriber to `key`, opening the resource with `open-fn` if
  nobody holds it. `open-fn` receives a `publish!` fn and returns a Quiescent
  task; cancelling the task closes the resource. Returns the resource's value
  atom, which holds `pending` until the first publish."
  [key open-fn]
  (locking lock
    (let [entry (or (get @!entries key) (open! key open-fn))]
      (some-> (:linger entry) q/cancel)
      (swap! !entries assoc key (-> entry (update :subscribers inc) (dissoc :linger)))
      (:!value entry))))


(defn release!
  "Unregisters a subscriber to `key`. The last one out starts the linger."
  [key]
  (locking lock
    (let [{:keys [subscribers token] :as entry} (update (get @!entries key) :subscribers dec)]
      (swap! !entries assoc key
        (cond-> entry
          (zero? subscribers)
          (assoc :linger (q/compel
                           (-> (q/sleep linger-ms)
                             (q/then (fn [_] (close-if-idle! key token)))))))))))


(def ^:private clock (DateTimeFormatter/ofPattern "HH:mm:ss.SSS"))


(defn ticker
  "A simulated stateful resource, in place of a Rama proxy: after a `connect-ms`
  handshake, a loop in a Quiescent task publishes a new reading every
  `period-ms` until the task is cancelled."
  [{:keys [connect-ms period-ms]}]
  (fn [publish!]
    (q/task
      (Thread/sleep (long connect-ms))
      (loop [n 1]
        (publish! {:n       n
                   :reading (rand-int 1000)
                   :at      (LocalTime/.format (LocalTime/now) clock)})
        (Thread/sleep (long period-ms))
        (recur (inc n))))))


(defrecord SimulatedPState [name open])


(defn simulated-pstate
  "A stand-in for a Rama PState named `name`. `open` is `(fn [path publish!] task)`:
  what a proxy on `path` does, publishing each new value until the task is
  cancelled."
  [name open]
  (->SimulatedPState name open))


(defn state
  [{:keys [subscribers live?]}]
  (cond
    (zero? subscribers) :lingering
    live?               :live
    :else               :connecting))


(defn summary
  "Display view of `entries`: key -> {:state, :subscribers}."
  [entries]
  (into (sorted-map-by #(compare (str %1) (str %2)))
    (map (fn [[k e]] [k {:state (state e) :subscribers (:subscribers e)}]))
    entries))


(comment
  @!entries
  @!totals
  (summary @!entries))
