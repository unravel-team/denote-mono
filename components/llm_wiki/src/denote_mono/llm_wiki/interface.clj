(ns denote-mono.llm-wiki.interface
  "LLM-maintained wiki silos: scaffolding, index and log upkeep, agentic
  tools, and lint checks."
  (:require [denote-mono.llm-wiki.core :as core]
            [denote-mono.llm-wiki.index :as index]
            [denote-mono.llm-wiki.lint :as lint]
            [denote-mono.llm-wiki.scaffold :as scaffold]
            [denote-mono.llm-wiki.tools :as tools]))

(defn scaffold [context] (scaffold/scaffold context))

(defn regenerate-index [context] (index/regenerate-index context))

(defn append-log [context entry] (scaffold/append-log context entry))

(defn ingest-history
  [context source-path]
  (scaffold/ingest-history context source-path))

(defn lint [context opts] (lint/lint context opts))

(defn tool-schemas [mode] (tools/tool-schemas mode))

(defn make-execute-tool [context state] (tools/make-execute-tool context state))

(defn ingest [context source-path opts] (core/ingest context source-path opts))

(defn query [context question opts] (core/query context question opts))

(defn deep-lint [context opts] (core/deep-lint context opts))
