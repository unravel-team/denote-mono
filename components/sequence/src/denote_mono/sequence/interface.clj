(ns denote-mono.sequence.interface
  "Pure Folgezettel sequence operations over Denote signatures."
  (:require [denote-mono.sequence.core :as core]))

(def schemes core/schemes)

(defn valid? [sequence] (core/valid? sequence))

(defn valid-for-scheme?
  [scheme sequence]
  (core/valid-for-scheme? scheme sequence))

(defn scheme-of
  ([sequence default-scheme] (core/scheme-of sequence default-scheme))
  ([sequence default-scheme partial?]
   (core/scheme-of sequence default-scheme partial?)))

(defn split [sequence] (core/split sequence))

(defn join [scheme parts] (core/join scheme parts))

(defn depth [sequence] (core/depth sequence))

(defn increment-part [part] (core/increment-part part))

(defn decrement-part [part] (core/decrement-part part))

(defn convert [sequence target-scheme] (core/convert sequence target-scheme))

(defn convert-part [part target-scheme] (core/convert-part part target-scheme))

(defn infer-parent
  [sequence default-scheme]
  (core/infer-parent sequence default-scheme))

(defn infer-child
  [sequence default-scheme]
  (core/infer-child sequence default-scheme))

(defn infer-sibling
  [sequence direction default-scheme]
  (core/infer-sibling sequence direction default-scheme))

(defn next-parent [sequences scheme] (core/next-parent sequences scheme))

(defn next-child
  [sequences sequence scheme]
  (core/next-child sequences sequence scheme))

(defn next-sibling
  [sequences sequence scheme]
  (core/next-sibling sequences sequence scheme))

(defn relative
  [sequences sequence type scheme]
  (core/relative sequences sequence type scheme))

(defn sort-sequences [sequences] (core/sort-sequences sequences))

(defn keep-siblings
  [direction sequence sequences]
  (core/keep-siblings direction sequence sequences))

(defn filter-scheme [sequences scheme] (core/filter-scheme sequences scheme))

(defn sequences-with-prefix
  [sequences sequence scheme]
  (core/sequences-with-prefix sequences sequence scheme))

(defn file-sequence [path] (core/file-sequence path))
