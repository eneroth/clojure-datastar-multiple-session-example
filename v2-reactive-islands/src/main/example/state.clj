(ns example.state
  "Stand-in for the database: application state shared by every session.
  In the platform these would be PStates, read through proxies (`example.resource`)
  and written through depots."
  (:require
    [clojure.string :as str]))


;; uid -> #{permission}
(defonce !acl (atom {}))


;; Oldest first, capped at `max-messages`.
(defonce !messages (atom []))


(def max-messages 5)


(def max-message-length 140)


(defn ensure-user!
  "First sight of `uid`: grant the vault, so the demo starts with it mounted."
  [uid]
  (swap! !acl update uid #(or % #{:vault})))


(defn allowed?
  [acl uid permission]
  (contains? (get acl uid) permission))


(defn toggle!
  [uid permission]
  (swap! !acl update uid #(if (contains? % permission) (disj % permission) (conj (or % #{}) permission))))


(defn post-message!
  "Validates `text`, which arrived as a client signal and is untrusted, then posts it.
  Returns true if posted."
  [uid text]
  (let [text (when (string? text)
               (str/trim (subs text 0 (min (count text) max-message-length))))]
    (when (seq text)
      (swap! !messages #(vec (take-last max-messages (conj % {:uid uid :text text}))))
      true)))
