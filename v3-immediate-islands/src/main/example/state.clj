(ns example.state
  "Stand-in for the database: application state shared by every session.

  - `$$feeds` and `$$vault` stand in for PStates read through proxies (`<-`).
  - `describe-topic` stands in for a one-off query (`?`).
  - The atoms stand in for state written through depots; islands read them
    with `use-watch`."
  (:require
    [clojure.string :as str]
    [co.multiply.quiescent :as q]
    [example.resource :as resource])
  (:import
    (java.time LocalTime)
    (java.time.format DateTimeFormatter)))


(def topics
  {"alpha" {:connect-ms 300 :period-ms 1000 :about "Steady"}
   "beta"  {:connect-ms 600 :period-ms 400 :about "Fast"}
   "gamma" {:connect-ms 900 :period-ms 2500 :about "Slow"}})


(def $$feeds
  (resource/simulated-pstate "$$feeds"
    (fn [[topic] publish!]
      ((resource/ticker (topics topic)) publish!))))


(def $$vault
  (resource/simulated-pstate "$$vault"
    (fn [_ publish!]
      ((resource/ticker {:connect-ms 800 :period-ms 1500}) publish!))))


;; How often `describe-topic` ran, and how those runs ended.
(defonce !queries (atom {:started 0 :completed 0 :cancelled 0}))


(def query-ms 700)


(def ^:private clock (DateTimeFormatter/ofPattern "HH:mm:ss"))


(defn describe-topic
  "A one-off read standing in for a Rama query: after `query-ms`, a description
  of `topic`. Counts its runs in `!queries`."
  [topic]
  (swap! !queries update :started inc)
  (-> (q/task
        (Thread/sleep (long query-ms))
        (assoc (topics topic) :looked-up-at (LocalTime/.format (LocalTime/now) clock)))
    (q/finally (fn [_ _ cancelled]
                 (swap! !queries update (if cancelled :cancelled :completed) inc)))))


;; uid -> #{permission}
(defonce !acl (atom {}))


;; Oldest first, capped at `max-messages`. Each is {:n, :uid, :text}; `:n` is unique.
(defonce !messages (atom []))


(defonce ^:private !message-n (atom 0))


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
      (let [n (swap! !message-n inc)]
        (swap! !messages #(vec (take-last max-messages (conj % {:n n :uid uid :text text})))))
      true)))
