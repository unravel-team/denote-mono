(ns denote-mono.cli.core
  "Thin CLI entry point composing denote-mono component interfaces.
  Handlers return {:exit CODE :out STRING}; printing and System/exit live
  only in -main."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.cli :as tools-cli]
            [denote-mono.cli.completions :as completions]
            [denote-mono.config.interface :as config]
            [denote-mono.editor.interface :as editor]
            [denote-mono.filename.interface :as filename]
            [denote-mono.llm-wiki.interface :as llm-wiki]
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
  --version        Print version and exit

Commands:
  find [QUERY]     Find notes (filters: --match --keyword --signature
                   --title --id; output: --sort --json --edn --print0
                   --absolute).
                   On a terminal fzf selects interactively: Enter opens
                   the selection in $EDITOR, Ctrl-P prints it instead
  grep QUERY       Search note contents (rg-accelerated when available).
                   On a terminal fzf selects: Enter opens, Ctrl-P prints
  backlinks ID|F   Notes linking to the given note
  links FILE_OR_ID Outgoing denote: links of a note
  rename FILE      Rename any file into Denote form (--title --keyword
                   --signature --id --date --front-matter MODE
                   --break-links --dry-run --yes)
  new              Create a note (--title --keyword --signature --id
                   --date --type --subdir --dry-run --reuse-empty)
  seq next parent|child SEQ|sibling SEQ
  seq new parent|child SEQ|sibling SEQ [new options]
  seq list [SEQ] [--depth N]
  seq tree [SEQ] [--depth N]
  seq convert FILE... --to SCHEME [--dry-run --yes]
  seq reparent FILE TARGET-SEQ [--recursive --dry-run --yes]
  seq as-parent FILE [--dry-run]
  llm-wiki ingest FILE...  Distill source files into the LLM wiki
  llm-wiki query QUESTION  Answer a question from the LLM wiki (--save)
  llm-wiki lint            Check LLM wiki health (--fix --deep)
  silo list        List configured silos
  silo path [NAME] Print the path of a silo
  silo doctor      Check that configured silos exist
  completions SH   Print a completion script for bash, zsh, or fish
  help             Show this help text.")

(def ^:private global-options
  [[nil "--silo NAME" "Silo name"] [nil "--root PATH" "Explicit root directory"]
   [nil "--config PATH" "Config file path"]
   [nil "--version" "Print version and exit"] ["-h" "--help" "Show help"]])

(defn- version-string
  "Version embedded at build time (see build.clj); \"dev\" when running
  from source."
  []
  (str "denote "
       (or (some-> (io/resource "denote_mono/version.txt")
                   slurp
                   str/trim)
           "dev")))

(def ^:private find-options
  [[nil "--match REGEX" "Filter by basename regex"]
   [nil "--keyword KW" "Filter by keyword"]
   [nil "--signature SIG" "Filter by exact signature"]
   [nil "--title REGEX" "Filter by title regex"]
   [nil "--id ID" "Filter by identifier"]
   [nil "--sort KEY" "Sort key" :default "identifier"]
   [nil "--json" "JSON-lines output"] [nil "--edn" "EDN output"]
   [nil "--print0" "NUL-delimited output"]
   [nil "--absolute" "Print absolute paths"]])

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
  [global-opts {:keys [env cwd tty?]}]
  (let [cfg (load-validated-config global-opts env)]
    {:config cfg,
     :silo
     (silo/resolve-silo cfg (select-keys global-opts [:silo :root]) cwd env),
     :env env,
     :cwd cwd,
     :tty? tty?}))

