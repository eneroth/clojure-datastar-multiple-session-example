(ns example.action
  "Actions as capabilities: server-side closures reachable from the client only
  while the island rendering them is mounted.

  `use-action` mints an unguessable token bound to the session's user the first
  time an island asks for it, and revokes it once the island stops asking or
  unmounts. So a POST can only reach closures currently rendered for that user,
  which is the property Electric gets from `(e/server (when allowed (e/client ...)))`.
  The authority part of the check happened when the island decided to render
  the action. The arguments (Datastar signals) are untrusted input, as
  `e/client` -> `e/server` values always were."
  (:require
    [example.island :as island]))


;; token -> {:uid, :!handler}
(defonce !actions (atom {}))


(defn- mint
  [uid]
  (let [token    (str (random-uuid))
        !handler (atom nil)]
    (swap! !actions assoc token {:uid uid :!handler !handler})
    {:value   {:token token :!handler !handler}
     :release #(swap! !actions dissoc token)}))


(defn use-action
  "A Datastar `@post(...)` expression that invokes `(handler signals)` on behalf
  of the session's user. `k` names the action within the island; the token
  stays the same across renders, and `handler` is replaced by each render's.

  `handler` returns a map of signals to patch into the client, or nil."
  [k handler]
  (let [{:keys [uid]}            (island/use-session)
        {:keys [token !handler]} (island/use-hold [::action k] #(mint uid))]
    (reset! !handler handler)
    (str "@post('/act/" token "')")))


(defn invoke!
  "Runs the action behind `token` on behalf of `uid` with `signals`.
  Returns `[:ok result]`, `[:forbidden]` (another user's token) or `[:gone]`
  (never minted, or revoked because its island no longer renders it)."
  [token uid signals]
  (if-let [{owner :uid !handler :!handler} (get @!actions token)]
    (if (= owner uid)
      [:ok (@!handler signals)]
      [:forbidden])
    [:gone]))


(comment
  (count @!actions))
