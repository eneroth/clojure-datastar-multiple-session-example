(ns example.runtime
  "The island runtime of one session: which islands are mounted, what each one
  holds and reads, and how a frame is produced.

  An island renders when it is new, when its arguments changed, or when a value
  it read changed. Its children are then reconciled against what it placed:
  children with unchanged arguments keep their last output, and children it no
  longer places are unmounted, releasing what they held and unsubscribing from
  what they read. A frame visits only the islands that need rendering and their
  ancestors; every other subtree is reused as it is.

  Single-threaded: only the session's pump calls in. The session subscribes
  once to each source its islands read (`example.signal`). A change marks the
  subscription in the session's inbox, from whatever thread made it, and the
  pump passes the marked subscriptions to `frame!`."
  (:require
    [example.island :as island])
  (:import
    (example.signal Inbox Sub)))


;; Instance: what the runtime keeps per mounted island, keyed by its id.
;;   :id        its element id; :parent its parent's, nil for the root
;;   :render    the render fn, :args its last arguments
;;   :template  the last template; :frame the last frame
;;   :children  [[child-id call] ...], the child islands it placed, in document order
;;   :holds     hold key -> {:value v, :release f}
;;   :reads     [{:sub s, :select f, :seen v}], from the last render
;;   :!state    the atom behind `use-state`
;;   :renders   how many times it rendered


(defn runtime
  "A runtime for the session `ctx` (`{:tab :uid}`), subscribing through `inbox`."
  [ctx inbox]
  {:ctx        ctx
   :inbox      inbox
   :!instances (volatile! {})
   :!root-id   (volatile! nil)
   ;; source -> Sub, one per source however many islands read it. The Sub's
   ;; `readers` holds which islands do.
   :!subs      (volatile! {})})


(defn- release-quietly!
  [id {:keys [release]}]
  (when release
    (try
      (release)
      (catch Exception e
        (println "runtime: release failed in" id (ex-message e))))))


