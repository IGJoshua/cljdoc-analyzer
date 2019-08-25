(ns cljdoc-analyzer.deps
  (:require [clojure.tools.deps.alpha :as tdeps]
            [version-clj.core :as v]
            [cljdoc-analyzer.pom :as pom]
            [cljdoc-analyzer.util :as util]))

(defn- ensure-recent-ish [deps-map]
  (let [min-versions {'org.clojure/clojure "1.9.0"
                      'org.clojure/clojurescript "1.10.339"
                      'org.clojure/java.classpath "0.2.2"
                      ;; Because codox already depends on this version
                      ;; and tools.deps generally selects newer versions
                      ;; it might be ok to not check for this explicitly
                      ;; This allows newer versions to be used through
                      ;; transitive dependencies. For an example see:
                      ;; iced-nrepl (0.2.5) -> orchard -> tools.namespace
                      ;; 'org.clojure/tools.namespace "0.2.11"
                      'org.clojure/core.async "0.4.474"}
        choose-version (fn choose-version [given-v min-v]
                         (if (pos? (v/version-compare min-v given-v)) min-v given-v))]
    (reduce (fn [dm [proj min-v]]
              (cond-> dm
                (get dm proj) (update-in [proj :mvn/version] choose-version min-v)))
            deps-map
            min-versions)))

(defn- ensure-required-deps [deps-map]
  (merge {'org.clojure/clojure {:mvn/version "1.10.1"}
          'org.clojure/clojurescript {:mvn/version "1.10.520"}
          ;; many ring libraries implicitly depend on this and getting all
          ;; downstream libraries to properly declare it as a "provided"
          ;; dependency would be a major effort. since it's all java it also
          ;; shouldn't affect Clojure-related dependency resolution
          'javax.servlet/javax.servlet-api {:mvn/version "4.0.1"}}
         deps-map))

(def cljdoc-analyzer-metagetta
  {'cljdoc-analyzer/reader {:local/root "metagetta"}})

(def hardcoded-deps
  ;; fixups for specific projects.
  ;; Make sure to always use group-id/artifact-id even if they're the same
  '{clj-time/clj-time {org.clojure/java.jdbc {:mvn/version "0.7.7"}}
    com.taoensso/tufte {com.taoensso/timbre {:mvn/version "4.10.0"}}
    cider/cider-nrepl {boot/core {:mvn/version "2.7.2"}
                       boot/base {:mvn/version "2.7.2"}
                       leiningen {:mvn/version "2.8.1"}}
    io.aviso/pretty {leiningen {:mvn/version "2.8.1"}}})

(defn- extra-deps
  "Some projects require additional depenencies that have either been specified with
  scope 'provided', 'system', 'test', are marked 'optional' or are specified via documentation, e.g. a README.
  Maybe should be able to configure this via their cljdoc.edn configuration
  file but this situation being an edge case this is a sufficient fix for now."
  [pom]
  {:pre [(pom/jsoup? pom)]}
  (->> (pom/dependencies pom)
       ;; compile/runtime scopes will be included by the normal dependency resolution.
       (filter #(or (#{"provided" "system" "test"} (:scope %))
                    (:optional %)))
       ;; The version can be nil when pom's utilize
       ;; dependencyManagement this unsurprisingly breaks tools.deps
       ;; Remains to be seen if this causes any issues
       ;; http://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html#Dependency_Management
       (remove #(nil? (:version %)))
       (remove #(.startsWith (:artifact-id %) "boot-"))
       ;; Ensure that tools.reader version is used as specified by CLJS
       (remove #(and (= (:group-id %) "org.clojure")
                     (= (:artifact-id %) "tools.reader")))
       (map (fn [{:keys [group-id artifact-id version]}]
               [(symbol group-id artifact-id) {:mvn/version version}]))
       (into {})))

(defn clj-cljs-deps
  [pom]
  {:pre [(pom/jsoup? pom)]}
  (->> (pom/dependencies pom)
       (map (fn [{:keys [group-id artifact-id version]}]
              [(symbol group-id artifact-id) {:mvn/version version}]))
       (filter #(-> % first #{'org.clojure/clojure 'org.clojure/clojurescript}))
       (into {})))

(defn- extra-repos
  [pom]
  {:pre [(pom/jsoup? pom)]}
  (->> (pom/repositories pom)
       (map (fn [repo] [(:id repo) (dissoc repo :id)]))
       (into {})))

(defn- deps
  "Create a deps.edn style :deps map for the project specified by the
  Jsoup document `pom`."
  [pom]
  {:pre [(pom/jsoup? pom)]}
  (let [{:keys [group-id artifact-id]} (pom/artifact-info pom)
        project (symbol group-id artifact-id)]
    (-> (extra-deps pom)
        (merge (clj-cljs-deps pom))
        (merge (get hardcoded-deps project))
        (ensure-required-deps)
        (ensure-recent-ish)
        (merge cljdoc-analyzer-metagetta))))

(def ^:private default-repos
  {"central" {:url "https://repo1.maven.org/maven2/"},
   "clojars" {:url "https://repo.clojars.org/"}
   ;; Included to account for https://dev.clojure.org/jira/browse/TDEPS-46
   ;; specifically anything depending on org.immutant/messaging will fail
   ;; this includes compojure-api
   "jboss" {:url "https://repository.jboss.org/nexus/content/groups/public/"}
   ;; included for https://github.com/FundingCircle/jackdaw
   "confluent" {:url "https://packages.confluent.io/maven/"}})

(defn resolved-deps
  "Returns resolved deps for `pom-url`."
  [jar-url pom-url]
  {:pre [(string? jar-url) (string? pom-url)]}
  (let [pom (pom/parse (slurp pom-url))
        project (util/clojars-id (pom/artifact-info pom))]
    (tdeps/resolve-deps {:deps (deps pom),
                         :mvn/repos (merge default-repos
                                           (extra-repos pom))}
                        {:extra-deps {(symbol project) {:local/root jar-url}}
                         :verbose false})))

(defn make-classpath
  "Build a classpath for `resolved-deps`."
  [resolved-deps]
  (tdeps/make-classpath resolved-deps [] nil))

(defn print-tree [resolved-deps]
  (tdeps/print-tree resolved-deps))