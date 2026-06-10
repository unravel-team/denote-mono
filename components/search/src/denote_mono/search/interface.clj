(ns denote-mono.search.interface
  "List/filter/sort candidate note files."
  (:require [denote-mono.search.core :as core]))

(defn list-notes [context filters opts] (core/list-notes context filters opts))

(defn sort-notes [notes sort-key opts] (core/sort-notes notes sort-key opts))
