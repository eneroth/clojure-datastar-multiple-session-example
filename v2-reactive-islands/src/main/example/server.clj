(ns example.server
  (:require
    [ring.adapter.jetty :as jetty])
  (:import
    (org.eclipse.jetty.server Server)))


(defonce !jetty-server (atom nil))


(defn start!
  [handler & {:as opts}]
  (let [opts (merge {:port 8080 :join? false :async? true} opts)]
    (println "Starting server on port:" (:port opts))
    (jetty/run-jetty handler opts)))


(defn stop!
  [server]
  (println "Stopping server")
  (Server/.stop server))


(defn reboot-jetty-server!
  [handler & {:as opts}]
  (swap! !jetty-server
    (fn [server]
      (when server
        (stop! server))
      (start! handler opts))))


(comment
  (require '[example.core :as c])
  (reboot-jetty-server! #'c/handler))
