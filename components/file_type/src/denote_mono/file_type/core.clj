(ns denote-mono.file-type.core
  "File type registry and detection, ported from denote-file-types and
  denote-file-type in denote.el. Detection is pure: it looks at the path
  and an optional content sample, never the filesystem."
  (:require [clojure.string :as str]
            [denote-mono.filename.interface :as filename]))

;; Registry modeled after denote-file-types. Vector of [type properties]
;; pairs because order matters: the first matching type wins.
(def registry
  [[:org
    {:extension ".org",
     :content-test nil,
     :title-key-regexp #"(?m)^#\+title\s*:",
     :keywords-key-regexp #"(?m)^#\+filetags\s*:",
     :signature-key-regexp #"(?m)^#\+signature\s*:",
     :identifier-key-regexp #"(?m)^#\+identifier\s*:",
     :date-key-regexp #"(?m)^#\+date\s*:",
     :date-format :org-timestamp,
     :link-format "[[denote:%s][%s]]",
     :link-retrieval-format "[denote:%s]",
     :link-in-context-regexp #"\[\[denote:([^\]\[]*?)(?:::.*)?\]\[(.*?)\]\]"}]
   [:markdown-yaml
    {:extension ".md",
     :content-test #(str/starts-with? % "---"),
     :title-key-regexp #"(?m)^title\s*:",
     :keywords-key-regexp #"(?m)^tags\s*:",
     :signature-key-regexp #"(?m)^signature\s*:",
     :identifier-key-regexp #"(?m)^identifier\s*:",
     :date-key-regexp #"(?m)^date\s*:",
     :date-format :rfc3339,
     :link-format "[%2$s](denote:%1$s)",
     :link-retrieval-format "(denote:%s)",
     :link-in-context-regexp #"\[(.*?)\]\(denote:([^\]\[]*?)\)"}]
   [:markdown-toml
    {:extension ".md",
     :content-test #(str/starts-with? % "+++"),
     :title-key-regexp #"(?m)^title\s*=",
     :keywords-key-regexp #"(?m)^tags\s*=",
     :signature-key-regexp #"(?m)^signature\s*=",
     :identifier-key-regexp #"(?m)^identifier\s*=",
     :date-key-regexp #"(?m)^date\s*=",
     :date-format :rfc3339,
     :link-format "[%2$s](denote:%1$s)",
     :link-retrieval-format "(denote:%s)",
     :link-in-context-regexp #"\[(.*?)\]\(denote:([^\]\[]*?)\)"}]
   [:text
    {:extension ".txt",
     :content-test nil,
     :title-key-regexp #"(?m)^title\s*:",
     :keywords-key-regexp #"(?m)^tags\s*:",
     :signature-key-regexp #"(?m)^signature\s*:",
     :identifier-key-regexp #"(?m)^identifier\s*:",
     :date-key-regexp #"(?m)^date\s*:",
     :date-format :iso-8601,
     :link-format "[[denote:%s][%s]]",
     :link-retrieval-format "[denote:%s]",
     :link-in-context-regexp #"\[\[denote:([^\]\[]*?)(?:::.*)?\]\[(.*?)\]\]"}]])

(defn properties
  [type]
  (some (fn [[t props]] (when (= t type) props)) registry))

(defn extension-for [type] (:extension (properties type)))

(defn extensions
  []
  (vec (distinct (map (fn [[_ props]] (:extension props)) registry))))

(defn extensions-with-encryption
  []
  (let [base (extensions)]
    (into base
          (for [ext base enc filename/encryption-extensions] (str ext enc)))))

(defn supported-extension?
  [path]
  (let [ext (filename/extension path)]
    (boolean (and (not (str/blank? ext))
                  (some #{ext} (extensions-with-encryption))))))

(defn text-file?
  "True when PATH has a supported text extension, possibly encrypted."
  [path]
  (supported-extension? path))

(defn- types-with-extension
  [ext]
  (filterv (fn [[_ props]] (= ext (:extension props))) registry))

(defn detect
  "Detect the file type of PATH, optionally using a CONTENT sample to
  disambiguate extensions shared by several types (.md). Mirrors
  denote-file-type: content test first, then the configured
  {:file-type TYPE} when it matches the extension, then the first
  matching registry entry. Returns nil for unsupported extensions."
  [path content opts]
  (when-let [candidates (not-empty (types-with-extension
                                     (filename/base-extension path)))]
    (or (when (and content (> (count candidates) 1))
          (some (fn [[type props]]
                  (when-let [test-fn (:content-test props)]
                    (when (test-fn content) type)))
                candidates))
        (when (and (> (count candidates) 1)
                   (some #{(:file-type opts)} (map first candidates)))
          (:file-type opts))
        (ffirst candidates))))
