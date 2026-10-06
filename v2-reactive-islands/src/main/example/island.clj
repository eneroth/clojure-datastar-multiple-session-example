(ns example.island
  "Islands: the unit of rendering, caching and patching.

  An island is a Missionary continuous flow of `Frame`s for one element id. Its
  `render` fn is plain hiccup over the latest values of its inputs; child
  islands appear in that hiccup as `(slot k)` holes. A render is compiled once
  into a `Template` (string parts and holes), so:

  - an island re-renders only when one of its own inputs changes, never when a
    child does;
  - emitting a page is appending cached strings; nothing is re-rendered;
  - `patches` finds the topmost islands whose own template changed, by identity."
  (:require
    [dev.onionpancakes.chassis.core :as h]
    [missionary.core :as m])
  (:import
    (java.util.concurrent.atomic AtomicLong)))


(declare write-frame!)


(deftype Slot [k]
  h/Node
  (branch? [_] false)
  (children [_] nil)

  h/Token
  (append-fragment-to [_ _]
    (throw (IllegalStateException. (str "Slot " k " rendered outside an island."))))
  (fragment [_]
    (throw (IllegalStateException. (str "Slot " k " rendered outside an island.")))))


(defn slot
  "A hole in an island's hiccup, filled by the child island under `k` in its `:slots`."
  [k]
  (Slot. k))


;; `parts`: strings and slot keys, in document order.
;; `n`:     which render of the island produced this template (1-based).
;; `chars`: total length of the string parts.
(defrecord Template [parts n chars])


;; `slots`: slot key -> child Frame.
(defrecord Frame [id template slots]
  h/Node
  (branch? [_] false)
  (children [_] nil)

  h/Token
  (append-fragment-to [this sb]
    (write-frame! sb this))
  (fragment [this]
    (str (write-frame! (StringBuilder.) this))))


(defn- flush-part
  [parts ^StringBuilder sb]
  (let [s (StringBuilder/.toString sb)]
    (StringBuilder/.setLength sb 0)
    (cond-> parts (pos? (count s)) (conj s))))


(defn- compile-template
  "Serializes hiccup `node` once, splitting the output at slots."
  [node n]
  (let [sb    (StringBuilder.)
        parts (h/reduce-node
                (fn [parts token]
                  (if (instance? Slot token)
                    (conj (flush-part parts sb) (.-k ^Slot token))
                    (do (h/append-fragment-to token sb) parts)))
                []
                node)
        parts (flush-part parts sb)]
    (->Template parts n (transduce (comp (filter string?) (map count)) + parts))))


(defn write-frame!
  "Appends the HTML of `frame` to `sb`: its template's string parts, with each hole
  filled by the corresponding child frame. Returns `sb`."
  [^StringBuilder sb frame]
  (let [slots (:slots frame)]
    (run! (fn [part]
            (if (string? part)
              (StringBuilder/.append sb ^String part)
              (write-frame! sb (get slots part))))
      (:parts (:template frame)))
    sb))


(defn html
  "The full HTML of `frame`, children included."
  [frame]
  (str (write-frame! (StringBuilder.) frame)))


(defn collect
  "Continuous flow of a map with the latest value of each flow in `flows`, a map
  of key -> continuous flow."
  [flows]
  (if (empty? flows)
    (m/cp {})
    (let [ks (vec (keys flows))]
      (apply m/latest (fn [& vs] (zipmap ks vs)) (map flows ks)))))


(defn- error-view
  [id ^Throwable e]
  [:div {:class [:rounded :border :border-red-400 :p-3 :text-sm :text-red-600]}
   [:strong "Island " id " failed to render: "] (ex-message e)])


(defn island
  "Returns a continuous flow of `Frame`s for the element with `id`.

  Options:
  - `:inputs` map of key -> continuous flow. `render` receives a map of their latest values.
  - `:slots`  map of key -> continuous flow of child `Frame`s, placed where `render` put `(slot key)`.
  - `:tag`    wrapper element, default `:div`. `:attrs` adds attributes to it; the id is set here.

  `render` runs when an input changes to a value not `=` to the previous one
  (Missionary's `latest` skips equal inputs), never because a child changed. A
  render that throws renders an error box in place: one broken island does not
  take the session's root flow down with it."
  [id {:keys [inputs slots tag attrs] :or {tag :div}} render]
  (let [renders  (AtomicLong.)
        wrap     (fn [body] [tag (assoc attrs :id id) body])
        template (m/latest
                   (fn [vals]
                     (let [n (AtomicLong/.incrementAndGet renders)]
                       (try
                         (compile-template (wrap (render vals)) n)
                         (catch Exception e
                           (compile-template (wrap (error-view id e)) n)))))
                   (collect inputs))]
    (m/latest (fn [template slots] (->Frame id template slots))
      template
      (collect slots))))


(defn switch-by
  "Continuous flow mounting the flow `(f v)` for the latest value `v` of `flow`.
  A new value unmounts the previous branch: its process is cancelled, so its
  resources are released and its actions revoked. An equal value keeps it."
  [flow f]
  (m/cp (m/?< (f (m/?< flow)))))


(defn gate
  "Mounts `then` while `allowed` (a continuous flow of booleans) is truthy, else
  `else`. Both should render the same element id: a slot always holds the same
  island, and the gate decides its content."
  [allowed then else]
  (switch-by allowed #(if % then else)))


(defn patches
  "The frames to send to bring a client showing `sent` up to `frame`: the topmost
  islands whose own template changed. An island with an identical template
  recurses into its children. If a child's id changed, the parent is sent whole,
  since the client has no element to patch under the new id."
  [sent frame]
  (cond
    (identical? sent frame)
    []

    (or (nil? sent)
        (not= (:id sent) (:id frame))
        (not (identical? (:template sent) (:template frame))))
    [frame]

    (not-every? (fn [[k child]] (= (:id child) (:id (get (:slots sent) k)))) (:slots frame))
    [frame]

    :else
    (into [] (mapcat (fn [[k child]] (patches (get (:slots sent) k) child))) (:slots frame))))


(defn frame-chars
  "Length of the HTML `frame` emits, computed from cached template sizes."
  [frame]
  (transduce (map frame-chars) + (:chars (:template frame)) (vals (:slots frame))))


(defn render-counts
  "Map of island id -> number of renders, over `frame` and its descendants."
  [frame]
  (transduce (map render-counts) merge {(:id frame) (:n (:template frame))} (vals (:slots frame))))
