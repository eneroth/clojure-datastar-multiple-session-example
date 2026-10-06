(ns example.hooks
  "The two hooks for reading from the database, named after the platform's
  Electric helpers in `co.multiply.app.electric.hooks`:

  - `(<- pstate path)` subscribes to `path` through a proxy shared by every
    session, and streams its updates.
  - `(? f & args)` runs a one-off task, and runs it again only when its
    arguments change.

  Both return `pending` until their first value arrives. Where the Electric
  versions throw `Pending`, these return it as a value."
  (:require
    [co.multiply.quiescent :as q]
    [example.island :as island]
    [example.resource :as resource]))


(def pending
  "What `<-` and `?` return until their first value arrives."
  resource/pending)


(defn <-
  "The latest value at `path` in `pstate`, through a proxy shared with every
  session reading the same path. The island renders again on each new value.
  Given another `pstate` or `path`, the island holds the new proxy and releases
  the old one, which lingers before it closes.

  Options: `:init`, returned instead of `pending` until the first value."
  ([pstate path]
   (<- pstate path nil))
  ([pstate path {:keys [init] :or {init pending}}]
   (let [key [(:name pstate) path]
         v   (island/use-watch
               (island/use-hold [::proxy key]
                 (fn []
                   {:value   (resource/acquire! key #((:open pstate) path %))
                    :release #(resource/release! key)})))]
     (if (= pending v) init v))))


;; A failed `?` task, kept until the island renders and rethrows it.
(defrecord Failed [error])


(defn ?
  "The result of `(apply f args)`, or `pending` while it runs. `f` returns a
  Quiescent task, or anything `q/as-task` accepts. It runs once per distinct
  `f` and `args`, and again only when they change (`=`). A run superseded by
  new arguments, or whose island unmounts, is cancelled.

  If the task fails, `?` throws its exception in the render: the island renders
  an error box unless it catches it.

  `f` is part of the key, so pass a named function rather than a fresh closure.
  It is called on the session's thread, so it must return a task, not block."
  [f & args]
  (let [result (island/use-watch
                 (island/use-hold [::task f args]
                   (fn []
                     (let [!result (atom pending)
                           task    (q/compel (q/as-task (apply f args)))]
                       (q/done task (fn [v e] (reset! !result (if e (->Failed e) v))))
                       {:value   !result
                        :release #(q/cancel task)}))))]
    (if (instance? Failed result)
      (throw (:error result))
      result)))
