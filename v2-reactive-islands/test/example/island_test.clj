(ns example.island-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dev.onionpancakes.chassis.core :as h]
    [example.island :as island :refer [island slot]]
    [example.test-util :refer [drive]]
    [missionary.core :as m]))


(defn- tree
  [!title !count]
  (island "parent"
    {:inputs {:title (m/watch !title)}
     :slots  {:child (island "child" {:tag :span :inputs {:n (m/watch !count)}}
                       (fn [{:keys [n]}] ["count " n]))}}
    (fn [{:keys [title]}]
      [:section [:h1 title] (slot :child) [:p "footer"]])))


(deftest emits-the-same-html-as-plain-rendering
  (let [{:keys [sample cancel]} (drive (tree (atom "Hello") (atom 1)))
        frame                   (sample)]
    (is (= (h/html [:div {:id "parent"}
                    [:section [:h1 "Hello"] [:span {:id "child"} "count " 1] [:p "footer"]]])
           (island/html frame)))
    (is (= (count (island/html frame)) (island/frame-chars frame)))
    (cancel)))


(deftest child-change-patches-only-the-child
  (let [!title                  (atom "Hello")
        !count                  (atom 1)
        {:keys [sample cancel]} (drive (tree !title !count))
        f1                      (sample)]
    (testing "a child's change neither re-renders nor resends the parent"
      (swap! !count inc)
      (let [f2 (sample)]
        (is (= ["child"] (map :id (island/patches f1 f2))))
        (is (identical? (:template f1) (:template f2)))
        (is (= {"parent" 1 "child" 2} (island/render-counts f2)))))
    (testing "the parent's own change sends the parent, children included"
      (let [f2 (sample)]
        (reset! !title "Bye")
        (let [f3 (sample)]
          (is (= ["parent"] (map :id (island/patches f2 f3))))
          (is (= {"parent" 2 "child" 2} (island/render-counts f3))))))
    (testing "an equal input value renders nothing and sends nothing"
      (let [f3 (sample)]
        (reset! !count 2)
        (is (identical? f3 (sample)))))
    (testing "an unknown client state (nil) resyncs the root"
      (is (= ["parent"] (map :id (island/patches nil (sample))))))
    (cancel)))


(deftest a-child-with-a-new-id-resends-its-parent
  (let [!which                  (atom :a)
        a                       (island "a" {} (fn [_] "A"))
        b                       (island "b" {} (fn [_] "B"))
        {:keys [sample cancel]} (drive (island "p" {:slots {:x (island/switch-by (m/watch !which) {:a a :b b})}}
                                         (fn [_] [:div (slot :x)])))
        f1                      (sample)]
    (reset! !which :b)
    (is (= ["p"] (map :id (island/patches f1 (sample)))))
    (cancel)))


(deftest a-failing-render-renders-an-error-in-place
  (let [{:keys [sample cancel]} (drive (island "boom" {} (fn [_] (throw (ex-info "kaboom" {})))))]
    (is (re-find #"kaboom" (island/html (sample))))
    (cancel)))
