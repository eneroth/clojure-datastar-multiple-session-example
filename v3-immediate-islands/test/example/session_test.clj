(ns example.session-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [example.action :as action :refer [use-action]]
    [example.hooks :refer [<-]]
    [example.island :as island :refer [defisland]]
    [example.resource :as resource]
    [example.session :as session]
    [example.sse :as sse]
    [example.test-util :refer [manual-pstate]]))


(defn- patched-ids
  [htmls]
  (mapv #(second (re-find #"^<[a-z]+ id=\"([^\"]+)\"" %)) htmls))


(defisland value-view
  [pstate]
  [:p (str (<- pstate [:x]))
   [:button {:data-on:click (use-action :go (fn [_] nil))} "Go"]])


(defisland shell
  [pstate]
  [:div (value-view pstate)])


(deftest session-lifecycle
  (let [!sent              (atom [])
        uid                (str (random-uuid))
        tokens             #(count (filter (fn [[_ v]] (= uid (:uid v))) @action/!actions))
        [pstate !publish]  (manual-pstate)]
    (with-redefs [sse/patch!         (fn [conn htmls] (swap! !sent conj [conn (patched-ids htmls)]) true)
                  sse/signals!       (fn [_ _] true)
                  sse/close!         (fn [_] true)
                  session/grace-ms   300
                  session/frame-ms   0
                  resource/linger-ms 100]
      (let [s (session/create! (str (random-uuid)) uid (fn [_] (shell pstate)))]
        (testing "the page is rendered from the first frame"
          (is (re-find #"id=\"shell\"" (island/html (session/render-page! s)))))
        (testing "attaching after SSR sends only what changed since"
          (session/attach! s :conn-1)
          (@!publish 1)
          (Thread/sleep 200)
          (is (= [[:conn-1 ["value-view"]]] @!sent)))
        (testing "a reconnect resyncs the whole root, without reopening the resource"
          (let [opened (:opened @resource/!totals)]
            (session/detach! s :conn-1)
            (session/attach! s :conn-2)
            (Thread/sleep 200)
            (is (= [:conn-2 ["shell"]] (last @!sent)))
            (is (= opened (:opened @resource/!totals)))))
        (testing "past the grace period the session closes: tokens revoked, resources released"
          (is (= 1 (tokens)))
          (session/detach! s :conn-2)
          (Thread/sleep 600)
          (is (nil? (get @session/!sessions (:tab s))))
          (is (zero? (tokens)))
          (Thread/sleep 300)
          (is (nil? (get @resource/!entries [(:name pstate) [:x]]))))))))
