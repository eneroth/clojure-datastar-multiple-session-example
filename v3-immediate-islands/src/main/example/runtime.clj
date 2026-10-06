(ns example.runtime
  "The island runtime of one session: which islands are mounted, what each one
  holds and reads, and how a frame is produced.

  An island renders when it is new, when its arguments changed, or when a value
  it read changed. Its children are then reconciled against what it placed:
  children with unchanged arguments keep their last output, and children it no
  longer places are unmounted, releasing what they held and removing their
  watches. A frame visits only the islands that need rendering and their
  ancestors; every other subtree is reused as it is.

  Single-threaded: only the session's pump calls in. Watches installed on refs
  report changes through `on-change`, from whatever thread changed the ref."
  (:require
    [example.island :as island]))


;; Instance: what the runtime keeps per mounted island.
;;   :parent    the id of the island that placed it; nil for the root
;;   :render    the render fn, :args its last arguments
;;   :template  the last template; :calls its child islands; :frame the last frame
;;   :holds     hold key -> {:value v, :release f}
;;   :reads     [{:ref r, :select f, :seen v}], from the last render
;;   :!state    the atom behind `use-state`
;;   :renders   how many times it rendered


(defn runtime
  "A runtime for the session `ctx` (`{:tab :uid}`). `on-change` is called with a
  ref when a watched ref changes, from the thread that changed it."
  [ctx on-change]
  {:ctx        ctx
   :on-change  on-change
   :watch-key  (Object.)
   :!instances (volatile! {})
   :!root-id   (volatile! nil)
   ;; ref -> #{island id}: which islands read it. The session watches each ref once.
   :!readers   (volatile! {})})


(defn- release-quietly!
  [id {:keys [release]}]
  (when release
    (try
      (release)
      (catch Exception e
        (println "runtime: release failed in" id (ex-message e))))))


