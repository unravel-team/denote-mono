(ns denote-mono.search.core
  "List, filter, and sort Denote notes inside a resolved silo."
  (:require [clojure.string :as str]
            [denote-mono.file-type.interface :as file-type]
            [denote-mono.filename.interface :as filename]
            [denote-mono.filesystem.interface :as fs]))

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
