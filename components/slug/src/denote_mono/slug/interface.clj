(ns denote-mono.slug.interface
  "Pure string normalization for Denote file name components."
  (:require [denote-mono.slug.core :as core]))

(defn keep-only-ascii [s] (core/keep-only-ascii s))

(defn hyphenate [s] (core/hyphenate s))

(defn put-equals [s] (core/put-equals s))

(defn slug-title [s] (core/slug-title s))

(defn slug-signature [s] (core/slug-signature s))

(defn slug-keyword [s] (core/slug-keyword s))

(defn slug-identifier
  ([s] (slug-identifier s {}))
  ([s opts] (core/slug-component :identifier s opts)))

(defn slug-component
  ([component s] (slug-component component s {}))
  ([component s opts] (core/slug-component component s opts)))

(defn slug-keywords
  ([keywords] (slug-keywords keywords {}))
  ([keywords opts] (core/slug-keywords keywords opts)))
