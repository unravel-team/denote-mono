(ns denote-mono.llm-wiki.core
  "Agentic LLM-wiki maintenance: ingest sources, answer questions, and
  audit the wiki through the tool loop."
  (:require [clojure.string :as str]
            [denote-mono.filesystem.interface :as fs]
            [denote-mono.llm.interface :as llm]
            [denote-mono.llm-wiki.index :as index]
            [denote-mono.llm-wiki.scaffold :as scaffold]
            [denote-mono.llm-wiki.source :as source]
            [denote-mono.llm-wiki.tools :as tools]
            [denote-mono.note.interface :as note]
            [denote-mono.search.interface :as search]
            [denote-mono.sequence.interface :as sequence])
  (:import (java.time LocalDate ZoneId)))

;;;; Plumbing

(def ^:private link-id-pattern #"denote:([0-9]{8}T[0-9]{6})")

(defn- silo-root [context] (fs/canonical (get-in context [:silo :path])))

(defn- resolve-provider
  "Register the configured LLM provider with DSCloj and return its key. The
  API key is resolved from the environment; absent, that is a validation
  error naming the env var."
  [context opts]
  (let [llm-config (get-in context [:config :llm])
        env-var (:api-key-env llm-config)
        api-key (get (:env context) env-var)]
    (when (str/blank? api-key)
      (throw (ex-info
               (str "Missing API key: set the " env-var " environment variable")
               {:type :validation, :env-var env-var})))
    (llm/register-provider! (cond-> (assoc llm-config :api-key api-key)
                              (:model opts) (assoc :model (:model opts))))))

(defn- max-rounds
  [context opts]
  (or (:max-rounds opts) (get-in context [:config :llm :max-rounds]) 20))

(defn- max-tokens
  "Reply budget per round. Reasoning models spend hidden thinking tokens
  from the same budget, so this errs generous."
  [context]
  (get-in context [:config :llm :max-tokens] 8192))

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
       " directions. Linking rules: never invent link targets and never"
         " write placeholder links — create_note returns the new page's"
       " identifier, so create a page first and link to it afterwards, or"
         " add the link in a later update. Batch every change to a note into"
       " a single update_note call instead of editing it repeatedly."
         " Finish with a one-paragraph report of what you did."))

(def ^:private handoff-prompt
  (str "The agent's round budget is exhausted. From its trajectory, write a"
         " short, single-paragraph handoff note describing what remains to be"
       " done for this source: pages not yet created, sections not yet"
         " covered, missing cross-links. Plain text only."))

(def ^:private handoff-module
  {:inputs [{:name :trajectory,
             :spec :string,
             :description
             "The agent's thoughts, tool calls, and observations."}],
   :outputs [{:name :remaining,
              :spec :string,
              :description
              "One paragraph: the work remaining for this source."}],
   :instructions handoff-prompt})

