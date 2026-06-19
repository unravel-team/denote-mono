(ns denote-mono.llm-wiki.tools
  "Agentic tool schemas and executor for LLM wiki maintenance."
  (:require [clojure.string :as str]
            [denote-mono.file-type.interface :as file-type]
            [denote-mono.filename.interface :as filename]
            [denote-mono.filesystem.interface :as fs]
            [denote-mono.llm-wiki.index :as index]
            [denote-mono.llm-wiki.scaffold :as scaffold]
            [denote-mono.llm-wiki.source :as source]
            [denote-mono.note.interface :as note]
            [denote-mono.search.interface :as search]
            [denote-mono.sequence.interface :as sequence]))

;;;; Schemas

(def ^:private list-notes-schema
  {:type "function",
   :function
   {:name "list_notes",
    :description
    "List every wiki note with its path, identifier, sequence, title, and keywords.",
    :parameters {:type "object", :properties {}, :required []}}})

(def ^:private read-note-schema
  {:type "function",
   :function
   {:name "read_note",
    :description
    (str "Read the full content of one wiki note. Only notes inside the"
         " wiki can be read; the raw source text is already provided in"
         " the conversation, so never try to read source files."),
    :parameters
    {:type "object",
     :properties
     {:path
      {:type "string",
       :description
       "Relative note path inside the wiki, or a bare Denote identifier like 20240101T000000."}},
     :required ["path"]}}})

(def ^:private search-notes-schema
  {:type "function",
   :function
   {:name "search_notes",
    :description "Search the contents of every wiki note for a regex query.",
    :parameters {:type "object",
                 :properties {:query {:type "string",
                                      :description "Regex to search for."}},
                 :required ["query"]}}})

(def ^:private create-note-schema
  {:type "function",
   :function
   {:name "create_note",
    :description
    (str "Create a new wiki note. The system assigns the filename,"
           " identifier, and sequence; never invent them. The body is"
         " markdown without front matter. Link other notes as"
           " [title](denote:IDENTIFIER) — only to identifiers that"
         " already exist; never write placeholder targets. This"
           " tool returns the new note's identifier so later pages"
         " can link to it. A Sources section is appended" " automatically."),
    :parameters
    {:type "object",
     :properties
     {:title {:type "string", :description "Human-readable note title."},
      :keywords {:type "array",
                 :items {:type "string"},
                 :description "Topic keywords for the filename."},
      :parent_sequence
      {:type "string",
       :description (str "Existing sequence to file this note under as a child;"
                         " omit for a new top-level page.")},
      :body {:type "string",
             :description "Markdown body without front matter."},
      :source_paths {:type "array",
                     :items {:type "string"},
                     :description
                     (str "Source URIs or absolute paths that this note"
                          " distills; defaults to the sources of the"
                          " current operation.")}},
     :required ["title" "body"]}}})

(def ^:private update-note-schema
  {:type "function",
   :function {:name "update_note",
              :description
              (str
                "Replace the body of an existing wiki note. Front matter is"
                " preserved and existing source links are kept; the Sources"
                  " section is maintained automatically. The body is markdown"
                " without front matter. Link other notes as"
                  " [title](denote:IDENTIFIER); never write placeholder"
                " targets. Batch all changes to a note into one call instead"
                  " of updating it repeatedly."),
              :parameters
              {:type "object",
               :properties
               {:path {:type "string",
                       :description "Relative note path inside the wiki."},
                :body {:type "string",
                       :description "New markdown body without front matter."},
                :add_source_paths
                {:type "array",
                 :items {:type "string"},
                 :description
                 "Source URIs or absolute paths of additional raw sources."}},
               :required ["path" "body"]}}})

(defn tool-schemas
  "OpenAI function-tool schemas for MODE :query (read-only) or :ingest."
  [mode]
  (case mode
    :query [list-notes-schema read-note-schema search-notes-schema]
    :ingest [list-notes-schema read-note-schema search-notes-schema
             create-note-schema update-note-schema]))

;;;; Shared helpers

