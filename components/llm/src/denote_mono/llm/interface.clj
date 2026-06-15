(ns denote-mono.llm.interface
  "Provider-agnostic LLM access over DSCloj: provider registration, an
  agentic tool-calling loop (ReAct), and chain-of-thought."
  (:require [denote-mono.llm.core :as core]))

(defn register-provider!
  "Register LLM-CONFIG and return the provider key for the other fns."
  [llm-config]
  (core/register-provider! llm-config))

(defn run-tool-loop [provider opts] (core/run-tool-loop provider opts))

(defn chain-of-thought
  [provider module input-map opts]
  (core/chain-of-thought provider module input-map opts))
