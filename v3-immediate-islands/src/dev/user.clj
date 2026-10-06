(ns user
  (:require
    [clj-reload.core :as reload]
    [example.core :as c]
    [example.resource :as resource]
    [example.server :as server]
    [example.session :as session]))


(alter-var-root #'*warn-on-reflection* (constantly true))


(reload/init
  {:no-reload ['user]})


(defn reload!
  "Closes every session (their resources close after the linger), reloads changed
  namespaces, and restarts the server. Open tabs reconnect into fresh sessions."
  []
  (session/close-all!)
  (reload/reload)
  (server/reboot-jetty-server! #'c/handler))


(comment
  (reload!)

  ;; sessions, by tab
  @session/!sessions

  ;; the resource registry
  (resource/summary @resource/!entries)
  @resource/!totals

  *e)
