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

  `example.hooks` (`<-` and `?`) and `example.action/use-action` build on these.

  Ids are scoped by parent, as React keys are. An island's slot is its name,
  plus `.key` for a keyed island, and is unique among its siblings. Its element
  id is the path of slots from the root, `app/vault-card/vault-panel`, so it
  is unique on the page wherever the island is placed. An island placed by a
  different parent is a different instance."
  (:require
    [dev.onionpancakes.chassis.core :as h])
  (:import
    (java.nio.charset StandardCharsets)))


(declare write-frame!)


;; The render in progress: the runtime's hook implementations for this island.
(def ^:dynamic *render* nil)


;; Calls ------------------------------------------------------------------------


(deftype Call [slot render args]
  h/Node
  (branch? [_] false)
  (children [_] nil)

  h/Token
  (append-fragment-to [_ _]
    (throw (IllegalStateException. (str "Island " slot " rendered outside the island runtime."))))
  (fragment [_]
    (throw (IllegalStateException. (str "Island " slot " rendered outside the island runtime.")))))


(defn call-slot
  "The island's slot in its parent: its name, plus `.key` if it has a key."
  [^Call call]
  (.-slot call))


(defn call-render
  [^Call call]
  (.-render call))


(defn call-args
  [^Call call]
  (.-args call))


(defn- id-char?
  [c]
  (or (<= (int \a) c (int \z)) (<= (int \A) c (int \Z)) (<= (int \0) c (int \9)) (= c (int \-))))


(defn- key-string
  "The key `k` as it appears in a slot. Letters, digits and `-` stand as they
  are. Any other character is written as `_` followed by its UTF-8 bytes in
  hex, so every key gives a valid id, and distinct keys distinct ids."
  [k]
  (let [s (if (keyword? k) (str (symbol k)) (str k))]
    (if (every? #(id-char? (int %)) s)
      s
      (let [sb (StringBuilder.)]
        (doseq [b (String/.getBytes s StandardCharsets/UTF_8)]
          (let [c (bit-and b 0xff)]
            (if (id-char? c)
              (StringBuilder/.append sb (char c))
              (StringBuilder/.append sb (format "_%02X" c)))))
        (str sb)))))


(defn child-id
  "The element id of the island in `slot` under the island `parent-id`; the
  root's (`parent-id` nil) is its slot."
  [parent-id slot]
  (if parent-id
    (str parent-id "/" slot)
    slot))


(defn island-fn
  "The function `defisland` defines: it returns a `Call` for the island named
  `island-name` with `render` and the given arguments. The name is a letter
  followed by letters, digits, `-` and `_`, so that it can't run into the `.`
  and `/` of ids."
  [island-name {:keys [key]} render]
  (when-not (re-matches #"[A-Za-z][A-Za-z0-9_-]*" island-name)
    (throw (IllegalArgumentException.
             (str "Island name " (pr-str island-name) ": use a letter followed by letters, digits, - and _."))))
  (if key
    (let [prefix (str island-name ".")]
      (fn [& args]
        (Call. (str prefix (key-string (apply key args))) render (vec args))))
    (fn [& args]
      (Call. island-name render (vec args)))))


(defmacro defisland
  "Defines an island: a function of its arguments returning hiccup, rendered and
  patched on its own.

  The island's element id is the path of slots from the root (see the
  namespace docstring). It is set on the root element the render returns, so
  the render must not set an id of its own; a root that isn't an element is
  wrapped in a `:div`. Siblings need distinct slots: to place several
  instances under one parent, give a `:key` fn of the arguments in an options
  map, and the slot becomes `name.<key>`:

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
  longer asks for `k`, or when the island unmounts. Returns `v`.

  A render asks for each `k` at most once: asking twice throws, since two call
  sites sharing one hold (two buttons sharing one action token) is a bug."
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
(defrecord Hole [slot])


;; `parts`: strings and Holes, in document order.
;; `chars`: total length of the string parts.
(defrecord Template [parts chars])


;; `id`: the island's element id. `slots`: child slot -> child Frame.
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
        (throw (IllegalArgumentException. "its root element gets the island's id; don't set one.")))
      (into [tag (assoc attrs :id id)] children))
    [:div {:id id} node]))


(defn- flush-part
  [parts ^StringBuilder sb]
  (if (pos? (StringBuilder/.length sb))
    (let [s (StringBuilder/.toString sb)]
      (StringBuilder/.setLength sb 0)
      (conj parts s))
    parts))


(defn compile-island
  "Serializes the hiccup `node` that island `id` rendered, once, with the island's
  id on its root. Returns `[template calls]`: the template's parts are strings
  and holes for child islands, and `calls` are the child islands in document
  order.
  Throws if two children share a slot, or the root sets its own id. The
  messages complete \"Island <id> failed to render: \"."
  [id node]
  (let [sb            (StringBuilder.)
        ;; The accumulator changes only at a child island; text tokens return it as is.
        [parts calls] (h/reduce-node
                        (fn [acc token]
                          (if (instance? Call token)
                            (let [[parts calls] acc]
                              [(conj (flush-part parts sb) (->Hole (call-slot token))) (conj calls token)])
                            (do (h/append-fragment-to token sb)
                                acc)))
                        [[] []]
                        (with-id id node))
        parts         (flush-part parts sb)]
    (when-not (apply distinct? nil (map call-slot calls))
      (throw (IllegalArgumentException. "it places two islands in one slot; give them distinct :key values.")))
    [(->Template parts (transduce (comp (filter string?) (map count)) + parts))
     calls]))


(defn error-view
  [id message]
  [:div {:class [:rounded :border :border-red-400 :p-3 :text-sm :text-red-600]}
   [:strong "Island " id " failed to render: "] message])


(defn write-frame!
  "Appends the HTML of `frame` to `sb`: its template's string parts, with each hole
  filled by the corresponding child frame. Returns `sb`."
  [^StringBuilder sb frame]
  (let [slots (:slots frame)]
    (run! (fn [part]
            (if (string? part)
              (StringBuilder/.append sb ^String part)
              (write-frame! sb (get slots (:slot part)))))
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
  their slots are part of the template."
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
