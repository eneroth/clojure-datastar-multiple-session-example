(ns example.live
  (:require
    [dev.onionpancakes.chassis.core :as h]
    [example.mock :as mock]))


(def route-id :route/live)


(defn mount
  "Called once per session with the ring request (path params, user).
  Returns the mounted map: `:data` the render loop reads every tick and
  `:subscriptions` that `unmount` stops."
  [req]
  (println "mount: mounting data and subscriptions")
  {:data
   {:message (mock/get-message (-> req :path-params :n))}
   :subscriptions
   {:ticker (mock/subscription #(rand-int 100) 700)}})


(defn mounted->render
  "From the mounted map to what `render` expects: the message from `:data`
  and the ticker subscription's current value."
  [{:keys [data subscriptions]}]
  {:message (:message data)
   :tick @(-> subscriptions :ticker :value)})


(defn render
  [{:keys [message tick]}]
  (h/html
    [:div {:id "message"}
     [:p message]
     [:div "Subscription: " tick]]))


(defn unmount
  [subscriptions]
  (println "unmount: unmounting subscriptions:" (keys subscriptions))
  (run! (fn [{:keys [stop]}] (stop)) (vals subscriptions)))


(def lifecycle-fns
  {:mounted->render mounted->render
   :render render
   :mount mount
   :unmount unmount})


(comment
  ;; check: tick changes between reads, unmount stops it
  (let [mounted (mount {:path-params {:n "2"}})
        a (mounted->render mounted)
        _ (Thread/sleep 800)
        b (mounted->render mounted)]
    (unmount (-> mounted :subscriptions))
    (assert (not= (:tick a) (:tick b)) "subscription should have advanced")
    (assert (= "Hello from rendering loop 2" (:message a)) "message should carry n")
    [a b (render b)]))
