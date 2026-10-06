(ns example.test-util
  (:require
    [co.multiply.quiescent :as q])
  (:import
    (java.util.concurrent LinkedBlockingQueue TimeUnit)))


(defn drive
  "Runs continuous `flow` the way a session pump does. Returns
  `{:sample f, :cancel f}`: `sample` samples until the flow is quiet for
  `quiet-ms` and returns the latest value; `cancel` cancels and drains it,
  which is when its processes release what they hold."
  [flow]
  (let [events  (LinkedBlockingQueue.)
        it      (flow #(LinkedBlockingQueue/.put events :dirty)
                  #(LinkedBlockingQueue/.put events :done))
        !latest (atom nil)
        settle  (fn [quiet-ms]
                  (loop []
                    (case (LinkedBlockingQueue/.poll events (long quiet-ms) TimeUnit/MILLISECONDS)
                      :dirty (do (try (reset! !latest @it) (catch Throwable _)) (recur))
                      (:done nil) @!latest)))]
    {:sample (fn [] (settle 100))
     :cancel (fn [] (it) (settle 100))}))


(defn manual-resource
  "A resource whose values the test publishes. Returns `[open-fn !publish]`;
  `@!publish` is the publish fn of the currently open instance."
  []
  (let [!publish (atom nil)]
    [(fn [publish!]
       (reset! !publish publish!)
       (q/task (Thread/sleep Long/MAX_VALUE)))
     !publish]))
