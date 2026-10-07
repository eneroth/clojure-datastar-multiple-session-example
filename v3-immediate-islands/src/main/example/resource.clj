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

  A resource whose task fails is closed: its value becomes `Failed`, so its
  readers throw, and the next subscriber to its key opens a new one.

  `simulated-pstate` stands in for a Rama PState: it opens a resource per path."
  (:require
    [co.multiply.quiescent :as q])
  (:import
    (example.signal Cell)
    (java.time LocalTime)
    (java.time.format DateTimeFormatter)))


(def linger-ms 5000)


;; A resource's value until its first publish.
(def pending ::pending)


;; The value of a resource, or of a one-off task, that failed: readers throw `error`.
(defrecord Failed [error])


(defn failed?
  [v]
  (instance? Failed v))


(defonce ^:private lock (Object.))


;; key -> {:subscribers n, :live? bool, :!value Cell, :task Task, :linger Task}
;; Changes only on lifecycle events, never on publish: values live in `:!value`.
;; `:!value` also identifies the entry: a key that closes and opens again gets a new one.
(defonce !entries (atom {}))


(defonce !totals (atom {:opened 0 :closed 0}))


(defn- current?
  "Whether `!value` belongs to the entry for `key` now. Caller holds `lock`."
  [key !value]
  (identical? !value (get-in @!entries [key :!value])))


(defn- mark-live!
  [key !value]
  (locking lock
    (when (current? key !value)
      (swap! !entries assoc-in [key :live?] true))))


(defn- fail!
  "Closes the entry whose task failed with `e`. Its readers get `Failed`, and
  the next subscriber to `key` opens a new resource."
  [key !value e]
  (locking lock
    (when (current? key !value)
      (println "resource: failed" key (ex-message e))
      (some-> (get-in @!entries [key :linger]) q/cancel)
      (swap! !entries dissoc key)
      (swap! !totals update :closed inc)))
  (Cell/.reset !value (->Failed e)))


(defn- open!
  "Starts the physical resource for `key` and returns its entry, not yet
  registered. Caller holds `lock`."
  [key open-fn]
  (let [!value   (Cell. pending)
        live?    (volatile! false)
        publish! (fn [v]
                   (Cell/.reset !value v)
                   (when-not @live?
                     (vreset! live? true)
                     (mark-live! key !value)))
        ;; Compelled: the resource belongs to the registry, not to whichever
        ;; session happened to subscribe first.
        task     (q/compel (open-fn publish!))]
    (println "resource: open" key)
    (swap! !totals update :opened inc)
    {:subscribers 0
     ;; `open-fn` may have published already, before the entry could be marked.
     :live?       (not= pending @!value)
     :!value      !value
     :task        task}))


(defn- watch-failure!
  "Closes the registered entry for `key` if its task fails. Compelled, like the
  task. Attached after registration, so a task that has already failed finds
  its entry."
  [key {:keys [task !value]}]
  (q/compel (q/err task #(fail! key !value %))))


(defn- close-if-idle!
  [key !value]
  (locking lock
    (when (and (current? key !value) (zero? (get-in @!entries [key :subscribers])))
      (println "resource: close" key)
      (q/cancel (get-in @!entries [key :task]))
      (swap! !entries dissoc key)
      (swap! !totals update :closed inc))))


(defn acquire!
  "Registers a subscriber to `key`, opening the resource with `open-fn` if
  nobody holds it. `open-fn` receives a `publish!` fn and returns a Quiescent
  task; cancelling the task closes the resource. Returns the resource's value, a
  `Cell` (`example.signal`) holding `pending` until the first publish; pass it to
  `release!`."
  [key open-fn]
  (locking lock
    (let [entry (get @!entries key)
          fresh (when-not entry (open! key open-fn))
          entry (or entry fresh)]
      (some-> (:linger entry) q/cancel)
      (swap! !entries assoc key (-> entry (update :subscribers inc) (dissoc :linger)))
      (when fresh
        (watch-failure! key fresh))
      (:!value entry))))


(defn release!
  "Unregisters a subscriber to `key`, given the value cell `acquire!` returned.
  The last one out starts the linger. Releasing a resource that has since failed
  does nothing: it is already closed."
  [key !value]
  (locking lock
    (when (current? key !value)
      (let [{:keys [subscribers] :as entry} (update (get @!entries key) :subscribers dec)]
        (swap! !entries assoc key
          (cond-> entry
            (zero? subscribers)
            (assoc :linger (q/compel
                             (-> (q/sleep linger-ms)
                               (q/then (fn [_] (close-if-idle! key !value))))))))))))


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
