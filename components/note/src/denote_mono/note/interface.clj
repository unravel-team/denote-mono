(ns denote-mono.note.interface
  "Plan and create new Denote notes."
  (:require [denote-mono.note.core :as core]))

(defn plan-new [changes context opts] (core/plan-new changes context opts))

(defn create [plan opts] (core/create plan opts))
