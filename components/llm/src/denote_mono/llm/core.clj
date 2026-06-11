(ns denote-mono.llm.core
  "LLM access. Wraps litellm-clj behind a provider-agnostic complete-fn
  and a manual tool-calling loop. All wire concerns live here: callers
  hand over plain Clojure tool executors and never see JSON."
  (:require [clojure.data.json :as json]
            [litellm.core :as litellm]))

(defn make-complete-fn
  "Build (fn [request-map] response-map) bound to LLM-CONFIG
  {:provider KW :model STR :api-key STR :api-base STR-or-nil
  :timeout-ms N-or-nil}. Provider/HTTP failures are rethrown as ex-info
  {:type :tool}."
  [{:keys [provider model api-key api-base timeout-ms]}]
  (fn [request]
    (try (litellm/completion provider
                             model
                             request
                             (cond-> {:api-key api-key}
                               api-base (assoc :api-base api-base)
                               timeout-ms (assoc :timeout timeout-ms)))
         (catch Exception e
           (throw (ex-info (str "LLM request failed: " (ex-message e))
                           {:type :tool, :provider provider, :model model}
                           e))))))

(defn- tool-result-message
  "Execute one tool call and shape its outcome as a :tool message.
  Executor exceptions become {:error MSG} results so the model can
  recover instead of the loop dying."
  [execute-tool on-event round tool-call]
  (let [call-id (:id tool-call)
        tool-name (get-in tool-call [:function :name])
        args (json/read-str (get-in tool-call [:function :arguments])
                            :key-fn
                            keyword)
        _ (on-event
            {:event :tool-call, :round round, :name tool-name, :args args})
        result (try (execute-tool tool-name args)
                    (catch Exception e
                      (on-event {:event :tool-error,
                                 :round round,
                                 :name tool-name,
                                 :message (ex-message e)})
                      {:error (ex-message e)}))]
    {:role :tool, :tool-call-id call-id, :content (json/write-str result)}))

(defn run-tool-loop
  "Drive COMPLETE-FN until the model answers without tool calls or
  MAX-ROUNDS requests have been made. OPTS:
  {:system STR :user STR :tools [SCHEMAS]
   :execute-tool (fn [name args-map] value)
   :max-rounds N (default 20) :max-tokens N (default 4096)}.
  An optional :on-event fn receives progress maps as the loop runs:
  {:event :request :round N}, {:event :tool-call :round N :name S :args M},
  and {:event :tool-error :round N :name S :message S}.
  Returns {:final-text STR-or-nil :messages [...] :rounds N
           :stopped :done|:max-rounds}."
  [complete-fn
   {:keys [system user tools execute-tool max-rounds max-tokens on-event],
    :or {max-rounds 20, max-tokens 4096}}]
  (let [on-event (or on-event (constantly nil))]
    (loop [messages [{:role :system, :content system}
                     {:role :user, :content user}]
           round 1]
      (let [_ (on-event {:event :request, :round round})
            response (complete-fn {:messages messages,
                                   :tools tools,
                                   :max-tokens max-tokens})
            msg (get-in response [:choices 0 :message])
            tool-calls (:tool-calls msg)]
        (cond
          (empty? tool-calls) {:final-text (:content msg),
                               :messages (conj messages msg),
                               :rounds round,
                               :stopped :done}
          (>= round max-rounds) {:final-text nil,
                                 :messages (conj messages msg),
                                 :rounds round,
                                 :stopped :max-rounds}
          :else
            (let [assistant (cond-> msg (nil? (:content msg)) (dissoc :content))
                  results (mapv
                            #(tool-result-message execute-tool on-event round %)
                            tool-calls)]
              (recur (into (conj messages assistant) results) (inc round))))))))