(defn- render-notes
  [notes {:keys [json edn print0 absolute]}]
  (let [path-key (if absolute :path :relative-path)]
    (cond json (str/join "\n"
                         (map #(json/write-str (search/note->wire %)) notes))
          edn (str/join "\n" (map pr-str notes))
          print0 (str/join "\u0000" (map path-key notes))
          :else (str/join "\n" (map path-key notes)))))

(defn- interactive-tty?
  "Best-effort check that this process is attached to an interactive
  terminal. On JDK 22+ System/console returns a console even when
  redirected, so consult isTerminal (reflectively: it does not exist on
  older JDKs, where a non-nil console already means interactive)."
  []
  (let [console (System/console)]
    (boolean (and console
                  (let [is-terminal (try (.getMethod java.io.Console
                                                     "isTerminal"
                                                     (into-array Class []))
                                         (catch NoSuchMethodException _ nil))]
                    (if is-terminal
                      (.invoke is-terminal console (object-array 0))
                      true))))))

(def ^:private print-key
  "fzf key that prints the selection instead of opening it."
  "ctrl-p")

(defn- fzf-selector?
  "True when the configured selector is fzf itself (rather than an
  arbitrary line filter), i.e. it understands --multi and --expect."
  [argv]
  (str/includes? (last (str/split (first argv) #"/")) "fzf"))

(defn- run-selector
  "Interactively narrow CANDIDATES with the configured selector. Returns
  {:action :open|:print :chosen [lines]}, :cancelled when the user backed
  out, or :unavailable when the selector is not installed. Real fzf gets
  --multi and --expect so Enter opens and Ctrl-P prints; other selectors
  always report :open."
  [context candidates]
  (let [argv (get-in context [:config :tools :fzf] ["fzf"])]
    (if-not (process/available? (first argv))
      :unavailable
      (let [fzf? (fzf-selector? argv)
            argv (cond-> (vec argv)
                   fzf? (into ["--multi" (str "--expect=" print-key)]))
            {:keys [exit out error]}
              (process/run argv {:in (str (str/join "\n" candidates) "\n")})]
        (if (or error (not (zero? exit)))
          :cancelled
          (let [lines (str/split-lines out)
                [key chosen] (if fzf? [(first lines) (rest lines)] [nil lines])]
            {:action (if (= key print-key) :print :open),
             :chosen (vec (remove str/blank? chosen))}))))))

(defn- open-in-editor
  [context paths]
  (let [{:keys [exit]} (editor/open paths (assoc context :inherit-io? true))]
    {:exit (if (zero? exit) (exit-codes :success) (exit-codes :tool)),
     :out ""}))

(defn- interactive-output?
  "Selection engages only on a terminal and when no scripted output format
  was requested."
  [context {:keys [json edn print0]}]
  (boolean (and (:tty? context) (not (or json edn print0)))))

(defn- find-notes
  "Filter and sort notes; on a terminal narrow with the selector (Enter
  opens, Ctrl-P prints), otherwise print."
  [context options query]
  (let [filters (assoc (select-keys options
                                    [:match :keyword :signature :title :id])
                  :query query)
        notes (-> (search/list-notes context filters {})
                  (search/sort-notes (keyword (:sort options)) {}))]
    (cond (empty? notes) {:exit (exit-codes :no-match),
                          :out "No matching notes"}
          (not (interactive-output? context options))
            {:exit (exit-codes :success), :out (render-notes notes options)}
          :else (let [result (run-selector context (map :relative-path notes))]
                  (case result
                    :unavailable {:exit (exit-codes :success),
                                  :out (render-notes notes options)}
                    :cancelled {:exit (exit-codes :no-match),
                                :out "Selection cancelled"}
                    (let [{:keys [action chosen]} result
                          chosen-notes
                            (filterv (comp (set chosen) :relative-path) notes)]
                      (cond (empty? chosen-notes) {:exit (exit-codes :no-match),
                                                   :out "No matching notes"}
                            (= action :print) {:exit (exit-codes :success),
                                               :out (render-notes chosen-notes
                                                                  options)}
                            :else (open-in-editor context
                                                  (map :path
                                                    chosen-notes)))))))))

(defn- handle-find
  [context args]
  (let [{:keys [options arguments errors]} (tools-cli/parse-opts args
                                                                 find-options)]
    (if errors
      {:exit (exit-codes :usage), :out (str/join "\n" errors)}
      (find-notes context options (first arguments)))))

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
    (let [matches (search/grep context query {})
          lines
            (mapv #(str (:relative-path %) ":" (:line-number %) ":" (:line %))
              matches)]
      (cond (empty? matches) {:exit (exit-codes :no-match), :out "No matches"}
            (not (interactive-output? context nil))
              {:exit (exit-codes :success), :out (str/join "\n" lines)}
            :else (let [result (run-selector context lines)]
                    (case result
                      :unavailable {:exit (exit-codes :success),
                                    :out (str/join "\n" lines)}
                      :cancelled {:exit (exit-codes :no-match),
                                  :out "Selection cancelled"}
                      (let [{:keys [action chosen]} result
                            by-line (zipmap lines matches)]
                        (cond (empty? chosen) {:exit (exit-codes :no-match),
                                               :out "No matches"}
                              (= action :print) {:exit (exit-codes :success),
                                                 :out (str/join "\n" chosen)}
                              :else (open-in-editor context
                                                    (distinct
                                                      (keep (comp :path by-line)
                                                            chosen)))))))))
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
  --break-links was passed. Only applies inside a silo: outside there is
  no link graph to protect. Throws ex-info {:type :validation} naming the
  affected notes."
  [context plans options]
  (when-not (or (:break-links options) (nil? (:silo context)))
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

(defn- rename-context
  "Context for renaming FILE. Rename is a plain file operation that works
  anywhere; the silo (when FILE sits inside a configured one) only scopes
  the backlink guard."
  [global-opts {:keys [env cwd tty?]} file]
  (let [cfg (load-validated-config global-opts env)
        silos (sort-by key (silo/all-silos cfg env))
        containing (some (fn [[_ s]] (when (silo/in-silo? file s) s)) silos)]
    {:config cfg, :silo containing, :env env, :cwd cwd, :tty? tty?}))

(defn- handle-rename
  [global-opts harness args]
  (let [{:keys [options arguments errors]} (tools-cli/parse-opts args
                                                                 rename-options)
        file (first arguments)]
    (cond errors {:exit (exit-codes :usage), :out (str/join "\n" errors)}
          (nil? file) {:exit (exit-codes :usage),
                       :out "Usage: denote rename FILE [OPTIONS]"}
          :else (let [context (rename-context global-opts harness file)
                      plan (rename/plan-rename file
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
        [nil "--depth N" "Restrict to sequences up to this depth" :parse-fn
         parse-long]
        [nil "--recursive" "Also reparent descendants"]
        [nil "--yes" "Apply without confirmation"]))

(def ^:private llm-wiki-options
  [[nil "--save" "File the answer back as a wiki note (query)"]
   [nil "--deep" "Add an LLM review pass (lint)"]
   [nil "--fix" "Repair what can be repaired (lint)"]
   [nil "--fresh" "Ignore previous ingests of the same source (ingest)"]
   [nil "--model MODEL" "Override the configured LLM model"]
   [nil "--max-rounds N" "Cap agentic tool rounds" :parse-fn parse-long]])

(def ^:private command-spec
  "Command surface used for dispatch documentation, per-command --help,
  and completion scripts."
  [{:name "find",
    :usage "find [QUERY] [OPTIONS]",
    :description "Find notes",
    :options find-options}
   {:name "grep",
    :usage "grep QUERY",
    :description "Search note contents",
    :options []}
   {:name "backlinks",
    :usage "backlinks FILE_OR_ID",
    :description "Notes linking to the given note",
    :options []}
   {:name "links",
    :usage "links FILE_OR_ID",
    :description "Outgoing links of a note",
    :options []}
   {:name "rename",
    :usage "rename FILE [OPTIONS]",
    :description "Rename one file",
    :options rename-options}
   {:name "new",
    :usage "new [OPTIONS]",
    :description "Create a note",
    :options new-options}
   {:name "llm-wiki",
    :usage "llm-wiki ingest FILE... | query QUESTION | lint [OPTIONS]",
    :description "LLM-maintained wiki operations",
    :options llm-wiki-options,
    :subcommands ["ingest" "lint" "query"]}
   {:name "seq",
    :usage "seq SUBCOMMAND [ARGS] [OPTIONS]",
    :description "Folgezettel sequence operations",
    :options seq-options,
    :subcommands ["as-parent" "convert" "list" "new" "next" "reparent" "tree"]}
   {:name "silo",
    :usage "silo list | path [NAME] | doctor",
    :description "Silo operations",
    :options [],
    :subcommands ["doctor" "list" "path"]}
   {:name "completions",
    :usage "completions bash|zsh|fish",
    :description "Print a shell completion script",
    :options [],
    :subcommands ["bash" "fish" "zsh"]}
   {:name "help", :usage "help", :description "Show help", :options []}])

(defn- command-help
  "Per-command help text rendered from command-spec, or nil for an
  unknown command. The options summary comes from the same tables the
  command parses with, so it cannot drift."
  [command]
  (when-let [{:keys [name usage description options subcommands]}
               (some #(when (= command (:name %)) %) command-spec)]
    (str "Usage: denote "
         (or usage name)
         "\n\n"
         description
         (when (seq subcommands)
           (str "\n\nSubcommands: " (str/join ", " subcommands)))
         (when (seq options)
           (str "\n\nOptions:\n"
                (:summary (tools-cli/parse-opts [] options)))))))

(defn- handle-completions
  [args]
  (if-let [script (completions/script (first args) command-spec global-options)]
    {:exit (exit-codes :success), :out script}
    {:exit (exit-codes :usage),
     :out "Usage: denote completions bash|zsh|fish"}))

(defn- selected-sequences
  "Filter SEQUENCES to those under PREFIX (when given) and within the
  --depth seq option."
  [sequences prefix options scheme]
  (cond->> sequences
    prefix (#(sequence/sequences-with-prefix % prefix scheme))
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
        "list"
          (let [{:keys [sequences by-sequence]} (silo-sequences context)
                prefix (first rest-args)
                selected (selected-sequences sequences prefix options scheme)]
            {:exit (exit-codes :success),
             :out (str/join "\n"
                            (map #(str % "\t" (:relative-path (by-sequence %)))
                              (sequence/sort-sequences selected)))})
        "tree" (let [{:keys [sequences by-sequence]} (silo-sequences context)
                     prefix (first rest-args)
                     selected
                       (selected-sequences sequences prefix options scheme)
                     base-depth (if prefix (sequence/depth prefix) 1)]
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

(defn- stderr-progress
  "Print one progress line to stderr immediately, keeping stdout clean
  for the command's real output."
  [message]
  (binding [*out* *err*]
    (println message)
    (flush)))

(defn- make-llm-wiki-context
  "Like make-context but resolved against llm-wiki silos only; carries the
  harness's :llm-complete so tests can script the LLM. On a terminal,
  agentic operations narrate their progress to stderr."
  [global-opts {:keys [env cwd tty? llm-complete]}]
  (let [cfg (load-validated-config global-opts env)]
    {:config cfg,
     :silo (silo/resolve-llm-wiki-silo cfg
                                       (select-keys global-opts [:silo :root])
                                       cwd
                                       env),
     :env env,
     :cwd cwd,
     :tty? tty?,
     :llm-complete llm-complete,
     :on-progress (when tty? stderr-progress)}))

(defn- handle-llm-wiki-lint
  [context options]
  (let [{:keys [problems fixed]} (llm-wiki/lint context {:fix? (:fix options)})
        fixable #{:stale-index :missing-scaffold}
        remaining
          (if (:fix options) (remove (comp fixable :check) problems) problems)
        deep-report (when (:deep options)
                      (:report (llm-wiki/deep-lint
                                 context
                                 (select-keys options [:model :max-rounds]))))
        lines (concat
                (map #(str "Fixed: " %) fixed)
                (map #(str (name (:check %)) ": " (:path %) " — " (:detail %))
                  remaining)
                (when deep-report [deep-report]))]
    (if (seq remaining)
      {:exit (exit-codes :validation), :out (str/join "\n" lines)}
      {:exit (exit-codes :success),
       :out (str/join "\n" (concat lines ["Wiki OK"]))})))

(defn- ingest-source
  "Ingest one source and render the outcome as
  {:failed? BOOL :lines [STR]}."
  [context options llm-opts source]
  (let [{:keys [created updated final-text rounds stopped]}
          (llm-wiki/ingest context
                           source
                           (assoc llm-opts :fresh? (:fresh options)))]
    (cond (= stopped :max-rounds)
            {:failed? true,
             :lines [(str "LLM stopped after "
                          rounds
                          " rounds without finishing. Partial progress is"
                          " saved in the wiki; re-run the same command to"
                          " continue from the handoff note.")]}
          (= stopped :empty-reply)
            {:failed? true,
             :lines [(str "The model returned an empty reply; nothing was"
                          " ingested. Re-run to try again.")]}
          :else {:failed? false,
                 :lines (concat (map #(str "Created: " %) created)
                                (map #(str "Updated: " %) updated)
                                (when-not (str/blank? final-text)
                                  [final-text]))})))

(defn- handle-ingest
  "Ingest SOURCES sequentially, each through its own tool loop. All
  sources are validated up front so a bad path fails the batch before
  the first LLM call. Any incomplete source fails the whole run."
  [context options llm-opts sources]
  (run! llm-wiki/validate-source! sources)
  (let [results (mapv #(ingest-source context options llm-opts %) sources)
        multi? (< 1 (count sources))
        blocks (map (fn [source {:keys [lines]}]
                      (str/join
                        "\n"
                        (if multi? (cons (str source ":") lines) lines)))
                 sources
                 results)]
    {:exit
     (if (some :failed? results) (exit-codes :tool) (exit-codes :success)),
     :out (str/join "\n\n" blocks)}))

(defn- handle-llm-wiki
  [global-opts harness args]
  (let [{:keys [options arguments errors]}
          (tools-cli/parse-opts args llm-wiki-options)
        [subcommand & rest-args] arguments
        llm-opts (select-keys options [:model :max-rounds])]
    (if errors
      {:exit (exit-codes :usage), :out (str/join "\n" errors)}
      (let [context (make-llm-wiki-context global-opts harness)]
        (case subcommand
          "ingest" (if (seq rest-args)
                     (handle-ingest context options llm-opts rest-args)
                     {:exit (exit-codes :usage),
                      :out "Usage: denote llm-wiki ingest FILE..."})
          "query"
            (if-let [question (first rest-args)]
              (let [{:keys [answer saved-path stopped]}
                      (llm-wiki/query context
                                      question
                                      (assoc llm-opts :save? (:save options)))]
                (cond (= stopped :max-rounds) {:exit (exit-codes :tool),
                                               :out
                                               "LLM stopped without an answer"}
                      (str/blank? answer)
                        {:exit (exit-codes :tool),
                         :out "The model returned an empty reply; try again."}
                      :else {:exit (exit-codes :success),
                             :out (str answer
                                       (when saved-path
                                         (str "\nSaved: " saved-path)))}))
              {:exit (exit-codes :usage),
               :out "Usage: denote llm-wiki query QUESTION"})
          "lint" (handle-llm-wiki-lint context options)
          {:exit (exit-codes :usage),
           :out
           "Usage: denote llm-wiki ingest FILE... | query QUESTION | lint"})))))

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
        {:env (into {} (System/getenv)),
         :cwd (System/getProperty "user.dir"),
         :tty? (interactive-tty?)}))
  ([args harness]
   (let [{:keys [options arguments errors]}
           (tools-cli/parse-opts args global-options :in-order true)
         [command & command-args] arguments]
     (try
       (cond errors {:exit (exit-codes :usage), :out (str/join "\n" errors)}
             (or (:version options) (= "version" command))
               {:exit (exit-codes :success), :out (version-string)}
             (or (nil? command) (:help options) (= "help" command))
               {:exit (exit-codes :success), :out help-text}
             (and (some #{"--help" "-h"} command-args) (command-help command))
               {:exit (exit-codes :success), :out (command-help command)}
             (= command "silo") (handle-silo options harness command-args)
             (= command "find") (handle-find (make-context options harness)
                                             command-args)
             (= command "rename") (handle-rename options harness command-args)
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
             (= command "llm-wiki")
               (handle-llm-wiki options harness command-args)
             (= command "completions") (handle-completions command-args)
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
