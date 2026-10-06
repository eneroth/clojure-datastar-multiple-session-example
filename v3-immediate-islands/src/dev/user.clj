(ns user
  (:require
    [clj-reload.core :as reload]
    [co.multiply.remontoire :as remontoire]
    [example.core :as c]
    [example.resource :as resource]
    [example.server :as server]
    [example.session :as session]))


(alter-var-root #'*warn-on-reflection* (constantly true))


(reload/init
  {:no-reload ['user]})


;; MCP endpoint into this JVM, for agents: http://127.0.0.1:7888/mcp
(remontoire/init!
  {:describe "a local dev JVM running v3-immediate-islands, a Datastar proof of concept"})


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
