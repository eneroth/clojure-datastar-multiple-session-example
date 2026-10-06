(ns example.resource-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [co.multiply.quiescent :as q]
    [example.action :as action :refer [use-action]]
    [example.hooks :refer [<-]]
    [example.island :as island :refer [defisland use-watch]]
    [example.resource :as resource]
    [example.runtime :as runtime]
    [example.test-util :refer [harness manual-pstate]]))


(use-fixtures :each
  (fn [t]
    (with-redefs [resource/linger-ms 200]
      (t))))


(defn- entry
  [pstate path]
  (get (resource/summary @resource/!entries) [(:name pstate) path]))


(defn- reader
  [pstate path]
  (island/island-fn "reader" nil (fn [] [:p (str (<- pstate path))])))


(deftest sessions-share-one-proxy
  (let [[pstate] (manual-pstate)
        opened   (:opened @resource/!totals)
        a        (harness (reader pstate [:x]))
        b        (harness (reader pstate [:x]))]
    ((:step a)) ((:step b))
    (is (= {:state :connecting :subscribers 2} (entry pstate [:x])))
    (is (= (inc opened) (:opened @resource/!totals)) "opened once")
    (runtime/dispose! (:rt a))
    (is (= 1 (:subscribers (entry pstate [:x]))) "still held by b")
    (runtime/dispose! (:rt b))
    (is (= :lingering (:state (entry pstate [:x]))))
    (Thread/sleep 400)
    (is (nil? (entry pstate [:x])) "closed after the linger")))


(deftest publishing-re-renders-the-reader
  (let [[pstate !publish] (manual-pstate)
        {:keys [step rt]} (harness (reader pstate [:x]))]
    (is (re-find #"pending" (island/html (step))))
    (@!publish 42)
    (is (re-find #">42<" (island/html (step))))
    (is (= :live (:state (entry pstate [:x]))))
    (runtime/dispose! rt)))


(deftest init-stands-in-until-the-first-value
  (let [[pstate !publish] (manual-pstate)
        reader            (island/island-fn "reader" nil (fn [] [:p (str (<- pstate [:x] {:init "none yet"}))]))
        {:keys [step rt]} (harness reader)]
    (is (re-find #">none yet<" (island/html (step))))
    (@!publish 1)
    (is (re-find #">1<" (island/html (step))))
    (runtime/dispose! rt)))


(defn- failing-pstate
  "A simulated PState whose proxies publish `:ok`, then fail when the test calls
  `@!kill`. Returns `[pstate !kill]`."
  []
  (let [!kill (atom nil)]
    [(resource/simulated-pstate (str "$$test-" (random-uuid))
       (fn [_ publish!]
         (let [p (promise)]
           (reset! !kill #(deliver p (ex-info "proxy lost" {})))
           (publish! :ok)
           (q/task (throw @p)))))
     !kill]))


(deftest a-failed-proxy-throws-and-the-next-reader-opens-another
  (let [[pstate !kill] (failing-pstate)
        opened         (:opened @resource/!totals)
        a              (harness (reader pstate [:x]))]
    (is (re-find #">:ok<" (island/html ((:step a)))))
    (is (= :live (:state (entry pstate [:x]))) "a value published while opening counts")
    (@!kill)
    (Thread/sleep 100)
    (is (re-find #"Island reader failed to render: </strong>proxy lost" (island/html ((:step a)))))
    (is (nil? (entry pstate [:x])) "the failed proxy is closed")
    (testing "the next reader opens a new proxy, which the failed one's release leaves alone"
      (let [b (harness (reader pstate [:x]))]
        (is (re-find #">:ok<" (island/html ((:step b)))))
        (is (= (+ opened 2) (:opened @resource/!totals)))
        (runtime/dispose! (:rt a))
        (is (= 1 (:subscribers (entry pstate [:x]))))
        (runtime/dispose! (:rt b))))))


(deftest a-new-path-releases-the-old-proxy
  (let [[pstate]          (manual-pstate)
        !path             (atom [:a])
        {:keys [step rt]} (harness #((reader pstate @!path)))]
    (step)
    (reset! !path [:b])
    (step)
    (is (= :lingering (:state (entry pstate [:a]))))
    (is (= 1 (:subscribers (entry pstate [:b]))))
    (runtime/dispose! rt)))


(def ^:private !allowed (atom true))


(defisland panel
  [pstate]
  [:div (str (<- pstate [:x]))
   [:button {:data-on:click (use-action :go (fn [_] nil))} "Go"]])


(defisland denied
  []
  [:p "No access"])


(defisland gate
  [pstate]
  (if (use-watch !allowed)
    (panel pstate)
    (denied)))


(deftest a-gate-releases-what-its-branch-held
  (reset! !allowed true)
  (let [[pstate !publish] (manual-pstate)
        uid               (str (random-uuid))
        tokens            #(count (filter (fn [[_ v]] (= uid (:uid v))) @action/!actions))
        {:keys [step rt]} (harness #(gate pstate) {:tab "tab" :uid uid})]
    (step)
    (is (= 1 (:subscribers (entry pstate [:x]))))
    (is (= 1 (tokens)))
    (testing "revoking unmounts the panel: proxy released, action token revoked"
      (reset! !allowed false)
      (is (re-find #"No access" (island/html (step))))
      (is (= :lingering (:state (entry pstate [:x]))))
      (is (zero? (tokens))))
    (testing "granting again within the linger remounts without reopening"
      (let [opened (:opened @resource/!totals)]
        (reset! !allowed true)
        (step)
        (is (= 1 (:subscribers (entry pstate [:x]))))
        (is (= opened (:opened @resource/!totals)))
        (is (= 1 (tokens)))))
    (testing "the token stays the same across renders, and is revoked on dispose"
      (let [token  #(re-find #"/act/[0-9a-f-]+" (island/html %))
            before (token (step))]
        (@!publish 7)
        (let [frame (step)]
          (is (re-find #">7<" (island/html frame)))
          (is (= before (token frame))))
        (runtime/dispose! rt)
        (is (zero? (tokens)))))))
