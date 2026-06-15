(ns denote-mono.llm.core
  "denote-mono's LLM access, implemented over DSCloj. Exposes a tool-using
  agent loop (DSCloj's ReAct) and chain-of-thought, plus provider
  registration from denote-mono config. Callers hand over plain Clojure tool
  executors and OpenAI-style tool schemas; the conversion to DSCloj's tool
  maps lives here."
  (:require [clojure.data.json :as json]
            [dscloj.core :as dscloj]))

(def ^:private provider-key
  "Stable DSCloj registry key for denote-mono's single active provider.
  Re-registered (overwritten) on each operation, which is safe for a
  one-shot CLI."
  :denote-mono)

(defn register-provider!
  "Register LLM-CONFIG with DSCloj and return the provider key to pass to
  run-tool-loop / chain-of-thought. LLM-CONFIG:
  {:provider KW :model STR :api-key STR :api-base STR-or-nil
  :timeout-ms N-or-nil}. The timeout lives in DSCloj's :config map, where
  the providers read it."
  [{:keys [provider model api-key api-base timeout-ms]}]
  (dscloj/register-provider! provider-key
                             {:provider provider,
                              :model model,
                              :config (cond-> {:api-key api-key}
                                        api-base (assoc :api-base api-base)
                                        timeout-ms (assoc :timeout
                                                     timeout-ms))})
  provider-key)

(defn- json-type->spec
  "A Malli spec for an OpenAI JSON-schema type. Only used to label the
  argument's type in the agent's tool catalog, so the mapping is coarse."
  [t]
  (case t
    "string" :string
    "integer" :int
    "number" :double
    "boolean" :boolean
    "array" [:vector :any]
    "object" [:map]
    :string))

(defn- schema->tool
  "Convert an OpenAI function SCHEMA plus the shared EXECUTE-TOOL dispatcher
  into a DSCloj tool map. The handler runs the executor and returns its
  result as a JSON observation string; executor exceptions propagate so
  DSCloj records them as observations the model can recover from."
  [schema execute-tool]
  (let [f (:function schema)
        tool-name (:name f)
        props (get-in f [:parameters :properties])
        args (mapv (fn [[arg-name v]]
                     {:name arg-name,
                      :spec (json-type->spec (:type v)),
                      :description (:description v)})
               props)]
    {:name tool-name,
     :description (:description f),
     :args args,
     :handler (fn [args-map]
                (json/write-str (execute-tool tool-name args-map)))}))

(defn- forward-steps
  "Adapt DSCloj :on-step callbacks to denote-mono :on-event :tool-call
  events, so existing progress narration keeps working. The synthetic
  finishing turn is not reported."
  [on-event]
  (fn [{:keys [iteration tool-name tool-args]}]
    (when-not (= tool-name "finish")
      (on-event {:event :tool-call,
                 :round (inc iteration),
                 :name tool-name,
                 :args tool-args}))))

(def ^:private loop-module
  "The ReAct module denote-mono drives: a single free-form task in, a single
  final text out. The caller's system prompt becomes the instructions."
  {:inputs [{:name :task,
             :spec :string,
             :description "The task, with all the context you need to do it."}],
   :outputs [{:name :final_text,
              :spec :string,
              :description "Your final report or answer for the task."}]})

(defn run-tool-loop
  "Drive an agentic tool loop over PROVIDER (a key from register-provider!)
  via DSCloj's ReAct. OPTS:
  {:system STR :user STR :tools [OPENAI-SCHEMAS]
   :execute-tool (fn [name args-map] value)
   :max-rounds N (default 20) :max-tokens N (default 4096)
   :on-event (fn [event])}.
  Returns {:final-text STR-or-nil :trajectory STR :rounds N
           :stopped :done|:max-rounds}."
  [provider
   {:keys [system user tools execute-tool max-rounds max-tokens on-event],
    :or {max-rounds 20, max-tokens 4096}}]
  (let [on-event (or on-event (constantly nil))
        module (assoc loop-module :instructions system)
        result (dscloj/react provider
                             module
                             {:task user}
                             (mapv #(schema->tool % execute-tool) tools)
                             {:max-iters max-rounds,
                              :max-tokens max-tokens,
                              :validate? false,
                              :on-step (forward-steps on-event)})]
    {:final-text (:final_text result),
     :trajectory (:trajectory result),
     :rounds (:iterations result),
     :stopped (case (:stopped result)
                :finished :done
                :max-iters :max-rounds)}))

(defn chain-of-thought
  "DSCloj chain-of-thought over PROVIDER: reason step by step, then produce
  MODULE's outputs from INPUT-MAP. See dscloj.cot/chain-of-thought."
  [provider module input-map opts]
  (dscloj/chain-of-thought provider module input-map opts))
