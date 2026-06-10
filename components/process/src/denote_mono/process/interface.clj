(ns denote-mono.process.interface
  "Safe external process execution (argv vectors, never shell strings)."
  (:require [denote-mono.process.core :as core]))

(defn available? [executable] (core/available? executable))

(defn run ([argv] (run argv {})) ([argv opts] (core/run argv opts)))

(defn select [candidates argv] (core/select candidates argv))
