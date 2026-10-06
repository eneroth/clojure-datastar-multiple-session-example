(ns example.hooks-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [co.multiply.quiescent :as q]
    [example.hooks :refer [?]]
    [example.island :as island :refer [use-watch]]
    [example.runtime :as runtime]
    [example.test-util :refer [harness]]))


(def ^:private !calls (atom []))
(def ^:private !cancelled (atom []))


(defn- slow-times-ten
  [x]
  (swap! !calls conj x)
  (-> (q/task
        (Thread/sleep 300)
        (* 10 x))
    (q/finally (fn [_ _ cancelled]
                 (when cancelled (swap! !cancelled conj x))))))


(defn- failing
  []
  (q/task (throw (ex-info "nope" {}))))


(defn- reset-calls!
  []
  (reset! !calls [])
  (reset! !cancelled []))


(deftest runs-once-per-distinct-arguments
  (reset-calls!)
  (let [!x                (atom 1)
        !other            (atom 0)
        island            (island/island-fn "q" nil
                            (fn [] [:p (str (? slow-times-ten (use-watch !x))) " / " (use-watch !other)]))
        {:keys [step rt]} (harness island)]
    (is (re-find #"pending" (island/html (step))))
    (Thread/sleep 500)
    (is (re-find #">10 / 0<" (island/html (step))))
    (testing "a render for another reason doesn't run it again"
      (swap! !other inc)
      (is (re-find #">10 / 1<" (island/html (step))))
      (is (= [1] @!calls)))
    (testing "new arguments run it again"
      (reset! !x 2)
      (step)
      (Thread/sleep 500)
      (is (re-find #">20 / 1<" (island/html (step))))
      (is (= [1 2] @!calls)))
    (runtime/dispose! rt)))


(deftest superseded-and-unmounted-runs-are-cancelled
  (reset-calls!)
  (let [!x                (atom 1)
        !show             (atom true)
        child             (island/island-fn "child" nil (fn [x] [:p (str (? slow-times-ten x))]))
        host              (island/island-fn "host" nil
                            (fn [] [:div (when (use-watch !show) (child (use-watch !x)))]))
        {:keys [step rt]} (harness host)]
    (step)
    (reset! !x 2)
    (step)
    (Thread/sleep 100)
    (is (= [1] @!cancelled) "the run for 1 is superseded while in flight")
    (reset! !show false)
    (step)
    (Thread/sleep 100)
    (is (= [1 2] @!cancelled) "the run for 2 is cancelled when its island unmounts")
    (runtime/dispose! rt)))


(deftest failures-throw-in-the-render
  (let [plain             (island/island-fn "plain" nil (fn [] [:p (str (? failing))]))
        caught            (island/island-fn "caught" nil
                            (fn [] [:p (try (str (? failing))
                                            (catch Exception e (str "caught " (ex-message e))))]))
        both              (island/island-fn "both" nil (fn [] [:div (plain) (caught)]))
        {:keys [step rt]} (harness both)]
    (step)
    (Thread/sleep 200)
    (let [html (island/html (step))]
      (is (re-find #"Island both/plain failed to render: </strong>nope" html))
      (is (re-find #">caught nope<" html)))
    (runtime/dispose! rt)))
