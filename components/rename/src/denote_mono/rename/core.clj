(ns denote-mono.rename.core
  "Build and apply rename plans. Plans are data; nothing mutates until
  apply-plan. Modeled on denote--rename-file with CLI safety additions:
  collision preflight and explicit front-matter modes."
  (:require [clojure.string :as str]
            [denote-mono.file-type.interface :as file-type]
            [denote-mono.filename.interface :as filename]
            [denote-mono.filesystem.interface :as fs]
            [denote-mono.front-matter.interface :as front-matter])
  (:import (java.time LocalDateTime ZoneId)))

(def ^:private id-pattern "yyyyMMdd'T'HHmmss")

(defn- mtime->local-date-time
  [path]
  (LocalDateTime/ofInstant (fs/file-mtime path) (ZoneId/systemDefault)))

(defn- generate-identifier
  "Date identifier from CHANGES :date, else the file's mtime."
  [path changes]
  (let [date (or (:date changes) (mtime->local-date-time path))]
    (front-matter/format-date date :text {:date-format id-pattern})))

(defn- merge-component
  "Omitted key keeps CURRENT; explicit empty string/collection removes."
  [changes key current]
  (if (contains? changes key)
    (let [value (get changes key)]
      (cond (and (string? value) (str/blank? value)) nil
            (and (coll? value) (empty? value)) nil
            :else value))
    current))

(defn- new-components
  [parsed changes path]
  (let [identifier (merge-component changes :identifier (:identifier parsed))
        identifier (or identifier (generate-identifier path changes))]
    {:identifier identifier,
     :title (merge-component changes :title (:title parsed)),
     :keywords (merge-component changes :keywords (:keywords parsed)),
     :signature (merge-component changes :signature (:signature parsed))}))

(defn- front-matter-date
  "Date for the front-matter line: explicit :date, else derived from a
  date identifier."
  [identifier changes]
  (or (:date changes)
      (when (and identifier (filename/date-identifier? identifier))
        identifier)))

(defn- plan-content-change
  "Front-matter rewrite plan for the file, or nil when mode is :none, the
  type is unsupported, or nothing changes."
  [path components changes {:keys [config front-matter-mode]}]
  (let [mode (or front-matter-mode
                 (get-in config [:front-matter :rename-mode])
                 :sync)]
    (when (and (not= mode :none) (file-type/supported-extension? path))
      (let [content (fs/read-text path)
            type (file-type/detect path content (:filename config))
            fm-opts (-> (get config :front-matter {})
                        (assoc :mode mode)
                        (dissoc :rename-mode))
            new-meta (assoc components
                       :date (front-matter-date (:identifier components)
                                                changes))
            plan (front-matter/plan-rewrite type content new-meta fm-opts)]
        (when (or (:prepend plan) (seq (:actions plan))) plan)))))

(defn plan-rename
  "Plan renaming FILE with CHANGES inside CONTEXT. CHANGES keys :title,
  :keywords, :signature, :identifier follow keep/replace/remove semantics
  (omitted keeps, empty removes); :date drives generated identifiers and
  the front-matter date line. Returns the plan map of spec section 8.4."
  [file changes {:keys [silo], :as context} opts]
  (let [path (fs/canonical file)]
    (when-not (fs/exists? path)
      (throw (ex-info (str "No such file: " file) {:type :validation})))
    (when (and silo (not (fs/inside-root? (:path silo) path)))
      ;; [ref:silo_path_containment]
      (throw (ex-info (str file " is outside silo " (name (:name silo)))
                      {:type :validation})))
    (let [parsed (filename/parse path)
          components (new-components parsed changes path)
          filename-opts (get-in context [:config :filename] {})
          destination (filename/format-filename (assoc components
                                                  :directory (:directory parsed)
                                                  :extension (:extension
                                                               parsed))
                                                filename-opts)]
      {:source path,
       :destination destination,
       :old (select-keys parsed [:identifier :title :keywords :signature]),
       :new components,
       :content-change (plan-content-change
                         path
                         components
                         changes
                         (assoc (select-keys context [:config])
                           :front-matter-mode (:front-matter opts))),
       :warnings []})))

(defn validate-plan
  "Throw ex-info {:type :collision} when the plan's destination collides
  with an existing file. A plan whose destination equals its source is a
  content-only change and is fine."
  [{:keys [source destination], :as plan} _opts]
  (when (and (not= source destination) (fs/exists? destination))
    (throw (ex-info
             (str "Destination exists: " destination)
             {:type :collision, :source source, :destination destination})))
  plan)

(defn apply-plan
  "Execute a validated plan: rename, then rewrite front matter. Returns the
  plan with :applied true."
  [{:keys [source destination content-change], :as plan} _opts]
  (when (not= source destination) (fs/rename-file source destination {}))
  (when content-change
    (let [content (fs/read-text destination)]
      (fs/write-text destination
                     (front-matter/apply-rewrite content content-change))))
  (assoc plan :applied true))

(defn apply-batch
  "Apply all plans, stopping at the first failure. Returns
  {:applied [...] :failed plan-or-nil :pending [...]}."
  [plans opts]
  (loop [remaining plans
         applied []]
    (if-let [[plan & more] (seq remaining)]
      (let [result (try (apply-plan plan opts)
                        (catch Exception e
                          {:error (ex-message e), :plan plan}))]
        (if (:error result)
          {:applied applied, :failed result, :pending (vec more)}
          (recur more (conj applied result))))
      {:applied applied, :failed nil, :pending []})))
