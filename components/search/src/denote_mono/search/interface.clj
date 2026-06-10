(ns denote-mono.search.interface
  "List/filter/sort candidate note files."
  (:require [denote-mono.search.core :as core]))

(defn list-notes [context filters opts] (core/list-notes context filters opts))

(defn sort-notes [notes sort-key opts] (core/sort-notes notes sort-key opts))

(defn note->wire [note] (core/note->wire note))

(defn grep [context query opts] (core/grep context query opts))

(defn links [context file opts] (core/links context file opts))

(defn backlinks [context id opts] (core/backlinks context id opts))
