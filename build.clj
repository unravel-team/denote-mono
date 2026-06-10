(ns build
  "Build tasks for the denote-mono Polylith workspace.

   Targets:
   * test
     - runs the root Clojure test alias
   * uberjar :project PROJECT
     - creates an uberjar for the given project

   Example:
     clojure -T:build uberjar :project denote-cli"
  (:refer-clojure :exclude [test])
  (:require [clojure.java.io :as io]
            [clojure.tools.build.api :as b]
            [clojure.tools.deps :as t]
            [clojure.tools.deps.util.dir :refer [with-dir]]
            [org.corfield.build :as bb]))

(defn- project-name
  [project]
  (cond (string? project) project
        (keyword? project) (name project)
        (symbol? project) (name project)
        :else (str project)))

(defn- get-project-aliases
  []
  (let [edn-fn (juxt :root-edn :project-edn)]
    (-> (t/find-edn-maps)
        (edn-fn)
        (t/merge-edns)
        :aliases)))

(defn- ensure-project-root
  "Given a task name and a project, ensure the project exists."
  [task project]
  (let [project (project-name project)
        project-root (str (System/getProperty "user.dir") "/projects/" project)]
    (when-not (and project
                   (.exists (io/file project-root))
                   (.exists (io/file project-root "deps.edn")))
      (throw (ex-info (str task " task requires a valid :project option")
                      {:project project})))
    project-root))

(defn current-version
  "Return the current version for a project.

   The project's deps.edn must contain an :uberjar alias with
   :major-version and :minor-version. Patch version uses git rev count."
  [{:keys [project]}]
  (let [project (project-name project)
        project-root (ensure-project-root "current-version" project)
        aliases (with-dir (io/file project-root) (get-project-aliases))
        major-version (-> aliases
                          :uberjar
                          :major-version)
        minor-version (-> aliases
                          :uberjar
                          :minor-version)]
    (when-not (and major-version minor-version)
      (throw (ex-info
               (str "The " project
                    " project's deps.edn file must specify :major-version and "
                      ":minor-version in its :uberjar alias")
               {:aliases aliases})))
    (let [cv (format "v%s.%s.%s"
                     major-version
                     minor-version
                     (b/git-count-revs nil))]
      (println (str "The current-version of project " project " is " cv))
      cv)))

(defn uber-opts
  "Build the options map used by uberjar.

   The project's deps.edn must contain an :uberjar alias with:
   - :main
   - :major-version
   - :minor-version
   - optional :java-opts"
  [{:keys [project]}]
  (let [project (project-name project)
        project-root (ensure-project-root "uberjar" project)
        aliases (with-dir (io/file project-root) (get-project-aliases))
        main (-> aliases
                 :uberjar
                 :main)
        java-opts (-> aliases
                      :uberjar
                      :java-opts)
        uber-version (current-version {:project project})
        class-dir "target/classes"
        lib (symbol "denote-mono" project)
        uber-file (str "target/" project "-" uber-version "-standalone.jar")]
    (when-not main
      (throw
        (ex-info
          (str
            "The "
            project
            " project's deps.edn file must specify :main in its :uberjar alias")
          {:aliases aliases})))
    (merge {:project-root project-root,
            :main main,
            :version uber-version,
            :class-dir class-dir,
            :lib lib,
            :uber-file uber-file,
            :compile-opts {:direct-linking true}}
           (when java-opts {:java-opts java-opts}))))

(defn test
  "Run unit tests through the root :dev:test aliases."
  [_]
  (let [{:keys [exit]} (b/process {:command-args ["clojure" "-M:dev:test"]})]
    (when-not (zero? exit) (throw (ex-info "Tests failed" {:exit exit}))))
  nil)

(defn clean
  "Delete generated build artifacts."
  [_]
  (doseq [path ["target" "projects/denote-cli/target"]]
    (b/delete {:path path})))

(defn uberjar
  "Build an uberjar for the specified project."
  [opts]
  (let [{:keys [project-root uber-file], :as build-opts} (uber-opts opts)]
    (b/with-project-root project-root
                         (bb/uber build-opts)
                         (println "Uberjar is built:" uber-file)
                         build-opts)))
