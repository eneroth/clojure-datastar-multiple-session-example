(ns example.sse
  "The wire: thin wrappers over the Datastar SDK."
  (:require
    [charred.api :as json]
    [starfederation.datastar.clojure.api :as d*]))


(defn patch!
  "Morphs each HTML string in `htmls` into the element with the same id, all in
  one SSE event. Returns false if the connection is closed."
  [sse htmls]
  (d*/patch-elements-seq! sse htmls))


(defn signals!
  "Merges map `m` into the client's signals. Returns false if the connection is closed."
  [sse m]
  (d*/patch-signals! sse (json/write-json-str m)))


(defn close!
  [sse]
  (d*/close-sse! sse))


(defn reload!
  "Reloads the page on the other end of `sse`, then closes it."
  [sse]
  (d*/execute-script! sse "location.reload()")
  (close! sse))
