(ns example.island
  "Islands: the unit of rendering, caching and patching, written as functions.

  `defisland` defines a function returning hiccup, as a React component does.
  Calling an island inside another island's hiccup does not render it: it places
  the island there, with its arguments, for the runtime (`example.runtime`) to
  reconcile. An island renders again only when its arguments change (`=`), or
  when something it read through a hook changed. Otherwise its last output is
  reused, and so is everything it holds.

  Hooks run only during a render. Each takes a key, unique within the island,
  instead of relying on call order:

  - `use-watch`   read a ref (atom, etc.); re-render when the selected value changes.
  - `use-state`   island-local state, kept while the island is mounted.
  - `use-hold`    hold something (a subscription, a token) while renders keep asking for it.
  - `use-session` the session's `{:tab :uid}`.

  `example.hooks` (`<-` and `?`) and `example.action/use-action` build on these."
  (:require
    [dev.onionpancakes.chassis.core :as h]))


(declare write-frame!)


;; The render in progress: the runtime's hook implementations for this island.
(def ^:dynamic *render* nil)


;; Calls ------------------------------------------------------------------------


(deftype Call [id render args]
  h/Node
  (branch? [_] false)
  (children [_] nil)

  h/Token
  (append-fragment-to [_ _]
    (throw (IllegalStateException. (str "Island " id " rendered outside the island runtime."))))
  (fragment [_]
    (throw (IllegalStateException. (str "Island " id " rendered outside the island runtime.")))))


(defn call-id
  [^Call call]
  (.-id call))


(defn call-render
  [^Call call]
  (.-render call))


(defn call-args
  [^Call call]
  (.-args call))


(defn island-fn
  "The function `defisland` defines: it returns a `Call` for the island named
  `island-name` with `render` and the given arguments."
  [island-name {:keys [key]} render]
  (fn [& args]
    (Call. (if key (str island-name "-" (apply key args)) island-name) render (vec args))))


