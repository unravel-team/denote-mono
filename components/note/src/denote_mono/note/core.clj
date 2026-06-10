(ns denote-mono.note.core
  "Create new notes: unique date identifier, Denote filename, and front
  matter, mirroring denote's creation contract (spec section 8.1)."
  (:require [clojure.string :as str]
            [denote-mono.file-type.interface :as file-type]
            [denote-mono.filename.interface :as filename]
            [denote-mono.filesystem.interface :as fs]
            [denote-mono.front-matter.interface :as front-matter])
  (:import (java.time LocalDateTime)
           (java.time.format DateTimeFormatter)))

(def ^:private id-pattern "yyyyMMdd'T'HHmmss")

(def ^:private id-formatter (DateTimeFormatter/ofPattern id-pattern))

(defn- existing-identifiers
  [root files-opts]
  (into #{}
        (keep #(filename/extract % :identifier))
        (fs/list-files [root] files-opts)))

(defn- unique-identifier
  "Date identifier from DATE (or now), incremented by seconds until it is
  not in USED, matching Denote's date-ID uniqueness behavior."
  [date used]
  (let [start
          (if date
            (LocalDateTime/parse
              (front-matter/format-date date :text {:date-format id-pattern})
              id-formatter)
            (LocalDateTime/now))]
    (loop [moment start]
      (let [id (.format moment id-formatter)]
        (if (used id) (recur (.plusSeconds moment 1)) id)))))

(defn plan-new
  "Plan a new note. CHANGES holds :title, :keywords, :signature,
  :identifier, :date, :type, and :subdir. Returns {:path :content :type}."
  [changes {:keys [silo config]} _opts]
  (let [root (fs/canonical (:path silo))
        directory (if-let [subdir (:subdir changes)]
                    (fs/canonical (str root "/" subdir))
                    root)]
    ;; [ref:silo_path_containment]
    (when-not (fs/inside-root? root directory)
      (throw (ex-info (str "Subdirectory escapes silo: " (:subdir changes))
                      {:type :validation})))
    (let [filename-opts (get config :filename {})
          type (or (:type changes) (:file-type filename-opts) :org)
          extension (or (file-type/extension-for type)
                        (throw (ex-info (str "Unknown file type: " type)
                                        {:type :validation})))
          identifier (or (:identifier changes)
                         (unique-identifier
                           (:date changes)
                           (existing-identifiers root (get config :files {}))))
          path (filename/format-filename {:directory (str directory "/"),
                                          :identifier identifier,
                                          :signature (:signature changes),
                                          :title (:title changes),
                                          :keywords (:keywords changes),
                                          :extension extension}
                                         filename-opts)
          metadata {:title (or (:title changes) ""),
                    :date (or (:date changes) identifier),
                    :keywords (:keywords changes),
                    :identifier identifier,
                    :signature (or (:signature changes) "")}
          content (str (front-matter/format type
                                            metadata
                                            (get config :front-matter {}))
                       (when-let [template (:template changes)] template))]
      {:path path, :content content, :type type, :identifier identifier})))

(defn create
  "Write the planned note to disk. An existing non-empty destination
  aborts; an existing empty file is reused only with {:reuse-empty? true}."
  [{:keys [path content], :as plan} opts]
  (when (fs/exists? path)
    (if (str/blank? (fs/read-text path))
      (when-not (:reuse-empty? opts)
        (throw (ex-info (str "Empty file exists (use --reuse-empty): " path)
                        {:type :collision, :path path})))
      (throw (ex-info (str "File exists: " path)
                      {:type :collision, :path path}))))
  (fs/write-text path content)
  (assoc plan :created true))
