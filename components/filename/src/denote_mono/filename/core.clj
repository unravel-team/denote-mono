(ns denote-mono.filename.core
  "Denote filename grammar: parse, format, validate.
  Regexes ported from denote.el (denote-date-identifier-regexp and friends)."
  (:require [clojure.string :as str]
            [denote-mono.slug.interface :as slug])
  (:import (java.util.regex Pattern)))

(def date-identifier-regexp #"[0-9]{8}T[0-9]{6}")

(def ^:private leading-date-identifier-regexp #"\A[0-9]{8}T[0-9]{6}")

(def identifier-regexp #"@@([^.]+?)(==.*|--.*|__.*|@@.*|\..*)*$")

(def signature-regexp #"==([^.]+?)(==.*|--.*|__.*|@@.*|\..*)*$")

(def title-regexp #"--([^.]+?)(==.*|__.*|@@.*|\..*)*$")

(def keywords-regexp #"__([^.]+?)(==.*|--.*|__.*|@@.*|\..*)*$")

(def encryption-extensions [".gpg" ".age"])

(def default-components-order [:identifier :signature :title :keywords])

(defn basename
  [path]
  (let [i (.lastIndexOf ^String path "/")]
    (if (neg? i) path (subs path (inc i)))))

(defn date-identifier? [s] (boolean (re-matches date-identifier-regexp s)))

(defn- outer-extension
  "Extension of BASE with dot, empty string when none. A leading dot does
  not start an extension (dotfiles)."
  [base]
  (let [i (.lastIndexOf ^String base ".")] (if (pos? i) (subs base i) "")))

(defn extension
  "Return extension of PATH with dot included, keeping encryption suffixes,
  e.g. \".org.gpg\"."
  [path]
  (let [base (basename path)
        outer (outer-extension base)]
    (if-let [inner (and (some #{outer} encryption-extensions)
                        (not-empty
                          (outer-extension
                            (subs base 0 (- (count base) (count outer))))))]
      (str inner outer)
      outer)))

(defn encryption-suffix
  [path]
  (some #{(outer-extension (basename path))} encryption-extensions))

(defn base-extension
  "Return extension of PATH without any encryption suffix."
  [path]
  (let [ext (extension path)]
    (if-let [enc (some #(when (str/ends-with? ext %) %) encryption-extensions)]
      (subs ext 0 (- (count ext) (count enc)))
      ext)))

(defn- first-group
  "Return group 1 of the first PATTERN match in S, else nil."
  [pattern s]
  (when-let [m (re-find pattern s)] (second m)))

(defn retrieve-identifier
  [path]
  (let [base (basename path)]
    (or (re-find leading-date-identifier-regexp base)
        (first-group identifier-regexp base))))

(defn retrieve-signature [path] (first-group signature-regexp (basename path)))

(defn retrieve-title [path] (first-group title-regexp (basename path)))

(defn retrieve-keywords [path] (first-group keywords-regexp (basename path)))

(defn- strip-first-group
  "Remove the text of group 1 of the first PATTERN match from S."
  [s pattern]
  (let [m (re-matcher pattern s)]
    (if (and (.find m) (.group m 1))
      (str (subs s 0 (.start m 1)) (subs s (.end m 1)))
      s)))

(defn- strip-component-pattern
  [marker value]
  (re-pattern (str "(" marker (Pattern/quote value) ").*$")))

(defn- valid-name-with-components?
  "Validity check on BASE given already-extracted component values: strip
  each component and accept when only an optional extension remains."
  [base {:keys [title keywords signature identifier]}]
  (let [identifier-marker (if (str/includes? base "@@") "@@" "")
        remainder (reduce (fn [s [marker value]]
                            (if value
                              (strip-first-group
                                s
                                (strip-component-pattern marker value))
                              s))
                    base
                    [["--" title] ["__" keywords] ["==" signature]
                     [identifier-marker identifier]])]
    (and (not (str/starts-with? base "."))
         (or (str/blank? remainder) (str/starts-with? remainder ".")))))

(defn valid-denote-filename?
  "True when PATH respects the Denote file-naming scheme: removing all
  recognized components leaves nothing but an optional extension."
  [path]
  (let [base (basename path)]
    (valid-name-with-components? base
                                 {:title (retrieve-title base),
                                  :keywords (retrieve-keywords base),
                                  :signature (retrieve-signature base),
                                  :identifier (retrieve-identifier base)})))

(defn keywords-combine [keywords] (str/join "_" keywords))

(defn- normalize-components-order
  "Normalize ORDER like Denote's seq-union: requested components first,
  duplicates dropped, missing standard components appended."
  [order]
  (vec (distinct (concat order default-components-order))))

(defn format-filename
  "Format a Denote file name from COMPONENTS, mirroring
  denote-format-file-name. COMPONENTS is a map with :directory, :identifier,
  :signature, :title, :keywords, and :extension."
  [{:keys [directory identifier signature title keywords extension]} opts]
  (cond (nil? directory) (throw (ex-info "directory must not be nil" {}))
        (str/blank? directory) (throw (ex-info "directory must not be empty"
                                               {}))
        (not (str/ends-with? directory "/"))
          (throw (ex-info "directory must end with /" {:directory directory})))
  (let [sort-keywords? (get opts :sort-keywords? true)
        keywords (cond-> keywords sort-keywords? sort)
        components (normalize-components-order (get opts :components-order []))
        file-name
          (reduce (fn [acc component]
                    (case component
                      :identifier
                        (if (and identifier (not (str/blank? identifier)))
                          (str
                            acc
                            "@@"
                            (slug/slug-component :identifier identifier opts))
                          acc)
                      :title
                        (if (and title (not (str/blank? title)))
                          (str acc "--" (slug/slug-component :title title opts))
                          acc)
                      :keywords (if (seq keywords)
                                  (str acc
                                       "__"
                                       (keywords-combine
                                         (slug/slug-keywords keywords opts)))
                                  acc)
                      :signature
                        (if (and signature (not (str/blank? signature)))
                          (str acc
                               "=="
                               (slug/slug-component :signature signature opts))
                          acc)))
            ""
            components)]
    (when (str/blank? file-name)
      (throw (ex-info "There should be at least one file name component" {})))
    (let [file-name (str file-name extension)
          file-name (if (and (not (get opts :identifier-delimiter-always?))
                             (str/starts-with? file-name "@@")
                             (date-identifier? (or identifier "")))
                      (subs file-name 2)
                      file-name)]
      (str directory file-name))))

(defn- component-positions
  "Vector of present components ordered by their marker position in BASE.
  Positions come from the already-extracted component values: each marker
  plus its value is an exact substring of BASE."
  [base {:keys [identifier signature title keywords]}]
  (let [identifier-position (when identifier
                              (if (re-find leading-date-identifier-regexp base)
                                0
                                (str/index-of base (str "@@" identifier))))
        entries (cond-> []
                  identifier (conj [:identifier identifier-position])
                  signature (conj [:signature
                                   (str/index-of base (str "==" signature))])
                  title (conj [:title (str/index-of base (str "--" title))])
                  keywords (conj [:keywords
                                  (str/index-of base (str "__" keywords))]))]
    (->> entries
         (filter (comp some? second))
         (sort-by second)
         (mapv first))))

(defn parse
  "Parse PATH into a structured map describing its Denote components."
  [path _opts]
  (let [base (basename path)
        dir-end (- (count path) (count base))
        directory (when (pos? dir-end) (subs path 0 dir-end))
        ext (extension base)
        stem (subs base 0 (- (count base) (count ext)))
        identifier (retrieve-identifier base)
        signature (retrieve-signature base)
        title (retrieve-title base)
        keywords-string (retrieve-keywords base)
        components {:identifier identifier,
                    :signature signature,
                    :title title,
                    :keywords keywords-string}]
    {:path path,
     :directory directory,
     :basename base,
     :stem stem,
     :extension ext,
     :extension/base (base-extension base),
     :encryption-suffix (encryption-suffix base),
     :identifier identifier,
     :identifier/date? (when identifier (date-identifier? identifier)),
     :signature signature,
     :title title,
     :keywords (when keywords-string (vec (str/split keywords-string #"_"))),
     :components-order (component-positions base components),
     :valid-denote-name? (valid-name-with-components? base components)}))
