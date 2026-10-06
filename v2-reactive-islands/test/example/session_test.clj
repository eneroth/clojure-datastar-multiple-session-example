(ns example.session-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [example.island :as island :refer [island slot]]
    [example.resource :as resource]
    [example.session :as session]
    [example.sse :as sse]
    [example.test-util :refer [manual-resource]]))


(defn- patched-ids
  [htmls]
  (mapv #(second (re-find #"^<[a-z]+ id=\"([^\"]+)\"" %)) htmls))


(deftest session-lifecycle
  (let [!sent              (atom [])
        key                [:test (random-uuid)]
        [open-fn !publish] (manual-resource)
        root               (fn [_]
                             (island "app" {:slots {:v (island "v" {:inputs {:v (resource/subscribe key open-fn)}}
                                                         (fn [{:keys [v]}] (str v)))}}
                               (fn [_] [:div (slot :v)])))]
    (with-redefs [sse/patch!         (fn [conn htmls] (swap! !sent conj [conn (patched-ids htmls)]) true)
                  sse/signals!       (fn [_ _] true)
                  sse/close!         (fn [_] true)
                  session/grace-ms   300
                  session/frame-ms   0
                  resource/linger-ms 100]
      (let [s (session/create! (str (random-uuid)) "user" root)]
        (testing "the page is rendered from the first frame"
          (is (re-find #"id=\"app\"" (island/html (session/render-page! s)))))
        (testing "attaching after SSR sends only what changed since"
          (session/attach! s :conn-1)
          (@!publish 1)
          (Thread/sleep 200)
          (is (= [[:conn-1 ["v"]]] @!sent)))
        (testing "a reconnect resyncs the whole root, without reopening the resource"
          (let [opened (:opened @resource/!totals)]
            (session/detach! s :conn-1)
            (session/attach! s :conn-2)
            (Thread/sleep 200)
            (is (= [:conn-2 ["app"]] (last @!sent)))
            (is (= opened (:opened @resource/!totals)))))
        (testing "past the grace period the session closes and its resources follow"
          (session/detach! s :conn-2)
          (Thread/sleep 600)
          (is (nil? (get @session/!sessions (:tab s))))
          (Thread/sleep 300)
          (is (nil? (get @resource/!entries key))))))))


(deftest a-static-root-keeps-its-session
  (with-redefs [sse/patch!   (fn [_ _] true)
                sse/signals! (fn [_ _] true)
                sse/close!   (fn [_] true)]
    (let [tab (str (random-uuid))
          s   (session/create! tab "user" (fn [_] (island "app" {} (fn [_] "static"))))]
      (is (= "<div id=\"app\">static</div>" (island/html (session/render-page! s))))
      (session/attach! s :conn)
      (Thread/sleep 200)
      (is (some? (get @session/!sessions tab)) "a terminated root is not a dead session")
      (session/close! s)
      (Thread/sleep 200)
      (is (nil? (get @session/!sessions tab))))))
