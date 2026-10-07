(ns example.test-util
  (:require
    [co.multiply.quiescent :as q]
    [example.resource :as resource]
    [example.runtime :as runtime])
  (:import
    (example.signal Inbox)
    (java.util ArrayList)))


(defn harness
  "A runtime for `root-fn` (a fn of no arguments returning the root `Call`),
  driven the way a session pump drives it. Returns `{:step f, :rt rt}`: `step`
  renders a frame from the changes marked since the previous one."
  ([root-fn]
   (harness root-fn {:tab "tab" :uid "user"}))
  ([root-fn ctx]
   (let [inbox (Inbox.)
         rt    (runtime/runtime ctx inbox)]
     {:rt   rt
      :step (fn []
              (let [changed (ArrayList.)]
                (Inbox/.drain inbox changed)
                (runtime/frame! rt (root-fn) changed)))})))


(defn manual-pstate
  "A simulated PState, uniquely named, whose values the test publishes. Returns
  `[pstate !publish]`; `@!publish` is the publish fn of the latest proxy opened."
  []
  (let [!publish (atom nil)]
    [(resource/simulated-pstate (str "$$test-" (random-uuid))
       (fn [_ publish!]
         (reset! !publish publish!)
         (q/task (Thread/sleep Long/MAX_VALUE))))
     !publish]))
