(ns denote-mono.llm.interface
  "Provider-agnostic LLM completion and tool-calling loop."
  (:require [denote-mono.llm.core :as core]))

(defn make-complete-fn [llm-config] (core/make-complete-fn llm-config))

(defn run-tool-loop [complete-fn opts] (core/run-tool-loop complete-fn opts))
