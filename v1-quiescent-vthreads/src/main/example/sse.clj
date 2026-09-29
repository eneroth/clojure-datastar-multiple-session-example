(ns example.sse
  (:require
    [example.session :as session]
    [starfederation.datastar.clojure.adapter.ring :refer [->sse-response on-open on-close on-exception]]
    [starfederation.datastar.clojure.api :as d*]))


(defn send!
  "Patch `html` into the client behind `sse`. Returns false when the connection is dead."
  [sse html]
  #_(println "Sending HTML payload")
  (d*/patch-elements! sse html))


(defn close!
  [sse]
  (d*/close-sse! sse))


(defn handle-sse-open
  [{{{route-id :id} :data} :reitit.core/match :as req} sse-conn]
  (println "Opening SSE stream")
  (session/update-session! sse-conn req route-id))


(defn handle-sse-close
  [{{{route-id :id} :data} :reitit.core/match :as _req} sse-conn]
  (println "Closing SSE stream for " route-id)
  (session/cleanup-sessions! [sse-conn]))


(defn handle-sse-exception
  " Args:
   - `sse-gen`: the SSEGenerator
   - `e`: the exception
   - `ctx`: context information about the exception, a map whose keys are:
     - `:sse-gen`: the sse generator throwing
     - `:event-type`: type of the event that failed
     - `:data-lines`: data lines for this event
     - `:opts`: options used when sending"
  [_sse _e _ctx]
  #_(println "handle-sse-exception: Leaving closing of SSE connection to `cleanup!` function")
  ;; NOTE If fn return truthy the SDK automatically runs `on-close` fn
  false)


(defn sse-handler
  [req respond _raise]
  (respond
    (->sse-response req
                    {on-open #(handle-sse-open req %)
                     on-close #(handle-sse-close req %)
                     on-exception handle-sse-exception})))
