(ns denote-mono.cli.core
  "Thin CLI entry point composing denote-mono component interfaces.
  Handlers return {:exit CODE :out STRING}; printing and System/exit live
  only in -main."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.cli :as tools-cli]
            [denote-mono.config.interface :as config]
            [denote-mono.editor.interface :as editor]
            [denote-mono.filename.interface :as filename]
            [denote-mono.note.interface :as note]
            [denote-mono.process.interface :as process]
            [denote-mono.rename.interface :as rename]
            [denote-mono.search.interface :as search]
            [denote-mono.sequence.interface :as sequence]
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
  find [QUERY]     Filter notes by query; prints paths (--open opens them,
                   --fzf selects interactively)
  open [QUERY]     Open matching note in $EDITOR (fzf narrows on a TTY)
  grep QUERY       Search note contents (rg-accelerated when available)
  backlinks ID|F   Notes linking to the given note
  links FILE_OR_ID Outgoing denote: links of a note
  rename FILE      Rename one note (--title --keyword --signature --id
                   --date --front-matter MODE --break-links --dry-run --yes)
  rename-many F... Batch rename (--add-keyword --remove-keyword
                   --replace-keywords KW,KW --from-front-matter
                   --dry-run --yes)
  new              Create a note (--title --keyword --signature --id
                   --date --type --subdir --dry-run --reuse-empty)
  seq validate SEQ [--scheme S]
  seq next parent|child SEQ|sibling SEQ
  seq new parent|child SEQ|sibling SEQ [new options]
  seq list [--prefix SEQ] [--depth N]
  seq tree [--prefix SEQ] [--depth N]
  seq convert FILE... --to SCHEME [--dry-run --yes]
  seq reparent FILE TARGET-SEQ [--recursive --dry-run --yes]
  seq as-parent FILE [--dry-run]
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

(def ^:private find-options
  [[nil "--open" "Open the selection in the editor"]
   [nil "--fzf" "Select interactively with fzf"]])

(def ^:private rename-options
  [[nil "--title TITLE" "New title; empty string removes"]
   [nil "--keyword KW" "Keyword (repeatable); a single empty removes all"
    :assoc-fn (fn [m k v] (update m k (fnil conj []) v))]
   [nil "--signature SIG" "New signature; empty string removes"]
   [nil "--id ID" "New identifier"]
   [nil "--date DATE" "Date for generated identifier and front matter"]
   [nil "--front-matter MODE" "sync, update-existing, add, or none" :parse-fn
    keyword] [nil "--dry-run" "Print the plan without applying it"]
   [nil "--break-links" "Allow identifier changes that break backlinks"]
   [nil "--yes" "Apply without confirmation"]])

(def ^:private rename-many-options
  (conj rename-options
        [nil "--add-keyword KW" "Add a keyword to every file"]
        [nil "--remove-keyword KW" "Remove a keyword from every file"]
        [nil "--replace-keywords KWS" "Replace keywords (comma-separated)"]
        [nil "--from-front-matter" "Take components from front matter"]))

(def ^:private new-options
  [[nil "--title TITLE" "Note title"]
   [nil "--keyword KW" "Keyword (repeatable)" :assoc-fn
    (fn [m k v] (update m k (fnil conj []) v))]
   [nil "--signature SIG" "Signature"] [nil "--id ID" "Explicit identifier"]
   [nil "--date DATE" "Creation date, e.g. \"2024-12-09 10:55:50\""]
   [nil "--type TYPE" "org, markdown-yaml, markdown-toml, or text" :parse-fn
    keyword] [nil "--subdir PATH" "Subdirectory inside the silo"]
   [nil "--dry-run" "Print the planned path without creating"]
   [nil "--reuse-empty" "Reuse an existing empty file"]])

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

(defn- interactive-tty?
  "True when this process is attached to a terminal the user can interact
  with (fzf needs one)."
  []
  (some? (System/console)))

(defn- fzf-select
  "Narrow NOTES with the configured fzf selector. Returns the selection,
  nil when the user cancelled, or NOTES unchanged when the selector is not
  available."
  [context notes]
  (let [fzf-argv (get-in context [:config :tools :fzf] ["fzf"])]
    (if (process/available? (first fzf-argv))
      (when-let [chosen (process/select (map :relative-path notes) fzf-argv)]
        (filterv (comp (set chosen) :relative-path) notes))
      notes)))

(defn- find-notes
  "Shared body for find/open: query, narrow with fzf when requested (or on
  an interactive terminal), then print or launch the editor."
  [context query {:keys [open? fzf?]}]
  (let [notes (search/sort-notes (search/list-notes context {:query query} {})
                                 :identifier
                                 {})
        notes (if (and (seq notes) (or fzf? (and open? (interactive-tty?))))
                (fzf-select context notes)
                notes)]
    (cond
      (nil? notes) {:exit (exit-codes :no-match), :out "Selection cancelled"}
      (empty? notes) {:exit (exit-codes :no-match), :out "No matching notes"}
      open? (let [{:keys [exit]} (editor/open (map :path notes)
                                              (assoc context
                                                :inherit-io? true))]
              {:exit (if (zero? exit) (exit-codes :success) (exit-codes :tool)),
               :out ""})
      :else {:exit (exit-codes :success),
             :out (str/join "\n" (map :relative-path notes))})))