(defmacro defisland
  "Defines an island: a function of its arguments returning hiccup, rendered and
  patched on its own.

  The island's element id is its name. It is set on the root element the
  render returns, so the render must not set an id of its own; a root that
  isn't an element is wrapped in a `:div`. Ids must be unique on the page. To
  place several instances, give a `:key` fn of the arguments in an options map,
  and the id becomes `name-<key>`:

      (defisland message {:key :n} [msg]
        [:li (:text msg)])"
  [island-name & decl]
  (let [[doc decl]      (if (string? (first decl)) [(first decl) (next decl)] [nil decl])
        [opts decl]     (if (map? (first decl)) [(first decl) (next decl)] [nil decl])
        [params & body] decl
        island-name     (cond-> (vary-meta island-name assoc :arglists (list 'quote (list params)))
                          doc (vary-meta assoc :doc doc))]
    `(def ~island-name
       (island-fn ~(str island-name) ~opts (fn ~island-name ~params ~@body)))))


;; Hooks ------------------------------------------------------------------------


(defn- current
  [hook]
  (or *render*
      (throw (IllegalStateException. (str hook " called outside an island render.")))))


(defn use-session
  "The session's context: `{:tab :uid}`."
  []
  (:session (current "use-session")))


(defn use-watch
  "The current value of `ref` (anything supporting `add-watch`), through `select`
  if given. The island renders again when the selected value changes, compared
  with `=`; a change that `select` maps to an equal value renders nothing.

  `select` runs on the session's thread whenever `ref` changes, so keep it cheap."
  ([ref]
   (use-watch ref identity))
  ([ref select]
   ((:read! (current "use-watch")) ref select)))


(defn use-hold
  "Holds a value under `k` for as long as the island's renders keep calling
  `(use-hold k ...)`. `acquire` runs when the island first asks for `k` and
  returns `{:value v, :release f}`. `release` runs after the first render that no
  longer asks for `k`, or when the island unmounts. Returns `v`."
  [k acquire]
  ((:hold! (current "use-hold")) k acquire))


(defn use-state
  "Island-local state under `k`, starting at `init`. Returns `[value set-value!]`.
  `set-value!` may be called from any thread, typically from an action, and
  returns nil. The state lives as long as the island is mounted."
  [k init]
  (let [!state (:state (current "use-state"))]
    [(use-watch !state #(get % k init))
     (fn [v]
       (swap! !state assoc k v)
       nil)]))


;; Templates and frames ---------------------------------------------------------


;; Where a child island goes in its parent's template.
(defrecord Hole [id])


;; `parts`: strings and Holes, in document order.
;; `chars`: total length of the string parts.
(defrecord Template [parts chars])


;; `slots`: child island id -> child Frame.
(defrecord Frame [id template slots]
  h/Node
  (branch? [_] false)
  (children [_] nil)

  h/Token
  (append-fragment-to [this sb]
    (write-frame! sb this))
  (fragment [this]
    (str (write-frame! (StringBuilder.) this))))


(defn- with-id
  "Puts island `id` on the root element of `node`, wrapping a root that isn't one."
  [id node]
  (if (and (vector? node) (keyword? (first node)))
    (let [[tag & more]     node
          [attrs children] (if (map? (first more)) [(first more) (rest more)] [{} more])]
      (when (or (contains? attrs :id) (re-find #"#" (name tag)))
        (throw (IllegalArgumentException.
                 (str "Island " id ": the root element gets the island's id; don't set one."))))
      (into [tag (assoc attrs :id id)] children))
    [:div {:id id} node]))


(defn- flush-part
  [parts ^StringBuilder sb]
  (let [s (StringBuilder/.toString sb)]
    (StringBuilder/.setLength sb 0)
    (cond-> parts (pos? (count s)) (conj s))))


(defn compile-island
  "Serializes the hiccup `node` that island `id` rendered, once, with the island's
  id on its root. Returns `[template calls]`: the template's parts are strings
  and holes for child islands, and `calls` are the child islands in document
  order.
  Throws if two children share an id."
  [id node]
  (let [sb            (StringBuilder.)
        [parts calls] (h/reduce-node
                        (fn [[parts calls] token]
                          (if (instance? Call token)
                            [(conj (flush-part parts sb) (->Hole (call-id token))) (conj calls token)]
                            (do (h/append-fragment-to token sb)
                                [parts calls])))
                        [[] []]
                        (with-id id node))
        parts         (flush-part parts sb)]
    (when-not (apply distinct? nil (map call-id calls))
      (throw (IllegalArgumentException.
               (str "Island " id " places two islands with the same id; give them a :key."))))
    [(->Template parts (transduce (comp (filter string?) (map count)) + parts))
     calls]))


(defn error-view
  [id message]
  [:div {:class [:rounded :border :border-red-400 :p-3 :text-sm :text-red-600]}
   [:strong "Island " id " failed to render: "] message])


(defn error-frame
  "A childless frame for `id` showing `message`."
  [id message]
  (->Frame id (first (compile-island id (error-view id message))) {}))


(defn write-frame!
  "Appends the HTML of `frame` to `sb`: its template's string parts, with each hole
  filled by the corresponding child frame. Returns `sb`."
  [^StringBuilder sb frame]
  (let [slots (:slots frame)]
    (run! (fn [part]
            (if (string? part)
              (StringBuilder/.append sb ^String part)
              (write-frame! sb (get slots (:id part)))))
      (:parts (:template frame)))
    sb))


(defn html
  "The full HTML of `frame`, children included."
  [frame]
  (str (write-frame! (StringBuilder.) frame)))


(defn patches
  "The frames to send to bring a client showing `sent` up to `frame`: the topmost
  islands whose own template changed. An island with an identical template
  recurses into its children. Its children are then the same islands, since
  their ids are part of the template."
  [sent frame]
  (cond
    (identical? sent frame)
    []

    (or (nil? sent)
        (not= (:id sent) (:id frame))
        (not (identical? (:template sent) (:template frame))))
    [frame]

    :else
    (into [] (mapcat (fn [[k child]] (patches (get (:slots sent) k) child))) (:slots frame))))


(defn frame-chars
  "Length of the HTML `frame` emits, computed from cached template sizes."
  [frame]
  (transduce (map frame-chars) + (:chars (:template frame)) (vals (:slots frame))))