(defn- one-line
  [s]
  (when s
    (let [collapsed (str/trim (str/replace s #"\s+" " "))]
      (when-not (str/blank? collapsed) collapsed))))

(defn- handoff-note
  "Summarize remaining work from the agent's TRAJECTORY via chain-of-thought.
  Best effort: nil when the call fails or yields nothing."
  [provider trajectory]
  (try (one-line (:remaining (llm/chain-of-thought provider
                                                   handoff-module
                                                   {:trajectory trajectory}
                                                   {:validate? false})))
       (catch Exception _ nil)))

(defn- continuation-section
  "User-prompt block describing a previous ingest of the same source, so
  the model continues instead of starting over."
  [{:keys [status created updated remaining]}]
  (str "This source was ingested before (status: "
       (or status "unknown")
       ").\n"
       "Wiki pages already created or updated from it:\n"
       (apply str (map #(str "- " % "\n") (distinct (concat created updated))))
       (when remaining
         (str "A handoff note from that run describes the remaining work:\n"
              remaining
              "\n"))
       "Continue that work: check the source against these pages, complete"
       " what is missing, and finish the cross-links. Prefer update_note"
       " over creating duplicate pages."))

(defn- unchanged-since-ingest?
  "True when HISTORY proves the source is fully ingested and unchanged.
  Prefer source hash comparison; fall back to legacy mtime logic for old
  entries without hash metadata."
  [{:keys [status source-hash source-mtime date]} {:keys [fingerprint]}]
  (let [current-mtime (:mtime fingerprint)
        current-hash (:sha256 fingerprint)]
    (boolean (and (= "complete" status)
                  (or (and source-hash (= source-hash current-hash))
                      (if source-mtime
                        (and current-mtime
                             (= current-mtime (parse-long source-mtime)))
                        (when (and current-mtime date)
                          (< current-mtime
                             (-> (LocalDate/parse date)
                                 (.atStartOfDay (ZoneId/systemDefault))
                                 .toInstant
                                 .toEpochMilli)))))))))

(defn validate-source!
  "Reject a missing, directory, or unsupported ingest source. Callers with
  several sources prepare all of them before the first LLM call, so a bad
  source costs nothing."
  [source-path]
  (source/validate-source! source-path))

(def ^:private skipped-result
  {:created [],
   :updated [],
   :final-text nil,
   :rounds 0,
   :stopped :skipped,
   :remaining nil})

(declare run-ingest)

(defn ingest-prepared
  "Distill already prepared SOURCE into the wiki through the agentic tool
  loop, then refresh the index and the log. A source whose latest log entry
  is complete and unchanged since is skipped outright (:stopped :skipped) —
  no LLM call, no new log entry; :fresh? overrides.
  Returns {:created :updated :final-text :rounds :stopped :remaining}."
  [context prepared opts]
  (let [progress (progress-fn context)
        history (when-not (:fresh? opts)
                  (scaffold/ingest-history context (:uri prepared)))]
    (if (and history (unchanged-since-ingest? history prepared))
      (do (progress (str "skipping "
                         (:display-name prepared)
                         " (unchanged since last ingest)"))
          skipped-result)
      (run-ingest context prepared opts history))))

(defn ingest
  "Prepare SOURCE and distill it into the wiki."
  [context source opts]
  (ingest-prepared context (source/prepare-source context source opts) opts))

(defn- run-ingest
  [context source opts history]
  (let [progress (progress-fn context)
        fingerprint (get source :fingerprint)
        mtime (:mtime fingerprint)
        _ (scaffold/scaffold context)
        provider (resolve-provider context opts)
        state (atom
                {:created [], :updated [], :default-sources [(:uri source)]})
        _ (progress (str "ingesting "
                         (:display-name source)
                         (when history " (continuing a previous ingest)")))
        result (llm/run-tool-loop
                 provider
                 {:system
                  (str (schema-text context) "\n\n" ingest-instructions),
                  :user (str "Source: "
                             (:uri source)
                             "\n"
                             "Source type: "
                             (name (:kind source))
                             "\n\nExtracted text:\n\n"
                             (:content source)
                             (when history
                               (str "\n\n" (continuation-section history)))
                             "\n\nCurrent index:\n\n"
                             (current-index context)),
                  :tools (tools/tool-schemas :ingest),
                  :execute-tool (tools/make-execute-tool context state),
                  :max-rounds (max-rounds context opts),
                  :max-tokens (max-tokens context),
                  :on-event (on-event-fn context)})
        {:keys [created updated]} @state
        created (vec (distinct created))
        updated (vec (distinct updated))
        incomplete? (= :max-rounds (:stopped result))
        ;; A reply with no text, no tool calls, and no work done is a
        ;; model failure (e.g. the whole token budget spent on hidden
        ;; reasoning), not a finished ingest.
        empty-reply? (and (= :done (:stopped result))
                          (str/blank? (:final-text result))
                          (empty? created)
                          (empty? updated))
        remaining (when incomplete?
                    (progress "asking the model for a handoff note")
                    (handoff-note provider (:trajectory result)))
        status (cond incomplete? (str "incomplete (max-rounds after "
                                      (:rounds result)
                                      ")")
                     empty-reply? "incomplete (empty reply)"
                     :else "complete")]
    (progress "updating index and log")
    (index/regenerate-index context)
    (scaffold/append-log
      context
      {:date (entry-date opts),
       :op "ingest",
       :title (:display-name source),
       :details (concat [(str "source: " (:uri source))
                         (str "source-kind: " (name (:kind source)))
                         (str "source-hash: " (:sha256 fingerprint))
                         (str "status: " status)]
                        ;; Captured before the run: a file edited while
                        ;; ingesting will mismatch and reprocess next time.
                        (when mtime [(str "source-mtime: " mtime)])
                        (when-let [etag (:etag fingerprint)]
                          [(str "source-etag: " etag)])
                        (when-let [last-modified (:last-modified fingerprint)]
                          [(str "source-last-modified: " last-modified)])
                        (when-let [final-url (:final-url fingerprint)]
                          [(str "source-final-url: " final-url)])
                        (map #(str "created: " %) created)
                        (map #(str "updated: " %) updated)
                        (when remaining [(str "remaining: " remaining)]))})
    {:created created,
     :updated updated,
     :final-text (:final-text result),
     :rounds (:rounds result),
     :stopped (if empty-reply? :empty-reply (:stopped result)),
     :remaining remaining}))

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
  (let [provider (resolve-provider context opts)
        state (atom {:created [], :updated [], :default-sources []})
        result (llm/run-tool-loop
                 provider
                 {:system (str (schema-text context) "\n\n" query-instructions),
                  :user (str question
                             "\n\nCurrent index:\n\n"
                             (current-index context)),
                  :tools (tools/tool-schemas :query),
                  :execute-tool (tools/make-execute-tool context state),
                  :max-rounds (max-rounds context opts),
                  :max-tokens (max-tokens context),
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
  (let [provider (resolve-provider context opts)
        state (atom {:created [], :updated [], :default-sources []})
        result (llm/run-tool-loop
                 provider
                 {:system
                  (str (schema-text context) "\n\n" deep-lint-instructions),
                  :user (str "Current index:\n\n"
                             (current-index context)
                             "\n\nAudit the wiki and report your findings."),
                  :tools (tools/tool-schemas :query),
                  :execute-tool (tools/make-execute-tool context state),
                  :max-rounds (max-rounds context opts),
                  :max-tokens (max-tokens context),
                  :on-event (on-event-fn context)})]
    {:report (:final-text result)}))