(defn- add-reader!
  "Registers island `id` as a reader of `source`, subscribing to it first if no
  island did. Returns the subscription."
  [{:keys [!subs inbox]} source id]
  (let [^Sub sub (or (get @!subs source)
                     (let [sub (Inbox/.subscribe inbox source)]
                       (vswap! !subs assoc source sub)
                       sub))]
    (set! (.-readers sub) (conj (or (.-readers sub) #{}) id))
    sub))


(defn- remove-reader!
  "Unregisters island `id` as a reader of `sub`, unsubscribing if it was the last."
  [{:keys [!subs]} ^Sub sub id]
  (let [ids (disj (.-readers sub) id)]
    (if (empty? ids)
      (do (set! (.-readers sub) nil)
          (Sub/.cancel sub)
          (when (identical? sub (get @!subs (.-source sub)))
            (vswap! !subs dissoc (.-source sub))))
      (set! (.-readers sub) ids))))


(defn- read-subs
  [reads]
  (into #{} (map :sub) reads))


(defn- render-island
  "Renders `call` with hooks bound and settles what the render held and read
  against the previous render of `inst`. Returns the updated instance. A render
  that throws renders an error box instead, keeping only what it held and read
  before throwing."
  [rt {:keys [id] :as inst} call]
  (let [!holds    (volatile! {})
        !reads    (volatile! [])
        old-holds (:holds inst)
        hooks     {:session (:ctx rt)
                   :state   (:!state inst)
                   :hold!   (fn [k acquire]
                              (when (contains? @!holds k)
                                (throw (IllegalArgumentException.
                                         (str "it asks for the hold " (pr-str k) " twice in one render."))))
                              (let [held (or (get old-holds k) (acquire))]
                                (vswap! !holds assoc k held)
                                (:value held)))
                   ;; Subscribe before reading, so no change between the two is missed.
                   :read!   (fn [source select]
                              (let [sub (add-reader! rt source id)
                                    v   (select @sub)]
                                (vswap! !reads conj {:sub sub :select select :seen v})
                                v))}
        [template calls] (binding [island/*render* hooks]
                           (try
                             (island/compile-island id (apply (island/call-render call) (island/call-args call)))
                             (catch Exception e
                               (println "runtime: island" id "failed to render:" (str e))
                               (island/compile-island id (island/error-view id (or (ex-message e) (str (class e))))))))
        holds     @!holds
        reads     @!reads]
    (run! (fn [[k held]] (when-not (contains? holds k) (release-quietly! id held))) old-holds)
    (run! #(remove-reader! rt % id) (remove (read-subs reads) (read-subs (:reads inst))))
    (assoc inst
      :render   (island/call-render call)
      :args     (island/call-args call)
      :holds    holds
      :reads    reads
      ;; Equal output keeps the old template, so nothing is patched.
      :template (if (= (:parts template) (:parts (:template inst))) (:template inst) template)
      :children (mapv (fn [call] [(island/child-id id (island/call-slot call)) call]) calls)
      :renders  (inc (:renders inst 0)))))


(defn- unmount!
  [rt {:keys [id holds reads]}]
  (run! #(release-quietly! id (val %)) holds)
  (run! #(remove-reader! rt % id) (read-subs reads)))


(defn- unmount-tree!
  "Unmounts island `id` and the islands it placed."
  [rt id]
  (when-let [inst (get @(:!instances rt) id)]
    (vswap! (:!instances rt) dissoc id)
    (run! #(unmount-tree! rt (first %)) (:children inst))
    (unmount! rt inst)))


(defn- changed?
  "Whether `call` differs from what `inst` last rendered."
  [inst call]
  (or (nil? inst)
      (not (identical? (:render inst) (island/call-render call)))
      (not= (:args inst) (island/call-args call))))


(defn- reconcile
  "The current frame of `call`, the island `id` placed by island `parent`.
  Renders it if it is new, changed or `dirty`, and visits its children if it
  rendered or is an ancestor of an island that must (`on-path`). Otherwise its
  last frame stands."
  [rt id call parent dirty on-path]
  (let [inst (get @(:!instances rt) id)]
    (if (and inst (not (changed? inst call)) (not (contains? on-path id)))
      (:frame inst)
      (let [render? (or (changed? inst call) (contains? dirty id))
            before  (:children inst)
            inst    (or inst {:id id :parent parent :!state (atom {})})
            inst    (if render? (render-island rt inst call) inst)
            slots   (into {}
                      (map (fn [[child-id child]]
                             [(island/call-slot child) (reconcile rt child-id child id dirty on-path)]))
                      (:children inst))
            old     (:frame inst)
            frame   (if (and old
                             (identical? (:template old) (:template inst))
                             (every? (fn [[k child]] (identical? child (get (:slots old) k))) slots))
                      old
                      (island/->Frame id (:template inst) slots))]
        (when (and render? (seq before))
          (let [placed (into #{} (map first) (:children inst))]
            (run! (fn [[child-id]] (when-not (contains? placed child-id) (unmount-tree! rt child-id))) before)))
        (vswap! (:!instances rt) assoc id (assoc inst :frame frame))
        frame))))


(defn- stale?
  "Whether a read's selected value differs from what the render saw."
  [{:keys [sub select seen]}]
  (try
    (not= (select @sub) seen)
    (catch Exception _ true)))


(defn- dirty-islands
  "Ids of islands that read one of the `changed` subscriptions and would now see
  a different value."
  [rt changed]
  (let [instances @(:!instances rt)]
    (into #{}
      (comp (mapcat (fn [^Sub sub] (map #(vector sub %) (.-readers sub))))
            (filter (fn [[sub id]]
                      (some #(and (identical? sub (:sub %)) (stale? %)) (:reads (get instances id)))))
            (map second))
      changed)))


(defn- with-ancestors
  [rt ids]
  (let [instances @(:!instances rt)]
    (loop [todo (seq ids) acc (transient #{})]
      (if-let [[id & more] todo]
        (if (contains? acc id)
          (recur more acc)
          (recur (if-some [parent (:parent (get instances id))] (cons parent more) more)
            (conj! acc id)))
        (persistent! acc)))))


(defn frame!
  "Brings the island tree under `root` (a `Call`) up to date and returns its
  frame. `changed` holds the subscriptions the inbox marked since the last call."
  [rt root changed]
  (let [dirty   (dirty-islands rt changed)
        root-id (island/call-slot root)
        frame   (reconcile rt root-id root nil dirty (with-ancestors rt dirty))]
    (when-not (contains? #{nil root-id} @(:!root-id rt))
      (unmount-tree! rt @(:!root-id rt)))
    (vreset! (:!root-id rt) root-id)
    frame))


(defn dispose!
  "Unmounts every island."
  [rt]
  (run! #(unmount! rt %) (vals @(:!instances rt)))
  (vreset! (:!instances rt) {}))


(defn render-counts
  "Island id -> how many times it rendered, over the mounted islands."
  [rt]
  (into (sorted-map) (map (fn [[id inst]] [id (:renders inst)])) @(:!instances rt)))


(defn mounted
  "Ids of the mounted islands."
  [rt]
  (set (keys @(:!instances rt))))
