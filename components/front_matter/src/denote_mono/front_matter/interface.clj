(ns denote-mono.front-matter.interface
  "Format, parse, and rewrite Denote front matter as data."
  (:refer-clojure :exclude [format])
  (:require [denote-mono.front-matter.core :as core]))

(defn format [type metadata opts] (core/format type metadata opts))

(defn format-date [date type opts] (core/format-date date type opts))

(defn parse [type content opts] (core/parse type content opts))

(defn has-front-matter?
  [type content opts]
  (core/has-front-matter? type content opts))

(defn plan-rewrite
  [type content new-meta opts]
  (core/plan-rewrite type content new-meta opts))

(defn apply-rewrite [content plan] (core/apply-rewrite content plan))
