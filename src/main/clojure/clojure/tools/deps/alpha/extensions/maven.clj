;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.tools.deps.alpha.extensions.maven
  (:require
    [clojure.java.io :as jio]
    [clojure.string :as str]
    [clojure.tools.deps.alpha.extensions :as ext]
    [clojure.tools.deps.alpha.util.maven :as maven])
  (:import
    ;; maven-resolver-api
    [org.eclipse.aether RepositorySystem]
    [org.eclipse.aether.resolution ArtifactRequest ArtifactDescriptorRequest]

    ;; maven-resolver-util
    [org.eclipse.aether.util.version GenericVersionScheme]
    ))

(set! *warn-on-reflection* true)

;; Main extension points for using Maven deps

(defmethod ext/dep-id :mvn
  [lib {:keys [mvn/version classifier] :as coord} config]
  {:lib lib
   :version version
   :classifier classifier})

(defmethod ext/manifest-type :mvn
  [lib coord config]
  {:deps/manifest :mvn})

(defonce ^:private version-scheme (GenericVersionScheme.))

(defn- parse-version [{version :mvn/version :as coord}]
  (.parseVersion ^GenericVersionScheme version-scheme ^String version))

(defmethod ext/compare-versions [:mvn :mvn]
  [coord-x coord-y config]
  (apply compare (map parse-version [coord-x coord-y])))

(defmethod ext/coord-deps :mvn
  [lib coord _manifest {:keys [mvn/repos mvn/local-repo]}]
  (let [local-repo (or local-repo maven/default-local-repo)
        system ^RepositorySystem @maven/the-system
        session (maven/make-session system local-repo)
        artifact (maven/coord->artifact lib coord)
        req (ArtifactDescriptorRequest. artifact (mapv maven/remote-repo repos) nil)
        result (.readArtifactDescriptor system session req)]
    (into []
      (comp
        (map maven/dep->data)
        (filter #(= (:scope (second %)) "compile"))
        (remove (comp :optional second))
        (map #(update-in % [1] dissoc :scope :optional)))
      (.getDependencies result))))

(defmethod ext/coord-paths :mvn
  [lib coord _manifest {:keys [mvn/repos mvn/local-repo]}]
  (let [local-repo (or local-repo maven/default-local-repo)
        system ^RepositorySystem @maven/the-system
        session (maven/make-session system local-repo)
        artifact (maven/coord->artifact lib coord)
        req (ArtifactRequest. artifact (mapv maven/remote-repo repos) nil)
        result (.resolveArtifact system session req)
        exceptions (.getExceptions result)]
    (cond
      (.isResolved result) [(.. result getArtifact getFile getAbsolutePath)]
      (.isMissing result) (throw (Exception. (str "Unable to download: [" lib (pr-str (:mvn/version coord)) "]")))
      :else (throw (first (.getExceptions result))))))

(comment
  ;; given a dep, find the child deps
  (ext/coord-deps 'org.clojure/clojure {:mvn/version "1.9.0-alpha17"} :mvn {:mvn/repos maven/standard-repos})

  ;; give a dep, download just that dep (not transitive - that's handled by the core algorithm)
  (ext/coord-paths 'org.clojure/clojure {:mvn/version "1.9.0-alpha17"} :mvn {:mvn/repos maven/standard-repos})

  (parse-version {:mvn/version "1.1.0"})

  (ext/compare-versions {:mvn/version "1.1.0-alpha10"} {:mvn/version "1.1.0-beta1"})
  )