(defn- handle-find
  [context args]
  (let [{:keys [options arguments errors]} (tools-cli/parse-opts args
                                                                 find-options)]
    (if errors
      {:exit (exit-codes :usage), :out (str/join "\n" errors)}
      (find-notes context
                  (first arguments)
                  {:open? (:open options), :fzf? (:fzf options)}))))

(defn- handle-open
  [context args]
  (let [{:keys [options arguments errors]} (tools-cli/parse-opts args
                                                                 find-options)]
    (if errors
      {:exit (exit-codes :usage), :out (str/join "\n" errors)}
      (find-notes context
                  (first arguments)
                  {:open? true, :fzf? (:fzf options)}))))

(defn- resolve-note-path
  "Resolve FILE-OR-ID to a note path: an identifier looks the note up in
  the silo, anything else is treated as a path."
  [context file-or-id]
  (if (filename/date-identifier? file-or-id)
    (or (:path (first (search/list-notes context {:id file-or-id} {})))
        (throw (ex-info (str "No note with identifier " file-or-id)
                        {:type :no-match})))
    file-or-id))

(defn- handle-grep
  [context args]
  (if-let [query (first args)]
    (let [matches (search/grep context query {})]
      (if (seq matches)
        {:exit (exit-codes :success),
         :out (str/join "\n"
                        (map #(str (:relative-path %)
                                   ":" (:line-number %)
                                   ":" (:line %))
                          matches))}
        {:exit (exit-codes :no-match), :out "No matches"}))
    {:exit (exit-codes :usage), :out "Usage: denote grep QUERY"}))

(defn- handle-backlinks
  [context args]
  (if-let [file-or-id (first args)]
    (let [id (if (filename/date-identifier? file-or-id)
               file-or-id
               (or (filename/extract file-or-id :identifier)
                   (throw (ex-info (str file-or-id " has no identifier")
                                   {:type :validation}))))
          notes (search/backlinks context id {})]
      (if (seq notes)
        {:exit (exit-codes :success),
         :out (str/join "\n" (map :relative-path notes))}
        {:exit (exit-codes :no-match), :out "No backlinks"}))
    {:exit (exit-codes :usage), :out "Usage: denote backlinks FILE_OR_ID"}))

