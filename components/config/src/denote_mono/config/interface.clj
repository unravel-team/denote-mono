(ns denote-mono.config.interface
  "Config load/merge/validation."
  (:require [denote-mono.config.core :as core]))

(defn default-config [] core/default-config)

(defn config-path [env] (core/config-path env))

(defn expand-home [path env] (core/expand-home path env))

(defn load-config [opts] (core/load-config opts))

(defn merge-cli [config cli-opts] (core/merge-cli config cli-opts))

(defn validate [config] (core/validate config))
