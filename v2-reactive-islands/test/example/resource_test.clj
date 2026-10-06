(ns example.resource-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [example.action :as action]
    [example.island :as island :refer [island]]
    [example.resource :as resource]
    [example.test-util :refer [drive manual-resource]]
    [missionary.core :as m]))


(use-fixtures :each
  (fn [t]
    (with-redefs [resource/linger-ms 200]
      (t))))


(defn- entry
  [key]
  (get (resource/summary @resource/!entries) key))


(deftest subscribers-share-one-resource
  (let [key       [:test (random-uuid)]
        [open-fn] (manual-resource)
        opened    (:opened @resource/!totals)
        a         (drive (resource/subscribe key open-fn))
        b         (drive (resource/subscribe key open-fn))]
    ((:sample a)) ((:sample b))
    (is (= {:state :connecting :subscribers 2} (entry key)))
    (is (= (inc opened) (:opened @resource/!totals)) "opened once")
    ((:cancel a))
    (is (= 1 (:subscribers (entry key))) "still held by b")
    ((:cancel b))
    (is (= :lingering (:state (entry key))))
    (Thread/sleep 400)
    (is (nil? (entry key)) "closed after the linger")))


(deftest resubscribing-within-the-linger-reuses
  (let [key                [:test (random-uuid)]
        [open-fn !publish] (manual-resource)
        a                  (drive (resource/subscribe key open-fn))]
    ((:sample a))
    (@!publish 42)
    (is (= 42 ((:sample a))))
    (is (= :live (:state (entry key))))
    ((:cancel a))
    (let [opened (:opened @resource/!totals)
          b      (drive (resource/subscribe key open-fn))]
      (is (= 42 ((:sample b))) "the current value, without reopening")
      (is (= opened (:opened @resource/!totals)))
      (Thread/sleep 400)
      (is (= :live (:state (entry key))) "the cancelled linger did not close it")
      ((:cancel b)))))


(deftest a-gate-releases-what-its-branch-held
  (let [key                     [:test (random-uuid)]
        [open-fn]               (manual-resource)
        !allowed                (atom true)
        uid                     (str (random-uuid))
        tokens                  #(count (filter (fn [[_ v]] (= uid (:uid v))) @action/!actions))
        panel                   (island "panel"
                                  {:inputs {:v   (resource/subscribe key open-fn)
                                            :act (action/action uid (fn [_] nil))}}
                                  (fn [{:keys [act]}] [:button {:data-on:click act} "Go"]))
        denied                  (island "panel" {} (fn [_] "No access"))
        {:keys [sample cancel]} (drive (island/gate (m/watch !allowed) panel denied))]
    (sample)
    (is (= 1 (:subscribers (entry key))))
    (is (= 1 (tokens)))
    (testing "revoking unmounts: subscription released, action token revoked"
      (reset! !allowed false)
      (is (re-find #"No access" (island/html (sample))))
      (is (= :lingering (:state (entry key))))
      (is (zero? (tokens))))
    (testing "granting again within the linger remounts without reopening"
      (reset! !allowed true)
      (sample)
      (is (= 1 (:subscribers (entry key))))
      (is (= 1 (tokens))))
    (cancel)
    (is (zero? (tokens)))))
