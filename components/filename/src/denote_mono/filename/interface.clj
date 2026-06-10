(ns denote-mono.filename.interface
  "Pure Denote filename parser, formatter, and validator."
  (:require [denote-mono.filename.core :as core]))

(def encryption-extensions core/encryption-extensions)

(defn date-identifier? [s] (core/date-identifier? s))

(defn parse ([path] (parse path {})) ([path opts] (core/parse path opts)))

(defn valid-denote-filename? [path] (core/valid-denote-filename? path))

(defn format-filename
  ([components] (format-filename components {}))
  ([components opts] (core/format-filename components opts)))

(defn extract
  "Extract COMPONENT (:identifier, :signature, :title, :keywords) from PATH.
  Returns the raw component string, or nil when absent."
  [path component]
  (case component
    :identifier (core/retrieve-identifier path)
    :signature (core/retrieve-signature path)
    :title (core/retrieve-title path)
    :keywords (core/retrieve-keywords path)))

(defn extension [path] (core/extension path))

(defn base-extension [path] (core/base-extension path))

(defn encryption-suffix [path] (core/encryption-suffix path))

(defn keywords-combine [keywords] (core/keywords-combine keywords))
