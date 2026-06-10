(ns denote-mono.front-matter.core
  "Format, parse, and rewrite Denote front matter. Ported from
  denote--format-front-matter and denote-rewrite-front-matter. Rewrites are
  planned as data and applied line-preservingly: only known component lines
  are added, removed, or modified."
  (:refer-clojure :exclude [format])
  (:require [clojure.string :as str]
            [denote-mono.file-type.interface :as file-type]
            [denote-mono.slug.interface :as slug])
  (:import (java.time LocalDate LocalDateTime ZoneId)
           (java.time.format DateTimeFormatter)))

;; Raw templates, byte-for-byte from denote-org-front-matter and friends.
;; Format arguments: title, date, keywords, identifier, signature.
(def ^:private templates
  {:org (str "#+title:      %s\n#+date:       %s\n#+filetags:   %s\n"
             "#+identifier: %s\n#+signature:  %s\n\n"),
   :markdown-yaml (str "---\ntitle:      %s\ndate:       %s\ntags:       %s\n"
                       "identifier: %s\nsignature:  %s\n---\n\n"),
   :markdown-toml (str
                    "+++\ntitle      = %s\ndate       = %s\ntags       = %s\n"
                    "identifier = %s\nsignature  = %s\n+++\n\n"),
   :text (str
           "title:      %s\ndate:       %s\ntags:       %s\n"
           "identifier: %s\nsignature:  %s\n---------------------------\n\n")})

(def ^:private template-components
  [:title :date :keywords :identifier :signature])

(def ^:private default-present-even-if-empty
  [:title :keywords :date :identifier])

;; Per-type value formatting, mirroring the :*-value-function entries of
;; denote-file-types.
(defn- quote-string [s] (pr-str (or s "")))

