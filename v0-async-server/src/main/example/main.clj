(ns example.main
  (:require
    [example.core :as c]
    [example.lifecycle :as lifecycle]
    [example.server :as server]))


(def render-frequency 1000)


(defn -main
  [& _]
  (let [server (server/start! c/handler)
        stop-lifecycle (lifecycle/start! render-frequency)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (server/stop! server)
                                 (stop-lifecycle)
                                 (shutdown-agents))))))


(comment
  (def stop (-main))
  )
