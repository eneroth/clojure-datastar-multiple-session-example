(ns example.lifecycle
  (:require
    [example.core :as core])
  (:import
    (java.util.concurrent
      Executors
      ScheduledExecutorService
      TimeUnit)))


(defn start!
  "Runs `core/render-and-cleanup-all!` every `ms` on a single-thread scheduler. Returns a stop fn."
  [ms]
  (let [ex (Executors/newSingleThreadScheduledExecutor)]
    ;; ponytail: try/catch is mandatory, an uncaught throw silently kills the schedule
    (.scheduleAtFixedRate ex #(try (core/render-and-cleanup-all!) (catch Throwable t (println "tick error" t)))
                          0 ms TimeUnit/MILLISECONDS)
    (fn stop! [] (.shutdownNow ^ScheduledExecutorService ex) nil)))


(def tick 1000)

(defonce !loop (atom nil))


(defn restart!
  []
  (swap! !loop (fn [stop] (when stop (stop)) (start! tick))))


(defn stop!
  []
  (swap! !loop (fn [stop] (when stop (stop)) nil)))


(comment
  (restart!)
  (stop!)

  ;; or bare, no global:
  (def stop (start! 1000))
  (stop)


  )
