(ns example.island-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dev.onionpancakes.chassis.core :as h]
    [example.island :as island :refer [defisland use-state use-watch]]
    [example.runtime :as runtime]
    [example.test-util :refer [harness]]))


(def ^:private !title (atom "Hello"))
(def ^:private !data (atom {:count 1 :other 1}))


(defisland child
  []
  [:span "count " (use-watch !data :count)])


(defisland parent
  []
  [:section [:h1 (use-watch !title)] (child) [:p "footer"]])


(defn- reset-data!
  []
  (reset! !title "Hello")
  (reset! !data {:count 1 :other 1}))


(deftest emits-the-same-html-as-plain-rendering
  (reset-data!)
  (let [{:keys [step rt]} (harness parent)
        frame             (step)]
    (is (= (h/html [:section {:id "parent"} [:h1 "Hello"] [:span {:id "child"} "count " 1] [:p "footer"]])
           (island/html frame)))
    (is (= (count (island/html frame)) (island/frame-chars frame)))
    (runtime/dispose! rt)))


(deftest renders-and-patches-only-what-changed
  (reset-data!)
  (let [{:keys [step rt]} (harness parent)
        f1                (step)]
    (testing "a child's change neither re-renders nor resends the parent"
      (swap! !data update :count inc)
      (let [f2 (step)]
        (is (= ["child"] (map :id (island/patches f1 f2))))
        (is (= {"child" 2 "parent" 1} (runtime/render-counts rt)))))
    (testing "the parent's own change resends the parent, without re-rendering the child"
      (let [f2 (step)]
        (reset! !title "Bye")
        (let [f3 (step)]
          (is (= ["parent"] (map :id (island/patches f2 f3))))
          (is (= {"child" 2 "parent" 2} (runtime/render-counts rt))))))
    (testing "a change the selector maps to an equal value renders nothing"
      (let [f3 (step)]
        (swap! !data update :other inc)
        (is (identical? f3 (step)))
        (is (= {"child" 2 "parent" 2} (runtime/render-counts rt)))))
    (testing "an unknown client state (nil) resyncs the root"
      (is (= ["parent"] (map :id (island/patches nil (step))))))
    (runtime/dispose! rt)
    (is (empty? (clojure.lang.ARef/.getWatches !data)) "dispose removes every watch")))


(deftest equal-output-sends-nothing
  (let [!n                (atom 1)
        parity            (island/island-fn "parity" nil (fn [] [:p (if (odd? (use-watch !n)) "odd" "even")]))
        {:keys [step rt]} (harness parity)
        f1                (step)]
    (swap! !n + 2)
    (let [f2 (step)]
      (is (= {"parity" 2} (runtime/render-counts rt)) "it rendered")
      (is (identical? f1 f2) "but nothing is sent"))
    (runtime/dispose! rt)))


(def ^:private !setter (atom nil))


(defisland counter
  []
  (let [[n set-n!] (use-state :n 0)]
    (reset! !setter set-n!)
    [:b n]))


(defisland counter-host
  [show?]
  [:div (when show? (counter))])


(deftest island-local-state
  (let [!show             (atom true)
        {:keys [step rt]} (harness #(counter-host @!show))]
    (step)
    (@!setter 5)
    (let [frame (step)]
      (is (re-find #">5<" (island/html frame)))
      (is (= {"counter" 2 "counter-host" 1} (runtime/render-counts rt)) "only the island re-renders"))
    (testing "state is dropped when the island unmounts"
      (reset! !show false)
      (step)
      (reset! !show true)
      (is (re-find #">0<" (island/html (step)))))
    (runtime/dispose! rt)))


(defisland row
  {:key :id}
  [{:keys [text]}]
  [:li text])


(deftest keyed-islands-render-once-each
  (let [!rows             (atom [{:id 1 :text "a"}])
        rows              (island/island-fn "rows" nil (fn [] [:ul (map row (use-watch !rows))]))
        {:keys [step rt]} (harness rows)]
    (step)
    (swap! !rows conj {:id 2 :text "b"})
    (is (= "<ul id=\"rows\"><li id=\"row-1\">a</li><li id=\"row-2\">b</li></ul>" (island/html (step))))
    (is (= {"row-1" 1 "row-2" 1 "rows" 2} (runtime/render-counts rt)))
    (testing "a removed row unmounts"
      (swap! !rows subvec 1)
      (step)
      (is (= #{"rows" "row-2"} (runtime/mounted rt))))
    (runtime/dispose! rt)))


(def ^:private !released (atom 0))


(defisland leaf
  []
  (island/use-hold :thing (fn [] {:value :thing :release #(swap! !released inc)}))
  [:i (use-watch !data :count)])


(defisland middle
  []
  [:div (leaf)])


(deftest unmounting-reaches-the-whole-subtree
  (reset-data!)
  (reset! !released 0)
  (let [!show             (atom true)
        host              (island/island-fn "host" nil (fn [] [:div (when (use-watch !show) (middle))]))
        {:keys [step rt]} (harness host)]
    (step)
    (is (= #{"host" "middle" "leaf"} (runtime/mounted rt)))
    (reset! !show false)
    (step)
    (is (= #{"host"} (runtime/mounted rt)))
    (is (= 1 @!released) "the grandchild's hold is released")
    (is (empty? (clojure.lang.ARef/.getWatches !data)) "and its watch removed")
    (runtime/dispose! rt)))


(deftest a-child-with-a-new-id-resends-its-parent
  (let [!which            (atom :a)
        a                 (island/island-fn "a" nil (fn [] [:i "A"]))
        b                 (island/island-fn "b" nil (fn [] [:i "B"]))
        p                 (island/island-fn "p" nil (fn [] [:div (if (= :a (use-watch !which)) (a) (b))]))
        {:keys [step rt]} (harness p)
        f1                (step)]
    (reset! !which :b)
    (is (= ["p"] (map :id (island/patches f1 (step)))))
    (is (= #{"p" "b"} (runtime/mounted rt)))
    (runtime/dispose! rt)))


(deftest failures-render-an-error-in-place
  (testing "a render that throws"
    (let [boom              (island/island-fn "boom" nil (fn [] (throw (ex-info "kaboom" {}))))
          {:keys [step rt]} (harness boom)]
      (is (re-find #"kaboom" (island/html (step))))
      (runtime/dispose! rt)))
  (testing "a root element with its own id"
    (let [own               (island/island-fn "own" nil (fn [] [:div {:id "mine"}]))
          {:keys [step rt]} (harness own)]
      (is (re-find #"don't set one" (island/html (step))))
      (runtime/dispose! rt)))
  (testing "two islands with one id"
    (let [twice             (island/island-fn "twice" nil (fn [] [:div (row {:id 1 :text "a"}) (row {:id 1 :text "b"})]))
          {:keys [step rt]} (harness twice)]
      (is (re-find #"give them a :key" (island/html (step))))
      (runtime/dispose! rt))))


(deftest hooks-only-run-in-a-render
  (is (thrown-with-msg? IllegalStateException #"outside an island render" (use-watch !title))))
