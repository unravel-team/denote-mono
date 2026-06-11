(ns denote-mono.llm-wiki.core
  "Agentic LLM-wiki maintenance: ingest sources, answer questions, and
  audit the wiki through the tool loop."
  (:require [clojure.string :as str]
            [denote-mono.file-type.interface :as file-type]
            [denote-mono.filesystem.interface :as fs]
            [denote-mono.llm.interface :as llm]
            [denote-mono.llm-wiki.index :as index]
            [denote-mono.llm-wiki.scaffold :as scaffold]
            [denote-mono.llm-wiki.tools :as tools]
            [denote-mono.note.interface :as note]
            [denote-mono.search.interface :as search]
            [denote-mono.sequence.interface :as sequence])
  (:import (java.time LocalDate)))

;;;; Plumbing

(def ^:private link-id-pattern #"denote:([0-9]{8}T[0-9]{6})")

(defn- basename
  [path]
  (if-let [i (str/last-index-of path "/")]
    (subs path (inc i))
    path))

(defn- silo-root [context] (fs/canonical (get-in context [:silo :path])))

(defn- complete-fn
  "The context's injected :llm-complete, or one built from config with the
  API key resolved from the environment."
  [context opts]
  (or (:llm-complete context)
      (let [llm-config (get-in context [:config :llm])
            env-var (:api-key-env llm-config)
            api-key (get (:env context) env-var)]
        (when (str/blank? api-key)
          (throw (ex-info (str "Missing API key: set the "
                               env-var
                               " environment variable")
                          {:type :validation, :env-var env-var})))
        (llm/make-complete-fn (cond-> (assoc llm-config :api-key api-key)
                                (:model opts) (assoc :model (:model opts)))))))

(defn- max-rounds
  [context opts]
  (or (:max-rounds opts) (get-in context [:config :llm :max-rounds]) 20))

(defn- schema-text
  [context]
  (let [path (str (silo-root context) "/wiki-schema.md")]
    (if (fs/exists? path) (fs/read-text path) scaffold/schema-content)))

(defn- current-index
  [context]
  (let [path (str (silo-root context) "/index.md")]
    (if (fs/exists? path)
      (fs/read-text path)
      (index/regenerate-index context))))

(defn- entry-date [opts] (or (:date opts) (str (LocalDate/now))))

(defn- event->message
  "Human one-liner for a tool-loop progress event."
  [{:keys [event round name args message]}]
  (case event
    :request (str "round " round ": waiting for the model...")
    :tool-call (str "round " round
                    ": " (case name
                           "list_notes" "listing notes"
                           "read_note" (str "reading " (:path args))
                           "search_notes" (str "searching: " (:query args))
                           "create_note" (str "creating note: " (:title args))
                           "update_note" (str "updating " (:path args))
                           name))
    :tool-error (str "round " round ": " name " failed: " message)
    nil))

(defn- progress-fn
  "The context's :on-progress (a fn of one string), or a no-op."
  [context]
  (or (:on-progress context) (constantly nil)))

(defn- on-event-fn
  [context]
  (let [progress (progress-fn context)]
    (fn [event]
      (some-> (event->message event)
              progress))))

;;;; Ingest

(def ^:private ingest-instructions
  (str "You maintain a personal wiki distilled from raw sources. Consult"
       " the index below, read overlapping pages with the tools, UPDATE"
         " existing pages that the new source extends, and CREATE pages for"
       " new topics. Aim for 10-15 focused pages for a substantial source,"
         " fewer for a small one. Place new pages with parent_sequence so"
       " children refine their parents. Cross-link related pages in both"
         " directions. Finish with a one-paragraph report of what you did."))

(defn- validate-source!
  [source-path]
  (when-not (fs/exists? source-path)
    (throw (ex-info (str "Source does not exist: " source-path)
                    {:type :validation, :path source-path})))
  (when (fs/directory? source-path)
    (throw (ex-info (str "Source is a directory: " source-path)
                    {:type :validation, :path source-path})))
  (when-not (file-type/text-file? source-path)
    (throw (ex-info (str "Source is not a supported text file: " source-path)
                    {:type :validation, :path source-path}))))

(defn ingest
  "Distill SOURCE-PATH into the wiki through the agentic tool loop, then
  refresh the index and the log. Returns
  {:created :updated :final-text :rounds :stopped}."
  [context source-path opts]
  (validate-source! source-path)
  (let [abs (fs/canonical source-path)
        progress (progress-fn context)
        _ (scaffold/scaffold context)
        complete (complete-fn context opts)
        state (atom {:created [], :updated [], :default-sources [abs]})
        _ (progress (str "ingesting " (basename abs)))
        result (llm/run-tool-loop
                 complete
                 {:system
                  (str (schema-text context) "\n\n" ingest-instructions),
                  :user (str "Source file: " abs
                             "\n\n" (fs/read-text abs)
                             "\n\nCurrent index:\n\n" (current-index context)),
                  :tools (tools/tool-schemas :ingest),
                  :execute-tool (tools/make-execute-tool context state),
                  :max-rounds (max-rounds context opts),
                  :on-event (on-event-fn context)})
        {:keys [created updated]} @state]
    (progress "updating index and log")
    (index/regenerate-index context)
    (scaffold/append-log context
                         {:date (entry-date opts),
                          :op "ingest",
                          :title (basename abs),
                          :details (concat [(str "source: file:" abs)]
                                           (map #(str "created: " %) created)
                                           (map #(str "updated: " %) updated))})
    {:created created,
     :updated updated,
     :final-text (:final-text result),
     :rounds (:rounds result),
     :stopped (:stopped result)}))

;;;; Query

(def ^:private query-instructions
  (str "Answer ONLY from the wiki. Use the tools to find relevant pages."
       " Cite every claim with [title](denote:ID) links. Say so explicitly"
       " when the wiki does not cover the question."))

(defn- save-answer
  "File ANSWER as a wiki note under the first cited note, refresh the
  index and the log, and return the new note's relative path."
  [context question answer opts]
  (let [root (silo-root context)
        notes (search/list-notes context {} {})
        by-id (into {}
                    (keep (fn [note]
                            (when-let [id (get-in note [:filename :identifier])]
                              [id note])))
                    notes)
        cited (vec (keep by-id
                         (distinct (map second
                                     (re-seq link-id-pattern answer)))))
        scheme (get-in context [:config :sequence :scheme] :numeric)
        sequences (tools/silo-sequences context)
        parent (get-in (first cited) [:filename :signature])
        signature (if parent
                    (sequence/next-child sequences parent scheme)
                    (sequence/next-parent sequences scheme))
        sources
          (map #(str (get-in context [:silo :path]) "/" (:relative-path %))
            cited)
        plan (note/plan-new {:title question,
                             :keywords ["answer"],
                             :signature signature,
                             :type :markdown-yaml,
                             :template (str (str/trim answer)
                                            (tools/sources-section sources))}
                            context
                            {})
        created (note/create plan {})
        relative (subs (:path created) (inc (count root)))]
    (index/regenerate-index context)
    (scaffold/append-log context
                         {:date (entry-date opts),
                          :op "query",
                          :title question,
                          :details
                          (conj (mapv #(str "cited: " (:relative-path %)) cited)
                                (str "saved: " relative))})
    relative))

(defn query
  "Answer QUESTION from the wiki through the read-only tool loop. With
  {:save? true} the answer is filed as a note. Returns
  {:answer :saved-path :stopped}."
  [context question opts]
  (let [complete (complete-fn context opts)
        state (atom {:created [], :updated [], :default-sources []})
        result (llm/run-tool-loop
                 complete
                 {:system (str (schema-text context) "\n\n" query-instructions),
                  :user (str question
                             "\n\nCurrent index:\n\n"
                             (current-index context)),
                  :tools (tools/tool-schemas :query),
                  :execute-tool (tools/make-execute-tool context state),
                  :max-rounds (max-rounds context opts),
                  :on-event (on-event-fn context)})
        answer (:final-text result)
        saved (when (and (:save? opts) (not (str/blank? answer)))
                (save-answer context question answer opts))]
    {:answer answer, :saved-path saved, :stopped (:stopped result)}))

;;;; Deep lint

(def ^:private deep-lint-instructions
  (str "You are auditing this wiki for contradictions between pages, stale"
       " claims, duplicated topics, and misplaced sequence positions."
       " Report findings as a markdown list. Do not modify anything."))

(defn deep-lint
  "Audit the wiki through the read-only tool loop. Returns {:report STR}."
  [context opts]
  (let [complete (complete-fn context opts)
        state (atom {:created [], :updated [], :default-sources []})
        result (llm/run-tool-loop
                 complete
                 {:system
                  (str (schema-text context) "\n\n" deep-lint-instructions),
                  :user (str "Current index:\n\n"
                             (current-index context)
                             "\n\nAudit the wiki and report your findings."),
                  :tools (tools/tool-schemas :query),
                  :execute-tool (tools/make-execute-tool context state),
                  :max-rounds (max-rounds context opts),
                  :on-event (on-event-fn context)})]
    {:report (:final-text result)}))
