(ns denote-mono.filesystem.interface
  "Path walking, text IO, and collision-safe rename operations."
  (:require [denote-mono.filesystem.core :as core]))

(defn list-files [roots opts] (core/list-files roots opts))

(defn read-text [path] (core/read-text path))

(defn write-text [path content] (core/write-text path content))

(defn rename-file [source dest opts] (core/rename-file source dest opts))

(defn file-mtime [path] (core/file-mtime path))

(defn writable? [path] (core/writable? path))

(defn exists? [path] (core/exists? path))

(defn directory? [path] (core/directory? path))

(defn canonical [path] (core/canonical path))

(defn inside-root? [root path] (core/inside-root? root path))

(defn backup-file? [path] (core/backup-file? path))
