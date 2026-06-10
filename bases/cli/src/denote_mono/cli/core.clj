(ns denote-mono.cli.core
  "Thin CLI entry point composing denote-mono component interfaces.
  Handlers return {:exit CODE :out STRING}; printing and System/exit live
  only in -main."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.cli :as tools-cli]
            [denote-mono.config.interface :as config]
            [denote-mono.editor.interface :as editor]
            [denote-mono.rename.interface :as rename]
            [denote-mono.search.interface :as search]
            [denote-mono.silo.interface :as silo])
  (:gen-class))

(def exit-codes
  {:success 0,
   :failure 1,
   :usage 2,
   :validation 3,
   :collision 4,
   :tool 5,
   :no-match 6})

(def help-text
  "denote - Denote-style note management from the command line

Usage: denote [GLOBAL-OPTIONS] COMMAND [OPTIONS]

Global options:
  --silo NAME      Operate on the named silo
  --root PATH      Operate on an explicit root directory
  --config PATH    Use a specific config file

Commands:
  list             List notes (filters: --match --keyword --signature
                   --title --id; output: --sort --json --edn --print0)
  find [QUERY]     Filter notes by query; prints paths (--open opens them)
  open [QUERY]     Open matching note in $EDITOR
  rename FILE      Rename one note (--title --keyword --signature --id
                   --date --front-matter MODE --dry-run --yes)
  rename-many F... Batch rename (--add-keyword --remove-keyword
                   --replace-keywords KW,KW --from-front-matter
                   --dry-run --yes)
  silo list        List configured silos
  silo path [NAME] Print the path of a silo
  silo doctor      Check that configured silos exist
  help             Show this help text.")

(def ^:private global-options
  [[nil "--silo NAME" "Silo name"] [nil "--root PATH" "Explicit root directory"]
   [nil "--config PATH" "Config file path"] ["-h" "--help" "Show help"]])

(def ^:private list-options
  [[nil "--match REGEX" "Filter by basename regex"]
   [nil "--keyword KW" "Filter by keyword"]
   [nil "--signature SIG" "Filter by exact signature"]
   [nil "--title REGEX" "Filter by title regex"]
   [nil "--id ID" "Filter by identifier"]
   [nil "--sort KEY" "Sort key" :default "identifier"]
   [nil "--json" "JSON-lines output"] [nil "--edn" "EDN output"]
   [nil "--print0" "NUL-delimited output"]])

(def ^:private find-options [[nil "--open" "Open the selection in the editor"]])

(def ^:private rename-options
  [[nil "--title TITLE" "New title; empty string removes"]
   [nil "--keyword KW" "Keyword (repeatable); a single empty removes all"
    :assoc-fn (fn [m k v] (update m k (fnil conj []) v))]
   [nil "--signature SIG" "New signature; empty string removes"]
   [nil "--id ID" "New identifier"]
   [nil "--date DATE" "Date for generated identifier and front matter"]
   [nil "--front-matter MODE" "sync, update-existing, add, or none" :parse-fn
    keyword] [nil "--dry-run" "Print the plan without applying it"]
   [nil "--yes" "Apply without confirmation"]])

(def ^:private rename-many-options
  (conj rename-options
        [nil "--add-keyword KW" "Add a keyword to every file"]
        [nil "--remove-keyword KW" "Remove a keyword from every file"]
        [nil "--replace-keywords KWS" "Replace keywords (comma-separated)"]
        [nil "--from-front-matter" "Take components from front matter"]))

(defn- load-validated-config
  [global-opts env]
  (-> (config/load-config {:path (:config global-opts), :env env})
      config/validate))

(defn- make-context
  [global-opts {:keys [env cwd]}]
  (let [cfg (load-validated-config global-opts env)]
    {:config cfg,
     :silo
     (silo/resolve-silo cfg (select-keys global-opts [:silo :root]) cwd env),
     :env env,
     :cwd cwd}))

(defn- render-notes
  [notes {:keys [json edn print0]}]
  (cond json (str/join "\n" (map #(json/write-str (search/note->wire %)) notes))
        edn (str/join "\n" (map pr-str notes))
        print0 (str/join "\u0000" (map :relative-path notes))
        :else (str/join "\n" (map :relative-path notes))))

(defn- handle-list
  [context args]
  (let [{:keys [options errors]} (tools-cli/parse-opts args list-options)]
    (if errors
      {:exit (exit-codes :usage), :out (str/join "\n" errors)}
      (let [filters (select-keys options
                                 [:match :keyword :signature :title :id])
            notes (-> (search/list-notes context filters {})
                      (search/sort-notes (keyword (:sort options)) {}))]
        {:exit (exit-codes :success), :out (render-notes notes options)}))))

(defn- find-notes
  "Shared body for find/open: query, then print or launch the editor."
  [context query open?]
  (let [notes (search/sort-notes (search/list-notes context {:query query} {})
                                 :identifier
                                 {})]
    (cond (empty? notes) {:exit (exit-codes :no-match),
                          :out "No matching notes"}
          open? (let [{:keys [exit]} (editor/open (map :path notes)
                                                  (assoc context
                                                    :inherit-io? true))]
                  {:exit
                   (if (zero? exit) (exit-codes :success) (exit-codes :tool)),
                   :out ""})
          :else {:exit (exit-codes :success),
                 :out (str/join "\n" (map :relative-path notes))})))

(defn- handle-find
  [context args]
  (let [{:keys [options arguments errors]} (tools-cli/parse-opts args
                                                                 find-options)]
    (if errors
      {:exit (exit-codes :usage), :out (str/join "\n" errors)}
      (find-notes context (first arguments) (:open options)))))

(defn- handle-open [context args] (find-notes context (first args) true))

(defn- options->changes
  "Build the rename changes map from provided CLI options only: omitted
  options keep current components, explicit empty strings remove them."
  [options]
  (cond-> {}
    (contains? options :title) (assoc :title (:title options))
    (contains? options :keyword)
      (assoc :keywords (let [kws (:keyword options)] (if (= [""] kws) [] kws)))
    (contains? options :signature) (assoc :signature (:signature options))
    (contains? options :id) (assoc :identifier (:id options))
    (contains? options :date) (assoc :date (:date options))))

(defn- render-plan
  [{:keys [source destination content-change]}]
  (str source
       " -> "
       destination
       (when-let [actions (seq (:actions content-change))]
         (str "\n"
              (str/join "\n"
                        (for [{:keys [action component old-line new-line]}
                                actions]
                          (str "  " (name action)
                               " " (name component)
                               ": " (or new-line old-line))))))
       (when (:prepend content-change) "\n  prepend front matter")))

(defn- handle-rename
  [context args]
  (let [{:keys [options arguments errors]} (tools-cli/parse-opts args
                                                                 rename-options)
        file (first arguments)]
    (cond
      errors {:exit (exit-codes :usage), :out (str/join "\n" errors)}
      (nil? file) {:exit (exit-codes :usage),
                   :out "Usage: denote rename FILE [OPTIONS]"}
      :else
        (let [plan (rename/plan-rename file
                                       (options->changes options)
                                       context
                                       {:front-matter (:front-matter options)})]
          (if (:dry-run options)
            {:exit (exit-codes :success), :out (render-plan plan)}
            (let [applied (rename/apply-plan (rename/validate-plan plan {}) {})]
              {:exit (exit-codes :success),
               :out (str (:source applied) " -> " (:destination applied))}))))))

(defn- batch-changes-from-options
  [options]
  (cond (:add-keyword options) {:add-keyword (:add-keyword options)}
        (:remove-keyword options) {:remove-keyword (:remove-keyword options)}
        (:replace-keywords options)
          {:replace-keywords (str/split (:replace-keywords options) #",")}
        (:from-front-matter options) {:from-front-matter true}
        :else (options->changes options)))

(defn- handle-rename-many
  [context args]
  (let [{:keys [options arguments errors]}
          (tools-cli/parse-opts args rename-many-options)]
    (cond errors {:exit (exit-codes :usage), :out (str/join "\n" errors)}
          (empty? arguments) {:exit (exit-codes :usage),
                              :out
                              "Usage: denote rename-many FILE... [OPTIONS]"}
          :else
            (let [{:keys [plans errors]}
                    (rename/plan-batch arguments
                                       (batch-changes-from-options options)
                                       context
                                       {:front-matter (:front-matter options)})
                  table (str/join "\n" (map render-plan plans))]
              (cond (seq errors) {:exit (exit-codes :collision),
                                  :out (str/join "\n" (map :message errors))}
                    (:dry-run options) {:exit (exit-codes :success), :out table}
                    (not (:yes options))
                      {:exit (exit-codes :validation),
                       :out (str table
                                 "\nRe-run with --yes to apply, or --dry-run "
                                 "to preview.")}
                    :else (let [{:keys [applied failed pending]}
                                  (rename/apply-batch plans {})]
                            (if failed
                              {:exit (exit-codes :failure),
                               :out (str "Applied " (count applied)
                                         ", failed: " (:error failed)
                                         ", pending " (count pending))}
                              {:exit (exit-codes :success),
                               :out (str "Renamed "
                                         (count applied)
                                         " file(s)")})))))))

(defn- handle-silo
  [global-opts {:keys [env]} args]
  (let [cfg (load-validated-config global-opts env)
        silos (silo/all-silos cfg env)
        [subcommand silo-name] args]
    (case subcommand
      "list" {:exit (exit-codes :success),
              :out (str/join "\n"
                             (map (fn [[silo-key {:keys [path]}]]
                                    (str (name silo-key) "\t" path))
                               (sort-by key silos)))}
      "path" (let [target (or (some-> silo-name
                                      keyword)
                              (:default-silo cfg))]
               (if-let [path (silo/path-for cfg target env)]
                 {:exit (exit-codes :success), :out path}
                 (throw (ex-info (str "Unknown silo: "
                                      (some-> target
                                              name))
                                 {:type :validation, :silos (keys silos)}))))
      "doctor" (let [report (silo/doctor cfg env)
                     problems (remove :ok? report)]
                 (if (seq problems)
                   {:exit (exit-codes :validation),
                    :out (str/join "\n"
                                   (map #(str (name (:name %))
                                              ": " (:reason %)
                                              " " (:path %))
                                     problems))}
                   {:exit (exit-codes :success),
                    :out (str (count report) " silo(s) OK")}))
      {:exit (exit-codes :usage),
       :out "Usage: denote silo list|path [NAME]|doctor"})))

(defn run
  "Dispatch ARGS and return {:exit CODE :out STRING}. HARNESS supplies
  :env (map of environment variables) and :cwd, so tests can inject both."
  ([args]
   (run args
        {:env (into {} (System/getenv)), :cwd (System/getProperty "user.dir")}))
  ([args harness]
   (let [{:keys [options arguments errors]}
           (tools-cli/parse-opts args global-options :in-order true)
         [command & command-args] arguments]
     (try (cond errors {:exit (exit-codes :usage), :out (str/join "\n" errors)}
                (or (nil? command) (:help options) (= "help" command))
                  {:exit (exit-codes :success), :out help-text}
                (= command "silo") (handle-silo options harness command-args)
                (= command "list") (handle-list (make-context options harness)
                                                command-args)
                (= command "find") (handle-find (make-context options harness)
                                                command-args)
                (= command "open") (handle-open (make-context options harness)
                                                command-args)
                (= command "rename")
                  (handle-rename (make-context options harness) command-args)
                (= command "rename-many") (handle-rename-many
                                            (make-context options harness)
                                            command-args)
                :else {:exit (exit-codes :usage),
                       :out (str "Unknown command: " command "\n\n" help-text)})
          (catch clojure.lang.ExceptionInfo e
            {:exit (exit-codes (or (:type (ex-data e)) :failure)),
             :out (str (ex-message e)
                       (when-let [silos (seq (:silos (ex-data e)))]
                         (str "\nConfigured silos: "
                              (str/join ", " (map name silos)))))})))))

(defn -main
  [& args]
  (let [{:keys [exit out]} (run args)]
    (when-not (str/blank? out)
      (if (zero? exit) (println out) (binding [*out* *err*] (println out))))
    (System/exit exit)))