(defn- silo-root [context] (fs/canonical (get-in context [:silo :path])))

(defn- relative-to [root path] (subs path (inc (count root))))

(defn- basename
  [path]
  (if-let [i (str/last-index-of path "/")]
    (subs path (inc i))
    path))

(defn- contained-path
  "Resolve PATH against ROOT, rejecting anything that escapes it."
  [root path]
  (let [resolved (fs/canonical (str root "/" path))]
    ;; [ref:silo_path_containment]
    (when-not (fs/inside-root? root resolved)
      (throw (ex-info (str "Path escapes the silo: " path)
                      {:type :validation, :path path})))
    resolved))

(defn- nonblank-source-ref
  [ref]
  (let [ref (some-> ref
                    str/trim)]
    (when-not (str/blank? ref) ref)))

(defn normalize-source-ref
  "Normalize an already persisted source ref. URLs and existing file: URIs stay
  unchanged; local paths become file: URIs without further canonicalization."
  [ref]
  (when-let [ref (nonblank-source-ref ref)]
    (cond (source/url? ref) ref
          (str/starts-with? ref "file:") ref
          :else (str "file:" (source/file-uri-path ref)))))

(defn- canonical-source-ref
  "Normalize a newly supplied tool source ref to a persisted URI."
  [ref]
  (when-let [ref (nonblank-source-ref ref)]
    (if (source/url? ref)
      ref
      (let [path (source/file-uri-path ref)]
        (when-not (.isAbsolute (java.io.File. ^String path))
          (throw (ex-info (str "Source path must be absolute or a URI: " ref)
                          {:type :validation, :source ref})))
        (source/file-uri ref)))))

(defn- source-label [uri] (source/display-name uri))

;; Built with re-pattern so the source never contains a literal org-style
;; file link opener, which tagref would try to validate as a reference.
(def ^:private org-file-link-pattern
  (re-pattern (str "\\[\\[file" ":([^\\]]+)\\]")))

