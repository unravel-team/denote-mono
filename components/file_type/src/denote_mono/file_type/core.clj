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

(def ^:private type->properties (into {} registry))

(defn properties [type] (type->properties type))

(defn extension-for [type] (:extension (properties type)))

;; Lookup tables derived once from the static registry.
(def ^:private all-extensions
  (vec (distinct (map (fn [[_ props]] (:extension props)) registry))))

(def ^:private all-extensions-with-encryption
  (into all-extensions
        (for [ext all-extensions
              enc filename/encryption-extensions]
          (str ext enc))))

(def ^:private supported-extension-set (set all-extensions-with-encryption))

(def ^:private extension->types
  (group-by (fn [[_ props]] (:extension props)) registry))

(defn extensions [] all-extensions)

(defn extensions-with-encryption [] all-extensions-with-encryption)

(defn supported-extension?
  [path]
  (contains? supported-extension-set (filename/extension path)))

(defn text-file?
  "True when PATH has a supported text extension, possibly encrypted."
  [path]
  (supported-extension? path))

(defn- types-with-extension [ext] (extension->types ext))

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
