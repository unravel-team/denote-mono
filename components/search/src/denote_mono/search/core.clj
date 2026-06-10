(ns denote-mono.search.core
  "List, filter, and sort Denote notes inside a resolved silo."
  (:require [clojure.string :as str]
            [denote-mono.file-type.interface :as file-type]
            [denote-mono.filename.interface :as filename]
            [denote-mono.filesystem.interface :as fs]
            [denote-mono.process.interface :as process]))

(defn- note-record
  [path root-canonical silo-name config]
  (let [parsed (filename/parse path)]
    (when (:valid-denote-name? parsed)
      {:path path,
       :relative-path (subs path (inc (count root-canonical))),
       :silo silo-name,
       :filename parsed,
       :file-type (file-type/detect path nil (:filename config)),
       :mtime (fs/file-mtime path)})))

(defn- compile-filters
  "Compile regex filter strings once so per-note matching reuses Patterns,
  and lowercase the free-text query."
  [{:keys [match title query], :as filters}]
  (cond-> filters
    match (assoc :match (re-pattern match))
    title (assoc :title (re-pattern title))
    query (assoc :query (str/lower-case query))))

(defn- matches-filters?
  [{:keys [filename relative-path]}
   {:keys [match keyword signature title id query]}]
  (and (or (nil? match) (re-find match (:basename filename)))
       (or (nil? keyword) (some #{keyword} (:keywords filename)))
       (or (nil? signature) (= signature (:signature filename)))
       (or (nil? title)
           (and (:title filename) (re-find title (:title filename))))
       (or (nil? id) (= id (:identifier filename)))
       (or (nil? query) (str/includes? (str/lower-case relative-path) query))))

(defn list-notes
  "Return note records for all valid Denote files in the context's silo,
  filtered by FILTERS (:match :keyword :signature :title :id :query)."
  [{:keys [silo config]} filters _opts]
  (let [root (fs/canonical (:path silo))
        files (fs/list-files [root] (get config :files {}))
        filters (compile-filters filters)]
    (into []
          (comp (keep #(note-record % root (:name silo) config))
                (filter #(matches-filters? % filters)))
          files)))

(defn note->wire
  "Shape a note record for JSON/EDN output: stringify the mtime instant and
  the silo keyword."
  [note]
  (-> note
      (update :mtime str)
      (update :silo
              #(some-> %
                       name))))

(defn- text-notes
  [context]
  (filterv #(file-type/text-file? (:path %)) (list-notes context {} {})))

(defn- grep-with-clojure
  [notes pattern]
  (vec (for [{:keys [path relative-path]} notes
             [line-number line]
               (map-indexed vector (str/split-lines (fs/read-text path)))
             :when (re-find pattern line)]
         {:path path,
          :relative-path relative-path,
          :line-number (inc line-number),
          :line line})))

(defn- grep-with-rg
  [notes query rg-argv]
  (let [{:keys [exit out error]}
          (process/run (into (vec rg-argv)
                             (concat ["--line-number" "--no-heading" "--" query]
                                     (map :path notes)))
                       {})]
    (when (and (not error) (#{0 1} exit))
      (let [by-path (into {} (map (juxt :path identity)) notes)]
        (vec (for [match-line (str/split-lines out)
                   :when (not (str/blank? match-line))
                   :let [[path line-number line] (str/split match-line #":" 3)
                         note (by-path path)]
                   :when note]
               {:path path,
                :relative-path (:relative-path note),
                :line-number (parse-long line-number),
                :line line}))))))

(defn grep
  "Search note contents for QUERY (a regex string). Uses rg when available,
  with a pure Clojure fallback. Only supported text files are read."
  [{:keys [config], :as context} query _opts]
  (let [notes (text-notes context)
        rg-argv (get-in config [:tools :rg] ["rg"])]
    (or (when (and (seq notes) (process/available? (first rg-argv)))
          (grep-with-rg notes query rg-argv))
        (grep-with-clojure notes (re-pattern query)))))

(def ^:private link-id-pattern #"denote:([0-9]{8}T[0-9]{6})")

(defn links
  "Identifiers linked from FILE's contents, resolved to note records when
  the target exists in the silo."
  [context file _opts]
  (let [ids (distinct (map second (re-seq link-id-pattern (fs/read-text file))))
        by-id (into {}
                    (keep (fn [note]
                            (when-let [id (get-in note [:filename :identifier])]
                              [id note])))
                    (list-notes context {} {}))]
    {:identifiers (vec ids), :notes (vec (keep by-id ids))}))

(defn backlinks
  "Notes whose contents link to ID via denote link syntax, excluding the
  note that carries ID itself."
  [context id _opts]
  (vec (for [{:keys [path], :as note} (text-notes context)
             :when (not= id (get-in note [:filename :identifier]))
             :when (str/includes? (fs/read-text path) (str "denote:" id))]
         note)))

(defn- nils-last [value] [(if (nil? value) 1 0) value])

(defn sort-notes
  "Sort NOTES by SORT-KEY: :identifier, :title, :keywords, :signature,
  :modified, or :random. Notes missing the component sort last."
  [notes sort-key _opts]
  (case sort-key
    :identifier (sort-by #(nils-last (get-in % [:filename :identifier])) notes)
    :title (sort-by #(nils-last (get-in % [:filename :title])) notes)
    :keywords (sort-by #(nils-last (some->> (get-in % [:filename :keywords])
                                            (filename/keywords-combine)))
                       notes)
    :signature (sort-by #(nils-last (get-in % [:filename :signature])) notes)
    :modified (sort-by :mtime notes)
    :random (shuffle notes)
    notes))
