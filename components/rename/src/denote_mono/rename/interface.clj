(ns denote-mono.rename.interface
  "Build and apply rename plans (data in, data out; apply mutates)."
  (:require [denote-mono.rename.core :as core]))

(defn plan-rename
  [file changes context opts]
  (core/plan-rename file changes context opts))

(defn plan-batch
  [files batch-changes context opts]
  (core/plan-batch files batch-changes context opts))

(defn validate-plan [plan opts] (core/validate-plan plan opts))

(defn apply-plan [plan opts] (core/apply-plan plan opts))

(defn apply-batch [plans opts] (core/apply-batch plans opts))
