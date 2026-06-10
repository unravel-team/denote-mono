(ns denote-mono.file-type.interface
  "Detect supported Denote file types from path and content sample."
  (:require [denote-mono.file-type.core :as core]))

(defn detect
  ([path content] (detect path content {}))
  ([path content opts] (core/detect path content opts)))

(defn supported-extension? [path] (core/supported-extension? path))

(defn text-file? [path] (core/text-file? path))

(defn extension-for [type] (core/extension-for type))

(defn extensions [] (core/extensions))

(defn extensions-with-encryption [] (core/extensions-with-encryption))

(defn properties [type] (core/properties type))
