(ns denote-mono.cli.core
  "Thin CLI entry point composing denote-mono component interfaces.
  Handlers return {:exit CODE :out STRING}; printing and System/exit live
  only in -main."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.cli :as tools-cli]
            [denote-mono.config.interface :as config]
            [denote-mono.editor.interface :as editor]
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

(defn- make-context
  [global-opts {:keys [env cwd]}]
  (let [cfg (-> (config/load-config {:path (:config global-opts), :env env})
                config/validate)]
    {:config cfg,
     :silo
     (silo/resolve-silo cfg (select-keys global-opts [:silo :root]) cwd env),
     :env env,
     :cwd cwd}))

(defn- note->json-friendly
  [note]
  (-> note
      (update :mtime str)
      (update :silo
              #(some-> %
                       name))))

(defn- render-notes
  [notes {:keys [json edn print0]}]
  (cond json (str/join "\n"
                       (map #(json/write-str (note->json-friendly %)) notes))
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

(defn- query-matches
  [context query]
  (let [notes
          (search/sort-notes (search/list-notes context {} {}) :identifier {})
        query (some-> query
                      str/lower-case)]
    (if (str/blank? query)
      notes
      (filterv #(str/includes? (str/lower-case (:relative-path %)) query)
        notes))))

(defn- handle-find
  [context args]
  (let [{:keys [options arguments errors]} (tools-cli/parse-opts args
                                                                 find-options)]
    (if errors
      {:exit (exit-codes :usage), :out (str/join "\n" errors)}
      (let [notes (query-matches context (first arguments))]
        (cond (empty? notes) {:exit (exit-codes :no-match),
                              :out "No matching notes"}
              (:open options)
                (let [{:keys [exit]} (editor/open (map :path notes)
                                                  (assoc context
                                                    :inherit-io? true))]
                  {:exit
                   (if (zero? exit) (exit-codes :success) (exit-codes :tool)),
                   :out ""})
              :else {:exit (exit-codes :success),
                     :out (str/join "\n" (map :relative-path notes))})))))

(defn- handle-open
  [context args]
  (handle-find context (conj (vec args) "--open")))

(defn- handle-silo
  [global-opts harness args]
  (let [{:keys [env]} harness
        cfg (-> (config/load-config {:path (:config global-opts), :env env})
                config/validate)
        silos (silo/all-silos cfg env)
        [subcommand silo-name] args]
    (case subcommand
      "list" {:exit (exit-codes :success),
              :out (str/join "\n"
                             (map (fn [[silo-key {:keys [path]}]]
                                    (str (name silo-key) "\t" path))
                               (sort-by key silos)))}
      "path" (let [target (if silo-name
                            (get silos (keyword silo-name))
                            (get silos (:default-silo cfg)))]
               (if target
                 {:exit (exit-codes :success), :out (:path target)}
                 {:exit (exit-codes :validation),
                  :out (str "Unknown silo. Configured: "
                            (str/join ", " (map name (keys silos))))}))
      "doctor" (let [problems (for [[silo-key {:keys [path]}] silos
                                    :when (not (.isDirectory (java.io.File.
                                                               ^String path)))]
                                (str (name silo-key) ": missing " path))]
                 (if (seq problems)
                   {:exit (exit-codes :validation),
                    :out (str/join "\n" problems)}
                   {:exit (exit-codes :success),
                    :out (str (count silos) " silo(s) OK")}))
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
