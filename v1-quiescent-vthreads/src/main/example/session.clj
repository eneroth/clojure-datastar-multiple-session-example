(ns example.session
  (:require
    [example.live :as live]))


(comment
  ;; Session shape
  {:sse-connection ;; one SSE-connection is one session
   {;; Lifecycle fns (in running order)
    :mount           (fn [req])           ;; side-effecting fn: takes request, creates session with data and mounted subscriptions
    :mounted->render (fn [data])          ;; pure fn:           takes data, return transformed data 
    :render          (fn [data])          ;; pure fn:           takes data, returns HTML
    :unmount         (fn [subscriptions]) ;; side-effecting fn: takes SSE connection, gracefully removes session including subscriptions

    :mounted ;; map: produced by `(mount req)` on first load
    {:data {}          ;; data from first load
     :subscriptions {} ;; map of named subscriptions
     }}}
  #__)


(def route->lifecycle-fns
  {live/route-id live/lifecycle-fns})


(defonce !state
  (atom {}))


(defn render-session
  [{:keys [render mounted->render mounted]}]
  (-> mounted mounted->render render))


(defn update-session!
  [sse req route-id]
  (swap! !state assoc sse
         (let [{:keys [mount] :as lifecycle-fns} (get route->lifecycle-fns route-id)]
           (assoc lifecycle-fns
                  :mounted (mount req)))))


(defn cleanup-sessions!
  "Unmounts each session's subscriptions, then drops them all in one swap.
  Does not close `sse`: the SDK closes it (on-exception -> true) and calls
  on-close -> here, so closing again would re-enter."
  [sses]
  (when (seq sses)
    (println "Unmounting" (count sses) "session(s)")
    (let [state @!state]
      (doseq [sse sses
              :let [{:keys [unmount mounted]} (state sse)]
              :when unmount]
        (unmount (-> mounted :subscriptions))))
    (apply swap! !state dissoc sses)))


(comment
  @!state
  (reset! !state {}))