(defn- handle-links
  [context args]
  (if-let [file-or-id (first args)]
    (let [path (resolve-note-path context file-or-id)
          {:keys [notes identifiers]} (search/links context path {})]
      (if (seq identifiers)
        {:exit (exit-codes :success),
         :out (str/join "\n" (map :relative-path notes))}
        {:exit (exit-codes :no-match), :out "No links"}))
    {:exit (exit-codes :usage), :out "Usage: denote links FILE_OR_ID"}))

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

(defn- apply-batch-and-report
  "Apply rename PLANS and shape the outcome as an exit/out pair."
  [plans]
  (let [{:keys [applied failed pending]} (rename/apply-batch plans {})]
    (if failed
      {:exit (exit-codes :failure),
       :out (str "Applied " (count applied)
                 ", failed: " (:error failed)
                 ", pending " (count pending))}
      {:exit (exit-codes :success),
       :out (str "Renamed " (count applied) " file(s)")})))

(defn- guard-broken-links!
  "Refuse identifier changes that would break existing backlinks, unless
  --break-links was passed. Throws ex-info {:type :validation} naming the
  affected notes."
  [context plans options]
  (when-not (:break-links options)
    (doseq [{:keys [source old new]} plans
            :let [old-id (:identifier old)]
            :when (and old-id (not= old-id (:identifier new)))]
      (when-let [linking (seq (search/backlinks context old-id {}))]
        (throw (ex-info (str source
                             " has " (count linking)
                             " backlink(s) to " old-id
                             "; pass --break-links to rename anyway:\n"
                               (str/join "\n" (map :relative-path linking)))
                        {:type :validation, :identifier old-id}))))))

(defn- handle-rename
  [context args]
  (let [{:keys [options arguments errors]} (tools-cli/parse-opts args
                                                                 rename-options)
        file (first arguments)]
    (cond errors {:exit (exit-codes :usage), :out (str/join "\n" errors)}
          (nil? file) {:exit (exit-codes :usage),
                       :out "Usage: denote rename FILE [OPTIONS]"}
          :else (let [plan (rename/plan-rename file
                                               (options->changes options)
                                               context
                                               {:front-matter (:front-matter
                                                                options)})]
                  (if (:dry-run options)
                    {:exit (exit-codes :success), :out (render-plan plan)}
                    (do (guard-broken-links! context [plan] options)
                        (let [applied (rename/apply-plan
                                        (rename/validate-plan plan {})
                                        {})]
                          {:exit (exit-codes :success),
                           :out (str (:source applied)
                                     " -> "
                                     (:destination applied))})))))))

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
                    :else (do (guard-broken-links! context plans options)
                              (apply-batch-and-report plans)))))))

(defn- new-changes-from-options
  [options]
  (cond-> {}
    (:title options) (assoc :title (:title options))
    (:keyword options) (assoc :keywords (:keyword options))
    (:signature options) (assoc :signature (:signature options))
    (:id options) (assoc :identifier (:id options))
    (:date options) (assoc :date (:date options))
    (:type options) (assoc :type (:type options))
    (:subdir options) (assoc :subdir (:subdir options))))

(defn- handle-new
  [context args]
  (let [{:keys [options errors]} (tools-cli/parse-opts args new-options)]
    (if errors
      {:exit (exit-codes :usage), :out (str/join "\n" errors)}
      (let [plan (note/plan-new (new-changes-from-options options) context {})]
        (if (:dry-run options)
          {:exit (exit-codes :success), :out (:path plan)}
          (let [created (note/create plan
                                     {:reuse-empty? (:reuse-empty options)})]
            {:exit (exit-codes :success), :out (:path created)}))))))

(defn- silo-sequences
  "All valid sequences among the silo's notes, with their notes.
  Returns {:sequences [...] :by-sequence {sequence note-record}}."
  [context]
  (let [pairs (keep (fn [note]
                      (when-let [s (sequence/file-sequence (:path note))]
                        [s note]))
                    (search/list-notes context {} {}))]
    {:sequences (mapv first pairs), :by-sequence (into {} pairs)}))

