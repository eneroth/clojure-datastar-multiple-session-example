(ns example.server
  (:require
    [example.core :as c]
    [ring.adapter.jetty :as jetty])
  (:import
    org.eclipse.jetty.server.Server))


(defonce !jetty-server (atom nil))


(defn start! [handler & {:as opts}]
  (let [opts (merge {:port 8080
                     :join? false
                     :async? true
                     #_#_:async-timeout  10000 ;; 10 sec
                     #_#_:async-timeout-handler  (fn [req] nil)
                     }
                    opts)]
    (println "Starting server on port:" (:port opts))
    (jetty/run-jetty handler opts)))


(defn stop! [server]
  (println "Stopping server")
  (println server)
  (.stop ^Server server))


(defn reboot-jetty-server! [handler & {:as opts}]
  (swap! !jetty-server
         (fn [server]
           (when server
             (stop! server))
           (start! handler opts))))

(comment
  (reboot-jetty-server! #'c/handler)

  #__)
