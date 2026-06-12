(ns denote-mono.llm-wiki.lint
  "Mechanical consistency checks over an LLM wiki silo."
  (:require [clojure.string :as str]
            [denote-mono.file-type.interface :as file-type]
            [denote-mono.filename.interface :as filename]
            [denote-mono.filesystem.interface :as fs]
            [denote-mono.llm-wiki.index :as index]
            [denote-mono.llm-wiki.scaffold :as scaffold]
            [denote-mono.llm-wiki.tools :as tools]
            [denote-mono.search.interface :as search]
            [denote-mono.sequence.interface :as sequence]))

(def ^:private link-id-pattern #"denote:([0-9]{8}T[0-9]{6})")

(defn- basename
  [path]
  (if-let [i (str/last-index-of path "/")]
    (subs path (inc i))
    path))

(defn- problem [check path detail] {:check check, :path path, :detail detail})

(defn- non-denote-file-problems
  [root config]
  (for [file (fs/list-files [root] (get config :files {}))
        :let [base (basename file)]
        :when (and (not (scaffold/scaffold-file? base))
                   (not (filename/valid-denote-filename? file)))]
    (problem :non-denote-file
             (subs file (inc (count root)))
             "not a valid Denote filename")))

(defn- broken-link-problems
  [notes texts ids]
  (for [{:keys [path relative-path]} notes
        :let [text (texts path)]
        :when text
        id (distinct (map second (re-seq link-id-pattern text)))
        :when (not (contains? ids id))]
    (problem :broken-link
             relative-path
             (str "links to missing note denote:" id))))

(defn- source-problems
  [notes texts]
  (mapcat (fn [{:keys [path relative-path]}]
            (when-let [text (texts path)]
              (let [targets (tools/file-link-targets text)]
                (if (empty? targets)
                  [(problem :missing-source
                            relative-path
                            "no file: source link")]
                  (for [target targets
                        :when (not (fs/exists? target))]
                    (problem :source-missing-on-disk
                             relative-path
                             (str "source not on disk: " target)))))))
    notes))

(def ^:private md-link-pattern #"\[[^\]]*\]\(([^)]+)\)")

(defn- allowed-link-target?
  [target]
  (let [target (str/trim target)]
    (some #(str/starts-with? target %)
          ["denote:" "file:" "http://" "https://" "mailto:" "#"])))

(defn- suspicious-link-problems
  "Markdown links whose target is neither a denote: link, a file: link,
  nor a URL — placeholders the model invented, typically."
  [notes texts]
  (for [{:keys [path relative-path]} notes
        :let [text (texts path)]
        :when text
        target (distinct (map second (re-seq md-link-pattern text)))
        :when (not (allowed-link-target? target))]
    (problem :suspicious-link
             relative-path
             (str "link target is neither denote:, file:, nor a URL: "
                  target))))

(defn- orphan-problems
  [notes texts]
  (let [outgoing (into {}
                       (map (fn [{:keys [path]}] [path
                                                  (set (map second
                                                         (re-seq
                                                           link-id-pattern
                                                           (texts path ""))))]))
                       notes)]
    (for [{:keys [path relative-path filename]} notes
          :let [id (:identifier filename)
                incoming? (some (fn [[other-path links]]
                                  (and (not= other-path path) (links id)))
                                outgoing)]
          :when (and (empty? (outgoing path)) (not incoming?))]
      (problem :orphan relative-path "no incoming or outgoing denote links"))))

(defn- sequence-problems
  [notes scheme]
  (for [{:keys [relative-path filename]} notes
        :let [signature (:signature filename)]
        :when (or (nil? signature)
                  (not (sequence/valid-for-scheme? scheme signature)))]
    (problem :invalid-sequence
             relative-path
             (if (nil? signature)
               "missing sequence"
               (str "invalid sequence for scheme " (name scheme)
                    ": " signature)))))

(defn- duplicate-sequence-problems
  [notes]
  (for [[signature group] (group-by #(get-in % [:filename :signature]) notes)
        :when (and signature (< 1 (count group)))
        {:keys [relative-path]} group]
    (problem :duplicate-sequence
             relative-path
             (str "sequence " signature " used by " (count group) " notes"))))

(defn- stale-index-problems
  [context root]
  (let [path (str root "/index.md")
        fresh (index/index-content context)]
    (when (or (not (fs/exists? path)) (not= fresh (fs/read-text path)))
      [(problem :stale-index
                "index.md"
                "index.md is missing or out of date")])))

(defn- missing-scaffold-problems
  [root]
  (for [name scaffold/file-names
        :when (not (fs/exists? (str root "/" name)))]
    (problem :missing-scaffold name "missing scaffold file")))

(defn- apply-fixes
  "Regenerate a stale index and recreate missing scaffold files. Returns
  the basenames fixed."
  [context problems]
  (let [checks (set (map :check problems))
        regenerated (when (contains? checks :stale-index)
                      (index/regenerate-index context)
                      ["index.md"])
        created (when (contains? checks :missing-scaffold)
                  (map basename (:created (scaffold/scaffold context))))]
    (vec (distinct (concat regenerated created)))))

(defn lint
  "Run every mechanical check over the silo in CONTEXT. Returns
  {:problems [{:check KW :path STR :detail STR}] :fixed [BASENAMES]};
  with {:fix? true} the stale index and missing scaffold files are
  repaired."
  [context {:keys [fix?]}]
  (let [root (fs/canonical (get-in context [:silo :path]))
        config (:config context)
        scheme (get-in config [:sequence :scheme] :numeric)
        notes (search/list-notes context {} {})
        texts (into {}
                    (keep (fn [{:keys [path]}]
                            (when (file-type/text-file? path)
                              [path (fs/read-text path)])))
                    notes)
        ids (set (keep #(get-in % [:filename :identifier]) notes))
        problems (vec (sort-by (juxt :check :path)
                               (concat (non-denote-file-problems root config)
                                       (broken-link-problems notes texts ids)
                                       (source-problems notes texts)
                                       (suspicious-link-problems notes texts)
                                       (orphan-problems notes texts)
                                       (sequence-problems notes scheme)
                                       (duplicate-sequence-problems notes)
                                       (stale-index-problems context root)
                                       (missing-scaffold-problems root))))]
    {:problems problems, :fixed (if fix? (apply-fixes context problems) [])}))
