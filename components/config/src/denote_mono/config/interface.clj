(ns denote-mono.config.interface
  "Config load/merge/validation."
  (:require [denote-mono.config.core :as core]))

(defn default-config [] core/default-config)

(defn config-path [env] (core/config-path env))

(defn expand-home [path env] (core/expand-home path env))

(defn load-config [opts] (core/load-config opts))

(defn merge-cli [config cli-opts] (core/merge-cli config cli-opts))

(defn init-plan
  "Plan first-run initialization: {:config-path :silo-name :silo-path
  :llm-wiki-path :env} -> {:config-path :config-text :dirs}. Pure."
  [opts]
  (core/init-plan opts))

(defn init-apply!
  "Create an init plan's directories and write its config file."
  [plan]
  (core/init-apply! plan))

(defn validate [config] (core/validate config))