(defn- scheme-from
  [context options]
  (or (:scheme options) (get-in context [:config :sequence :scheme]) :numeric))

(defn- next-for-relation
  "Resolve a seq RELATION (\"parent\", \"child\", \"sibling\") to the next
  sequence, or nil for an unknown relation."
  [sequences relation target scheme]
  (case relation
    "parent" (sequence/next-parent sequences scheme)
    "child" (sequence/next-child sequences target scheme)
    "sibling" (sequence/next-sibling sequences target scheme)
    nil))

(defn- apply-or-confirm
  "Shared --dry-run/--yes gate for seq mutations over rename PLANS."
  [plans options]
  (let [table (str/join "\n" (map render-plan plans))]
    (cond (:dry-run options) {:exit (exit-codes :success), :out table}
          (not (:yes options)) {:exit (exit-codes :validation),
                                :out (str table
                                          "\nRe-run with --yes to apply.")}
          :else (do (doseq [plan plans] (rename/validate-plan plan {}))
                    (apply-batch-and-report plans)))))

(def ^:private seq-options
  (conj new-options
        [nil "--scheme SCHEME" "numeric, alphanumeric, alphanumeric-delimited"
         :parse-fn keyword]
        [nil "--to SCHEME" "Target scheme for convert" :parse-fn keyword]
        [nil "--prefix SEQ" "Restrict to sequences under this prefix"]
        [nil "--depth N" "Restrict to sequences up to this depth" :parse-fn
         parse-long]
        [nil "--recursive" "Also reparent descendants"]
        [nil "--yes" "Apply without confirmation"]))