(defn- add-reader!
  "Registers island `id` as a reader of `ref`, watching it first if nobody did."
  [{:keys [!readers watch-key on-change]} ref id]
  (when-not (contains? @!readers ref)
    (add-watch ref watch-key (fn [_ _ old new]
                               (when-not (identical? old new)
                                 (on-change ref)))))
  (vswap! !readers update ref (fnil conj #{}) id))


(defn- remove-reader!
  [{:keys [!readers watch-key]} ref id]
  (let [ids (disj (get @!readers ref) id)]
    (if (empty? ids)
      (do (remove-watch ref watch-key)
          (vswap! !readers dissoc ref))
      (vswap! !readers assoc ref ids))))


(defn- read-refs
  [reads]
  (into #{} (map :ref) reads))


(defn- render-island
  "Renders `call` with hooks bound and settles what the render held and read
  against the previous render of `inst`. Returns the updated instance. A render
  that throws renders an error box instead, keeping only what it held and read
  before throwing."
  [rt inst call]
  (let [id        (island/call-id call)
        !holds    (volatile! {})
        !reads    (volatile! [])
        old-holds (:holds inst)
        hooks     {:session (:ctx rt)
                   :state   (:!state inst)
                   :hold!   (fn [k acquire]
                              (let [held (or (get @!holds k) (get old-holds k) (acquire))]
                                (vswap! !holds assoc k held)
                                (:value held)))
                   ;; Watch before reading, so no change between the two is missed.
                   :read!   (fn [ref select]
                              (add-reader! rt ref id)
                              (let [v (select @ref)]
                                (vswap! !reads conj {:ref ref :select select :seen v})
                                v))}
        [template calls] (binding [island/*render* hooks]
                           (try
                             (island/compile-island id (apply (island/call-render call) (island/call-args call)))
                             (catch Exception e
                               (island/compile-island id (island/error-view id (ex-message e))))))
        holds     @!holds
        reads     @!reads]
    (run! (fn [[k held]] (when-not (contains? holds k) (release-quietly! id held))) old-holds)
    (run! #(remove-reader! rt % id) (remove (read-refs reads) (read-refs (:reads inst))))
    (assoc inst
      :render   (island/call-render call)
      :args     (island/call-args call)
      :holds    holds
      :reads    reads
      ;; Equal output keeps the old template, so nothing is patched.
      :template (if (= (:parts template) (:parts (:template inst))) (:template inst) template)
      :calls    calls
      :renders  (inc (:renders inst 0)))))


(defn- unmount!
  [rt {:keys [id holds reads]}]
  (run! #(release-quietly! id (val %)) holds)
  (run! #(remove-reader! rt % id) (read-refs reads)))


(defn- unmount-tree!
  "Unmounts island `id` and the islands it placed."
  [rt id]
  (when-let [inst (get @(:!instances rt) id)]
    (vswap! (:!instances rt) dissoc id)
    (run! (fn [child]
            (let [child-id (island/call-id child)]
              (when (= id (:parent (get @(:!instances rt) child-id)))
                (unmount-tree! rt child-id))))
      (:calls inst))
    (unmount! rt inst)))


(defn- changed?
  "Whether `call` differs from what `inst` last rendered."
  [inst call]
  (or (nil? inst)
      (not (identical? (:render inst) (island/call-render call)))
      (not= (:args inst) (island/call-args call))))


(defn- reconcile
  "The current frame of `call`, placed by island `parent`. Renders it if it is
  new, changed or `dirty`, and visits its children if it rendered or is an
  ancestor of an island that must (`on-path`). Otherwise its last frame stands."
  [rt call parent dirty on-path !visited]
  (let [id   (island/call-id call)
        inst (get @(:!instances rt) id)]
    (cond
      (contains? @!visited id)
      (island/error-frame id "its id appears twice on the page; give it a :key.")

      (and inst (= parent (:parent inst)) (not (changed? inst call)) (not (contains? on-path id)))
      (:frame inst)

      :else
      (let [_         (vswap! !visited conj id)
            old-calls (:calls inst)
            render?   (or (changed? inst call) (contains? dirty id))
            inst      (or inst {:id id :!state (atom {})})
            inst      (-> (if render? (render-island rt inst call) inst)
                        (assoc :parent parent))
            slots     (into {}
                        (map (fn [child] [(island/call-id child) (reconcile rt child id dirty on-path !visited)]))
                        (:calls inst))
            old       (:frame inst)
            frame     (if (and old
                               (identical? (:template old) (:template inst))
                               (every? (fn [[k child]] (identical? child (get (:slots old) k))) slots))
                        old
                        (island/->Frame id (:template inst) slots))]
        (run! (fn [child]
                (let [child-id (island/call-id child)]
                  (when (and (not (contains? slots child-id))
                             (= id (:parent (get @(:!instances rt) child-id))))
                    (unmount-tree! rt child-id))))
          old-calls)
        (vswap! (:!instances rt) assoc id (assoc inst :frame frame))
        frame))))


(defn- stale?
  "Whether a read's selected value differs from what the render saw."
  [{:keys [ref select seen]}]
  (try
    (not= (select @ref) seen)
    (catch Exception _ true)))


(defn- dirty-islands
  "Ids of islands that read one of the `changed` refs and would now see a different value."
  [rt changed]
  (let [instances @(:!instances rt)]
    (into #{}
      (comp (mapcat (fn [ref] (map #(vector ref %) (get @(:!readers rt) ref))))
            (filter (fn [[ref id]]
                      (some #(and (identical? ref (:ref %)) (stale? %)) (:reads (get instances id)))))
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
  frame. `changed` holds the refs reported through `on-change` since the last
  call."
  [rt root changed]
  (let [dirty   (dirty-islands rt changed)
        frame   (reconcile rt root nil dirty (with-ancestors rt dirty) (volatile! #{}))
        root-id (island/call-id root)]
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
