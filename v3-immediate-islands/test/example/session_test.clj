(ns example.session-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [example.action :as action :refer [use-action]]
    [example.hooks :refer [<-]]
    [example.island :as island :refer [defisland]]
    [example.resource :as resource]
    [example.session :as session]
    [example.sse :as sse]
    [example.test-util :refer [manual-pstate]])
  (:import
    (java.util.concurrent CountDownLatch)))


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
          (session/attach! s :conn-1 "load-1")
          (@!publish 1)
          (Thread/sleep 200)
          (is (= [[:conn-1 ["shell/value-view"]]] @!sent)))
        (testing "a reconnect resyncs the whole root, without reopening the resource"
          (let [opened (:opened @resource/!totals)]
            (session/detach! s :conn-1)
            (session/attach! s :conn-2 "load-1")
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


(deftest a-copy-of-the-page-is-told-to-reload
  (let [!sent             (atom [])
        !reloaded         (atom [])
        [pstate !publish] (manual-pstate)]
    (with-redefs [sse/patch!       (fn [conn htmls] (swap! !sent conj [conn (patched-ids htmls)]) true)
                  sse/signals!     (fn [_ _] true)
                  sse/close!       (fn [_] true)
                  sse/reload!      (fn [conn] (swap! !reloaded conj conn) true)
                  session/frame-ms 0]
      (let [s (session/create! (str (random-uuid)) "u" (fn [_] (shell pstate)))]
        (session/render-page! s)
        (session/attach! s :conn-1 "load-1")
        (session/attach! s :copy "load-2")
        (@!publish 1)
        (Thread/sleep 200)
        (is (= [:copy] @!reloaded))
        (is (= [[:conn-1 ["shell/value-view"]]] @!sent) "the first page load keeps the session")
        (testing "the first page load still reconnects"
          (session/detach! s :conn-1)
          (session/attach! s :conn-2 "load-1")
          (Thread/sleep 200)
          (is (= [:conn-2 ["shell"]] (last @!sent)))
          (is (= [:copy] @!reloaded)))
        (session/close! s)
        (is (deref (:closed s) 1000 false))))))


(deftest a-closed-session-turns-events-away
  (let [!closed (atom [])
        page    (promise)]
    (with-redefs [sse/close! (fn [conn] (swap! !closed conj conn) true)]
      (#'session/handle {:closed? true} [:attach :conn-late])
      (#'session/handle {:closed? true} [:ssr page]))
    (is (= [:conn-late] @!closed) "a connection is closed, so its client retries into a new session")
    (is (and (realized? page) (nil? @page)) "a page render gets nil")))


(defisland blank
  []
  [:p "blank"])


(deftest concurrent-obtains-share-one-session
  (let [tab      (str (random-uuid))
        start    (CountDownLatch. 1)
        obtains  (doall (repeatedly 8 #(future (CountDownLatch/.await start) (session/obtain! tab "u" (fn [_] (blank))))))
        _        (CountDownLatch/.countDown start)
        sessions (into #{} (map deref) obtains)]
    (is (= 1 (count sessions)))
    (is (identical? (first sessions) (get @session/!sessions tab)))
    (session/close! (first sessions))
    (is (deref (:closed (first sessions)) 1000 false))))