(defn- format-value
  [type component value]
  (let [md? (#{:markdown-yaml :markdown-toml} type)]
    (case component
      :keywords (case type
                  :org (if (seq value) (str ":" (str/join ":" value) ":") "")
                  :text (str/join "  " value)
                  (str "[" (str/join ", " (map quote-string value)) "]"))
      (:title :identifier :signature) (if md? (quote-string value) (str value))
      :date (str value))))

(defn- parse-date
  "Parse a date STRING in Denote identifier, date-time, or date form."
  [s]
  (when-not (str/blank? (str s))
    (let [s (str s)]
      (or (some #(try (LocalDateTime/parse s (DateTimeFormatter/ofPattern %))
                      (catch Exception _ nil))
                ["yyyyMMdd'T'HHmmss" "yyyy-MM-dd HH:mm:ss"
                 "yyyy-MM-dd'T'HH:mm:ss"])
          (try (.atStartOfDay (LocalDate/parse s)) (catch Exception _ nil))))))

(defn format-date
  "Format DATE (a date string or LocalDateTime) for TYPE. OPTS :date-format
  (a Java DateTimeFormatter pattern) overrides the per-type default."
  [date type opts]
  (if-let [parsed (if (instance? LocalDateTime date) date (parse-date date))]
    (let [pattern (or (:date-format opts)
                      (case (:date-format (file-type/properties type))
                        :org-timestamp "'['yyyy-MM-dd EEE HH:mm']'"
                        :rfc3339 "yyyy-MM-dd'T'HH:mm:ssxxx"
                        "yyyy-MM-dd"))]
      ;; Format through a zoned date-time so offset patterns (RFC3339)
      ;; work; zone-free patterns are unaffected.
      (.format (.atZone ^LocalDateTime parsed (ZoneId/systemDefault))
               (DateTimeFormatter/ofPattern pattern)))
    ""))

(defn- has-value?
  [component value]
  (if (= component :keywords)
    (boolean (seq value))
    (not (str/blank? (str value)))))

(defn- normalized-values
  "Apply Denote's normalization before formatting: keywords and signature
  are sluggified, everything else passes through."
  [{:keys [title date keywords identifier signature]} type opts]
  {:title (or title ""),
   :date (format-date date type opts),
   :keywords (slug/slug-keywords (or keywords [])),
   :identifier (or identifier ""),
   :signature (if (str/blank? (str signature))
                ""
                (slug/slug-component :signature signature))})

(defn- key-regexp
  [type component]
  (get (file-type/properties type)
       (case component
         :title :title-key-regexp
         :date :date-key-regexp
         :keywords :keywords-key-regexp
         :identifier :identifier-key-regexp
         :signature :signature-key-regexp)))

(defn- component-of-line
  [type line]
  (some #(when (re-find (key-regexp type %) line) %) template-components))

(defn format
  "Front matter string for TYPE from METADATA ({:title :date :keywords
  :identifier :signature}). Lines whose component has no value are dropped
  unless listed in OPTS :present-even-if-empty (default: title, keywords,
  date, identifier — not signature), matching Denote."
  [type metadata opts]
  (let [values (normalized-values metadata type opts)
        rendered (clojure.core/format
                   (templates type)
                   (format-value type :title (:title values))
                   (:date values)
                   (format-value type :keywords (:keywords values))
                   (format-value type :identifier (:identifier values))
                   (format-value type :signature (:signature values)))
        keep-empty
          (set (get opts :present-even-if-empty default-present-even-if-empty))
        drop? (fn [line]
                (when-let [component (component-of-line type line)]
                  (and (not (has-value? component (get metadata component)))
                       (not (keep-empty component)))))]
    (->> (str/split rendered #"\n" -1)
         (remove drop?)
         (str/join "\n"))))

;; Line formats derived from the templates, used to build replacement lines.
(def ^:private line-formats
  (into {}
        (for [[type template] templates]
          [type
           (into {}
                 (keep (fn [line]
                         (when-let [component (component-of-line type line)]
                           [component line])))
                 (str/split template #"\n"))])))

(defn- component-line
  [type component values]
  (clojure.core/format (get-in line-formats [type component])
                       (if (= component :date)
                         (:date values)
                         (format-value type component (get values component)))))

(defn- find-line
  "Index and text of the first line in LINES matching COMPONENT's key."
  [type component lines]
  (let [pattern (key-regexp type component)]
    (some (fn [[i line]] (when (re-find pattern line) [i line]))
          (map-indexed vector lines))))

(defn parse
  "Extract metadata from CONTENT's front matter lines. Returns a map with
  only the components found; {} when no front matter is present."
  [type content _opts]
  (let [lines (str/split content #"\n")
        md? (#{:markdown-yaml :markdown-toml} type)
        raw-value (fn [component]
                    (when-let [[_ line] (find-line type component lines)]
                      (let [m (re-matcher (key-regexp type component) line)]
                        (when (.find m) (str/trim (subs line (.end m)))))))
        unquote-value (fn [s] (if md? (str/replace s #"^[\"']+|[\"']+$" "") s))
        extract-keywords
          (fn [s]
            (->> (str/split s #"[:,\s]+")
                 (map #(str/replace % #"^[\]\[ \"']+|[\]\[ \"']+$" ""))
                 (remove str/blank?)
                 vec))]
    (into {}
          (keep (fn [component]
                  (when-let [value (raw-value component)]
                    [component
                     (case component
                       :keywords (extract-keywords value)
                       :date value
                       (unquote-value value))])))
          template-components)))

(defn has-front-matter?
  [type content _opts]
  (boolean (some #(re-find (key-regexp type %) content) template-components)))

(defn plan-rewrite
  "Plan a front-matter rewrite of CONTENT to NEW-META for TYPE. OPTS:
  :mode (:sync :update-existing :add :none), :date-format,
  :present-even-if-empty. Returns {:type :mode :actions [...]} where each
  action is {:component :action :anchor :old-line :new-line}; :prepend
  carries a full front-matter block for :add on contentless files."
  [type content new-meta {:keys [mode], :as opts}]
  (let [mode (or mode :sync)]
    (if (= mode :none)
      {:type type, :mode mode, :actions []}
      (let [lines (str/split content #"\n" -1)
            values (normalized-values new-meta type opts)
            keep-empty (set (get opts
                                 :present-even-if-empty
                                 default-present-even-if-empty))
            in-file (set (filter #(find-line type % lines)
                           template-components))]
        (if (and (empty? in-file) (#{:add} mode))
          {:type type,
           :mode mode,
           :actions [],
           :prepend (format type new-meta opts)}
          (let [actions
                  (vec
                    (keep
                      (fn [component]
                        (let [present? (in-file component)
                              value? (has-value? component
                                                 (get new-meta component))
                              new-line (component-line type component values)
                              old-line (second
                                         (find-line type component lines))]
                          (cond (and (not present?) value? (#{:sync :add} mode))
                                  {:component component,
                                   :action :add,
                                   :new-line new-line}
                                (and present?
                                     (not value?)
                                     (not (keep-empty component))
                                     (#{:sync :add} mode))
                                  {:component component,
                                   :action :remove,
                                   :old-line old-line}
                                (and present?
                                     (not= old-line new-line)
                                     (or (#{:sync :add} mode) value?))
                                  {:component component,
                                   :action :modify,
                                   :old-line old-line,
                                   :new-line new-line})))
                      template-components))]
            ;; No-op unless the file already shares a line with the
            ;; template.
            {:type type,
             :mode mode,
             :actions (if (seq in-file) actions [])}))))))

(defn apply-rewrite
  "Apply a plan from plan-rewrite to CONTENT, preserving all unrelated
  lines byte-for-byte."
  [content {:keys [type actions prepend]}]
  (cond prepend (str prepend content)
        (empty? actions) content
        :else
          (let [lines (str/split content #"\n" -1)
                by-action (group-by :action actions)
                removed-lines (set (map :old-line (:remove by-action)))
                modify-by-old
                  (into {} (map (juxt :old-line :new-line)) (:modify by-action))
                present (set (keep #(component-of-line type %) lines))
                ;; Anchor each added component after the nearest preceding
                ;; template component present in the file.
                adds-after
                  (group-by
                    (fn [{:keys [component]}]
                      (let [idx (.indexOf ^java.util.List template-components
                                          component)]
                        (or (last (filter present
                                    (take idx template-components)))
                            (first (filter present template-components)))))
                    (:add by-action))
                emit (fn [acc line]
                       (cond (removed-lines line) acc
                             :else
                               (let [acc (conj acc
                                               (get modify-by-old line line))
                                     component (component-of-line type line)]
                                 (into acc
                                       (map :new-line
                                         (get adds-after component))))))]
            (str/join "\n" (reduce emit [] lines)))))
