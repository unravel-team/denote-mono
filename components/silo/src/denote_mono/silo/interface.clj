(ns denote-mono.silo.interface
  "Resolve configured silo roots and enforce isolation."
  (:require [denote-mono.silo.core :as core]))

(defn all-silos [config env] (core/all-silos config env))

(defn path-for [config silo-name env] (core/path-for config silo-name env))

(defn in-silo? [path silo] (core/in-silo? path silo))

(defn resolve-silo
  [config cli-opts cwd env]
  (core/resolve-silo config cli-opts cwd env))

(defn resolve-llm-wiki-silo
  [config cli-opts cwd env]
  (core/resolve-llm-wiki-silo config cli-opts cwd env))

(defn doctor [config env] (core/doctor config env))
