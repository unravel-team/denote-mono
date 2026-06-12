(ns denote-mono.slug.core
  "Sluggification rules ported from denote.el (denote-sluggify-* and helpers)."
  (:require [clojure.string :as str]))

(defn keep-only-ascii
  "Replace all non-printable-ASCII characters in S with spaces.
  Iterates code points, not chars, so astral-plane characters (emoji)
  become a single space."
  [s]
  (let [sb (StringBuilder.)]
    (.forEach (.codePoints ^String s)
              (reify
                java.util.function.IntConsumer
                  (accept [_ cp]
                    (.appendCodePoint sb (int (if (<= 33 cp 126) cp 32))))))
    (str sb)))

(defn hyphenate
  "Replace spaces and underscores with hyphens, collapse repeats, trim ends."
  [s]
  (-> s
      (str/replace #"_|\s+" "-")
      (str/replace #"-{2,}" "-")
      (str/replace #"^-|-$" "")))

(defn put-equals
  "Replace spaces and underscores with equals signs, collapse repeats, trim ends."
  [s]
  (-> s
      (str/replace #"_|\s+" "=")
      (str/replace #"={2,}" "=")
      (str/replace #"^=|=$" "")))

;; Punctuation classes mirror denote-sluggify-title/-signature/-keyword.
(def ^:private title-punctuation #"[\[\]{}!@#$%^&*()+'\"?,.|;:~`‘’“”/=]")
(def ^:private signature-punctuation #"[\[\]{}!@#$%^&*()+'\"?,.|;:~`‘’“”/-]")
(def ^:private keyword-punctuation #"[\[\]{}!@#$%^&*()+'\"?,.|;:~`‘’“”/_ =-]")

(defn slug-title
  [s]
  (-> s
      (str/replace title-punctuation "")
      hyphenate
      str/lower-case))

(defn slug-signature
  [s]
  (-> s
      (str/replace signature-punctuation "")
      put-equals
      str/lower-case))

(defn slug-keyword
  [s]
  (-> s
      (str/replace keyword-punctuation "")
      str/lower-case))

(defn- valid-identifier
  "Remove query prefixes, square brackets, and parentheses from IDENTIFIER."
  [identifier]
  (-> identifier
      (str/replace "query-filenames:" "")
      (str/replace "query-contents:" "")
      (str/replace #"[\[\]()]" "")))

(defn- remove-dot-characters [s] (str/replace s "." ""))

(defn- replace-consecutive-token-characters
  "Collapse runs of @, =, _ (and - except for titles) into a single character."
  [s component]
  (let [s (-> s
              (str/replace #"@{2,}" "@")
              (str/replace #"={2,}" "=")
              (str/replace #"_{2,}" "_"))]
    (if (= component :title) s (str/replace s #"-{2,}" "-"))))

(defn- trim-right-token-characters
  "Trim trailing token characters; titles keep their trailing hyphens."
  [s component]
  (if (= component :title)
    (str/replace s #"[=@_]+$" "")
    (str/replace s #"[=@_-]+$" "")))

(defn slug-component
  "Apply COMPONENT-specific slug function plus the file-naming scheme rules.
  COMPONENT is :title, :signature, :keyword, or :identifier. OPTS may provide
  {:slug-functions {component fn}} to override the default slug function."
  [component s opts]
  (let [slug-fn (get-in opts [:slug-functions component])
        slugged (case component
                  :title ((or slug-fn slug-title) s)
                  :keyword (str/replace ((or slug-fn slug-keyword) s) "_" "")
                  :identifier (valid-identifier ((or slug-fn identity) s))
                  :signature ((or slug-fn slug-signature) s))]
    (-> slugged
        remove-dot-characters
        (replace-consecutive-token-characters component)
        (trim-right-token-characters component))))

(defn slug-keywords
  [keywords opts]
  (mapv #(slug-component :keyword % opts) keywords))