(defn- selected-sequences
  "Filter SEQUENCES by the --prefix and --depth seq options."
  [sequences options scheme]
  (cond->> sequences
    (:prefix options)
      (#(sequence/sequences-with-prefix % (:prefix options) scheme))
    (:depth options) (filter #(<= (sequence/depth %) (:depth options)))))

(defn- handle-seq
  [context args]
  (let [{:keys [options arguments errors]} (tools-cli/parse-opts args
                                                                 seq-options)
        [subcommand & rest-args] arguments
        scheme (scheme-from context options)]
    (if errors
      {:exit (exit-codes :usage), :out (str/join "\n" errors)}
      (case subcommand
        "validate"
          (let [s (first rest-args)
                valid? (if (:scheme options)
                         (sequence/valid-for-scheme? (:scheme options) s)
                         (sequence/valid? s))]
            (if valid?
              {:exit (exit-codes :success), :out (str s " is valid")}
              {:exit (exit-codes :validation), :out (str s " is not valid")}))
        "next"
          (let [[relation target] rest-args
                {:keys [sequences]} (silo-sequences context)
                result (next-for-relation sequences relation target scheme)]
            (if result
              {:exit (exit-codes :success), :out result}
              {:exit (exit-codes :usage),
               :out "Usage: denote seq next parent|child SEQ|sibling SEQ"}))
        "new" (let [[relation target] rest-args
                    {:keys [sequences]} (silo-sequences context)
                    signature
                      (next-for-relation sequences relation target scheme)]
                (if signature
                  (let [changes (assoc (new-changes-from-options options)
                                  :signature signature)
                        plan (note/plan-new changes context {})]
                    (if (:dry-run options)
                      {:exit (exit-codes :success), :out (:path plan)}
                      {:exit (exit-codes :success),
                       :out (:path (note/create plan
                                                {:reuse-empty? (:reuse-empty
                                                                 options)}))}))
                  {:exit (exit-codes :usage),
                   :out "Usage: denote seq new parent|child SEQ|sibling SEQ"}))
        "list" (let [{:keys [sequences by-sequence]} (silo-sequences context)
                     selected (selected-sequences sequences options scheme)]
                 {:exit (exit-codes :success),
                  :out (str/join
                         "\n"
                         (map #(str % "\t" (:relative-path (by-sequence %)))
                           (sequence/sort-sequences selected)))})
        "tree" (let [{:keys [sequences by-sequence]} (silo-sequences context)
                     selected (selected-sequences sequences options scheme)
                     base-depth (if-let [prefix (:prefix options)]
                                  (sequence/depth prefix)
                                  1)]
                 {:exit (exit-codes :success),
                  :out (str/join "\n"
                                 (for [s (sequence/sort-sequences selected)
                                       :let [indent (max 0
                                                         (- (sequence/depth s)
                                                            base-depth))]]
                                   (str (apply str (repeat indent "  "))
                                        s
                                        "  "
                                        (:relative-path (by-sequence s)))))})
        "convert" (let [target (:to options)
                        files rest-args]
                    (if (and target (seq files))
                      (let [plans
                              (for [file files
                                    :let [current (sequence/file-sequence file)]
                                    :when current]
                                (rename/plan-rename
                                  file
                                  {:signature (sequence/convert current target)}
                                  context
                                  {}))]
                        (apply-or-confirm (vec plans) options))
                      {:exit (exit-codes :usage),
                       :out "Usage: denote seq convert FILE... --to SCHEME"}))
        "reparent"
          (let [[file target-sequence] rest-args]
            (if (and file target-sequence)
              (let [{:keys [sequences by-sequence]} (silo-sequences context)
                    old-sequence (sequence/file-sequence file)
                    new-sequence
                      (sequence/next-child sequences target-sequence scheme)
                    plans (into
                            [(rename/plan-rename file
                                                 {:signature new-sequence}
                                                 context
                                                 {})]
                            (when (and (:recursive options) old-sequence)
                              (for [descendant (sequence/relative sequences
                                                                  old-sequence
                                                                  :all-children
                                                                  scheme)]
                                (rename/plan-rename
                                  (:path (by-sequence descendant))
                                  {:signature (str new-sequence
                                                   (subs descendant
                                                         (count old-sequence)))}
                                  context
                                  {}))))]
                (apply-or-confirm plans options))
              {:exit (exit-codes :usage),
               :out "Usage: denote seq reparent FILE TARGET-SEQUENCE"}))
        "as-parent"
          (let [file (first rest-args)]
            (cond (nil? file) {:exit (exit-codes :usage),
                               :out "Usage: denote seq as-parent FILE"}
                  (sequence/file-sequence file)
                    {:exit (exit-codes :validation),
                     :out (str file " already has a sequence signature")}
                  :else (let [{:keys [sequences]} (silo-sequences context)
                              signature (sequence/next-parent sequences scheme)
                              plan (rename/plan-rename file
                                                       {:signature signature}
                                                       context
                                                       {})]
                          (apply-or-confirm [plan] (assoc options :yes true)))))
        {:exit (exit-codes :usage),
         :out (str "Unknown seq subcommand: " subcommand)}))))

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
     (try
       (cond errors {:exit (exit-codes :usage), :out (str/join "\n" errors)}
             (or (nil? command) (:help options) (= "help" command))
               {:exit (exit-codes :success), :out help-text}
             (= command "silo") (handle-silo options harness command-args)
             (= command "list") (handle-list (make-context options harness)
                                             command-args)
             (= command "find") (handle-find (make-context options harness)
                                             command-args)
             (= command "open") (handle-open (make-context options harness)
                                             command-args)
             (= command "rename") (handle-rename (make-context options harness)
                                                 command-args)
             (= command "rename-many")
               (handle-rename-many (make-context options harness) command-args)
             (= command "new") (handle-new (make-context options harness)
                                           command-args)
             (= command "grep") (handle-grep (make-context options harness)
                                             command-args)
             (= command "backlinks")
               (handle-backlinks (make-context options harness) command-args)
             (= command "links") (handle-links (make-context options harness)
                                               command-args)
             (= command "seq") (handle-seq (make-context options harness)
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