(def ^:private md-source-link-pattern
  #"\[[^\]]*\]\((file:[^)]+|https?://[^)]+)\)")

(defn source-link-targets
  "All file:/http:/https: source link targets in CONTENT."
  [content]
  (concat (map second (re-seq md-source-link-pattern content))
          (map #(str "file:" (second %))
            (re-seq org-file-link-pattern content))))

(defn file-link-targets
  "All file: link targets in CONTENT, in markdown and org link form."
  [content]
  (keep (fn [uri]
          (when (str/starts-with? uri "file:") (source/file-uri-path uri)))
        (source-link-targets content)))

(defn- heading-title
  [line]
  (some-> (second (re-find #"^\s{0,3}#{1,6}\s+(.+?)\s*$" line))
          str/lower-case))

(defn- provenance-section-lines
  [content]
  (let [wanted? #{"sources" "citations"}]
    (loop [[line & more] (str/split-lines content)
           in-section? false
           acc []]
      (if (nil? line)
        acc
        (if-let [heading (heading-title line)]
          (recur more (contains? wanted? heading) acc)
          (recur more in-section? (if in-section? (conj acc line) acc)))))))

(defn provenance-link-targets
  "Source URI links from ## Sources or # Citations sections."
  [content]
  (source-link-targets (str/join "\n" (provenance-section-lines content))))

(defn- provenance-source-refs
  [content]
  (remove nil? (map normalize-source-ref (provenance-link-targets content))))

(defn sources-section
  "A \"## Sources\" markdown section linking source REFS, nil when REFS is
  empty."
  [refs]
  (let [uris (remove nil? (map normalize-source-ref refs))]
    (when (seq uris)
      (apply str
        "\n\n## Sources\n\n"
        (map (fn [uri] (str "- [" (source-label uri) "](" uri ")\n")) uris)))))

(defn silo-sequences
  "All valid sequences among the silo's notes."
  [context]
  (vec (keep #(sequence/file-sequence (:path %))
             (search/list-notes context {} {}))))

;;;; Tool implementations

(defn- run-list-notes
  [context]
  (->> (search/list-notes context {} {})
       (sort-by :relative-path)
       (mapv (fn [{:keys [relative-path filename]}]
               {:path relative-path,
                :identifier (:identifier filename),
                :sequence (:signature filename),
                :title (:title filename),
                :keywords (:keywords filename)}))))

(defn- run-read-note
  [context {:keys [path]}]
  (let [root (silo-root context)
        abs (if (filename/date-identifier? path)
              (or (some #(when (= path (get-in % [:filename :identifier]))
                           (:path %))
                        (search/list-notes context {} {}))
                  (throw (ex-info (str "No note with identifier: " path)
                                  {:type :validation, :path path})))
              (contained-path root path))]
    {:path (relative-to root abs), :content (fs/read-text abs)}))

(defn- run-search-notes
  [context {:keys [query]}]
  ;; Force rg to print the file path even for a single-file haystack so
  ;; that search/grep can always parse its output as path:line:text.
  (let [context (update-in context
                           [:config :tools :rg]
                           #(conj (vec (or % ["rg"])) "--with-filename"))]
    (mapv (fn [{:keys [relative-path line-number line]}]
            (str relative-path ":" line-number ":" line))
      (search/grep context query {}))))

(defn- normalize-keywords
  "The schema declares an array, but models sometimes send one comma- or
  space-separated string; charsplitting that into the filename would be
  garbage."
  [keywords]
  (if (string? keywords)
    (vec (remove str/blank? (str/split keywords #"[,\s]+")))
    (vec keywords)))

(defn- run-create-note
  [context state {:keys [title keywords parent_sequence body source_paths]}]
  (let [root (silo-root context)
        scheme (get-in context [:config :sequence :scheme] :numeric)
        sequences (silo-sequences context)
        signature (if parent_sequence
                    (sequence/next-child sequences parent_sequence scheme)
                    (sequence/next-parent sequences scheme))
        raw-sources (or (not-empty source_paths) (:default-sources @state))
        sources (mapv canonical-source-ref raw-sources)
        plan (note/plan-new {:title title,
                             :keywords (normalize-keywords keywords),
                             :signature signature,
                             :type :markdown-yaml,
                             :template (str (str/trim body)
                                            (sources-section sources))}
                            context
                            {})
        created (note/create plan {})
        relative (relative-to root (:path created))]
    (swap! state update :created conj relative)
    {:path relative, :identifier (:identifier created), :sequence signature}))

(defn- run-update-note
  [context state {:keys [path body add_source_paths]}]
  (let [root (silo-root context)
        abs (contained-path root path)]
    (when (scaffold/scaffold-file? (basename abs))
      (throw (ex-info (str "Refusing to edit a machine-maintained file: " path)
                      {:type :validation, :path path})))
    (let [old (fs/read-text abs)
          type (or (file-type/detect abs
                                     old
                                     (get-in context [:config :filename] {}))
                   :markdown-yaml)
          [front _] (index/split-front-matter type old)
          new-body (str/trim body)
          new-source-refs (set (provenance-source-refs new-body))
          kept (remove new-source-refs (provenance-source-refs old))
          sources (vec (distinct (concat kept
                                         (keep canonical-source-ref
                                               add_source_paths))))
          content (str front "\n" new-body (sources-section sources))
          relative (relative-to root abs)]
      (fs/write-text
        abs
        (if (str/ends-with? content "\n") content (str content "\n")))
      (swap! state update :updated conj relative)
      {:path relative, :updated true})))

(defn make-execute-tool
  "Executor over the silo in CONTEXT. STATE is an atom
  {:created [] :updated [] :default-sources []}; :created/:updated collect
  silo-relative paths."
  [context state]
  (fn [tool-name args]
    (case tool-name
      "list_notes" (run-list-notes context)
      "read_note" (run-read-note context args)
      "search_notes" (run-search-notes context args)
      "create_note" (run-create-note context state args)
      "update_note" (run-update-note context state args)
      (throw (ex-info (str "Unknown tool: " tool-name)
                      {:type :validation, :tool tool-name})))))
