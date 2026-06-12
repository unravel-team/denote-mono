(ns denote-mono.llm-wiki.scaffold
  "Create and maintain the machine-managed files of an LLM wiki silo:
  index.md, log.md, and wiki-schema.md."
  (:require [clojure.string :as str]
            [denote-mono.filesystem.interface :as fs]
            [denote-mono.llm-wiki.index :as index])
  (:import (java.util.regex Pattern)))

(def file-names ["index.md" "log.md" "wiki-schema.md"])

(def ^:private file-name-set (set file-names))

(defn scaffold-file? [basename] (contains? file-name-set basename))

(def ^:private initial-log "# Log\n")

(def schema-content
  (str
    "# Wiki Schema\n\n" "Conventions for this LLM-maintained wiki.\n\n"
    "## Layers\n\n"
      "1. Raw sources: immutable files outside this silo, only ever linked.\n"
    "2. Wiki notes: Denote files in this silo, maintained by an LLM.\n"
      "3. This schema document, which defines the conventions.\n\n"
    "## Notes\n\n" "- Notes are markdown files with YAML front matter, named\n"
    "  `ID==SEQUENCE--title__keywords.md`.\n"
      "- Placement follows Folgezettel: children refine their parents,\n"
    "  so `1=1` elaborates on `1`.\n"
      "- Every note ends with a `## Sources` section of `[name](file:/abs/path)`\n"
    "  links back to the raw sources. Never remove them.\n"
      "- Cross-link densely with `[title](denote:ID)` links.\n"
    "- Link only to identifiers that exist. Never write placeholder link\n"
      "  targets; create a page first, then link to it.\n"
    "- Pages are evergreen: update existing pages instead of duplicating\n"
      "  topics.\n\n"
    "## Machine-maintained files\n\n"
      "- `index.md` and `log.md` are machine-maintained. Never edit them.\n"))

(defn- create-when-missing
  [path action]
  (when-not (fs/exists? path) (action) path))

(defn scaffold
  "Create any missing scaffold file at the silo root. Returns
  {:created [ABSOLUTE-PATHS]}, [] when everything is already present."
  [context]
  (let [root (fs/canonical (get-in context [:silo :path]))
        path (fn [name] (str root "/" name))]
    {:created
     (vec (keep identity
                [(create-when-missing (path "index.md")
                                      #(index/regenerate-index context))
                 (create-when-missing (path "log.md")
                                      #(fs/write-text (path "log.md")
                                                      initial-log))
                 (create-when-missing (path "wiki-schema.md")
                                      #(fs/write-text (path "wiki-schema.md")
                                                      schema-content))]))}))

(defn- entry-detail
  [entry key]
  (second (re-find (re-pattern (str "(?m)^- " key ": (.*)$")) entry)))

(defn- entry-details
  [entry key]
  (mapv second (re-seq (re-pattern (str "(?m)^- " key ": (.*)$")) entry)))

(defn ingest-history
  "The latest log.md ingest entry for SOURCE-PATH, as
  {:status STR-or-nil :created [REL] :updated [REL] :remaining STR-or-nil}.
  Nil when the source was never ingested into this wiki."
  [context source-path]
  (let [root (fs/canonical (get-in context [:silo :path]))
        log-path (str root "/log.md")
        source-abs (fs/canonical source-path)]
    (when (fs/exists? log-path)
      (let [entries (str/split (fs/read-text log-path) #"(?m)^## ")
            source-line (re-pattern (str "(?m)^- source: file:"
                                         (Pattern/quote source-abs)
                                         "$"))
            ingest-of-source? (fn [entry]
                                (and (str/includes? entry "] ingest | ")
                                     (re-find source-line entry)))]
        (when-let [entry (last (filter ingest-of-source? entries))]
          {:status (entry-detail entry "status"),
           :created (entry-details entry "created"),
           :updated (entry-details entry "updated"),
           :remaining (entry-detail entry "remaining")})))))

(defn append-log
  "Append ENTRY {:date :op :title :details} to log.md, creating the file
  when missing. Existing content is never rewritten."
  [context {:keys [date op title details]}]
  (let [root (fs/canonical (get-in context [:silo :path]))
        path (str root "/log.md")
        existing (if (fs/exists? path) (fs/read-text path) initial-log)
        entry (str "\n## [" date
                   "] " op
                   " | " title
                   "\n" (when (seq details)
                          (apply str "\n" (map #(str "- " % "\n") details))))]
    (fs/write-text path (str existing entry))))
