(ns example.action
  "Actions as capabilities: server-side closures reachable from the client only
  while the island rendering them is mounted.

  An action is a flow, like any other island input. Mounting it mints an
  unguessable token bound to the user; unmounting revokes it. So a POST can only
  reach closures that are currently mounted for that user, which is the same
  property Electric gets from `(e/server (when allowed (e/client ...)))`. The
  authority part of the check is captured at mount time. The arguments (Datastar
  signals) are untrusted input, as `e/client` -> `e/server` values always were."
  (:require
    [missionary.core :as m]))


;; token -> {:uid, :handler}
(defonce !actions (atom {}))


(defn action
  "Continuous flow of a Datastar `@post(...)` expression that invokes
  `(handler signals)` for user `uid`. The token exists while the flow runs.

  `handler` returns nil, or a map of signals to patch into the client."
  [uid handler]
  (m/observe
    (fn [!]
      (let [token (str (random-uuid))]
        (swap! !actions assoc token {:uid uid :handler handler})
        (! (str "@post('/act/" token "')"))
        #(swap! !actions dissoc token)))))


(defn invoke!
  "Runs the action behind `token` on behalf of `uid` with `signals`.
  Returns `[:ok result]`, `[:forbidden]` (another user's token) or `[:gone]`
  (never minted, or revoked because its island unmounted)."
  [token uid signals]
  (if-let [{owner :uid handler :handler} (get @!actions token)]
    (if (= owner uid)
      [:ok (handler signals)]
      [:forbidden])
    [:gone]))


(comment
  (count @!actions))
