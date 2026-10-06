(ns example.main
  (:require
    [example.core :as c]
    [example.server :as server]
    [example.session :as session]))


(defn -main
  [& _]
  (let [server (server/start! c/handler)]
    (Runtime/.addShutdownHook (Runtime/getRuntime)
      (Thread. (fn []
                 (session/close-all!)
                 (server/stop! server)
                 (shutdown-agents))))))
