(ns denote-mono.editor.interface
  "Open files in the user's editor."
  (:require [denote-mono.editor.core :as core]))

(defn shellwords [s] (core/shellwords s))

(defn editor-command [env config] (core/editor-command env config))

(defn open [files opts] (core/open files opts))
