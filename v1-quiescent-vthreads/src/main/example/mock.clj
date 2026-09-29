(ns example.mock)


(defn get-message
  [n]
  (str "Hello from session " n))


(defn subscription
  "Push: a thread writes `(next-value)` into the `:value` atom every `ms`.
  `:stop` is what `unmount` calls. A real subscription (db watcher, channel) keeps this shape.
  ponytail: one thread per subscription; share a scheduler if sessions grow past hundreds."
  [next-value ^long ms]
  (let [!value (atom (next-value))
        fut (future (while true (Thread/sleep ms) (reset! !value (next-value))))]
    {:value !value :stop #(future-cancel fut)}))


(comment
  (def x (subscription (fn [] (rand-int 10)) 1000))
  #__)
