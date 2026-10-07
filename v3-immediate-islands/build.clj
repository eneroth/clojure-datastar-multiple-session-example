(ns build
  (:require
    [clojure.tools.build.api :as b]))


(def class-dir "target/classes")
(def basis (delay (b/create-basis {:project "deps.edn"})))


(defn compile-java
  "Compiles the Java sources, which sit next to the Clojure ones in src/main.
  Requires Java 21+."
  [_]
  (b/delete {:path class-dir})
  (b/javac {:src-dirs   ["src/main"]
            :class-dir  class-dir
            :basis      @basis
            :javac-opts ["--release" "21" "-Xlint:all"]}))
