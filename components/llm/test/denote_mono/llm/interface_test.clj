(ns denote-mono.llm.interface-test
  "Tests for the DSCloj adapter. DSCloj drives the ReAct loop and parses the
  text protocol; these tests stub litellm.router/completion (no network) with
  DSCloj-format responses and verify the denote-mono <-> DSCloj conversion:
  OpenAI tool schemas become DSCloj tools, executors run, results map back,
  and progress events are forwarded."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [denote-mono.llm.interface :as llm]
            [litellm.router :as router]))

(defn- resp
  [content]
  {:choices [{:message {:role "assistant", :content content}}]})

(defn- scripted
  "Stub returning each content in CONTENTS in order (DSCloj-format strings)."
  [contents]
  (let [remaining (atom contents)]
    (fn [_config-name _request]
      (let [c (first @remaining)]
        (swap! remaining rest)
        (resp c)))))

(defn- step
  "A ReAct step response selecting TOOL with ARGS."
  [thought tool args]
  (str "[[ ## next_thought ## ]]\n"
       thought
       "\n\n"
       "[[ ## next_tool_name ## ]]\n"
       tool
       "\n\n"
       "[[ ## next_tool_args ## ]]\n"
       (json/write-str args)))

(defn- extract
  "A chain-of-thought extraction response for the loop's :final_text output."
  [reasoning final]
  (str "[[ ## reasoning ## ]]\n"
       reasoning
       "\n\n"
       "[[ ## final_text ## ]]\n"
       final))

(def ^:private weather-tool
  {:type "function",
   :function {:name "get_weather",
              :description "Get the weather",
              :parameters {:type "object",
                           :properties {:location {:type "string",
                                                   :description "City"}},
                           :required ["location"]}}})

(defn- provider
  []
  (llm/register-provider!
    {:provider :openrouter, :model "test-model", :api-key "test-key"}))

(deftest register-provider-test
  (testing "registration returns a provider key usable downstream"
    (is (keyword? (provider)))))

(deftest tool-then-finish-test
  (testing "a tool call then finish runs the executor and extracts final text"
    (let [calls (atom [])]
      (with-redefs [router/completion
                      (scripted
                        [(step "check weather" "get_weather" {:location "Pune"})
                         (step "have it" "finish" {})
                         (extract "reasoned" "It is sunny.")])]
        (let [result (llm/run-tool-loop
                       (provider)
                       {:system "sys",
                        :user "weather?",
                        :tools [weather-tool],
                        :execute-tool
                        (fn [n a] (swap! calls conj [n a]) {:temp 31})})]
          (is (= "It is sunny." (:final-text result)))
          (is (= :done (:stopped result)))
          (testing "executor saw the tool name and keyword-keyed args"
            (is (= [["get_weather" {:location "Pune"}]] @calls)))
          (testing "the trajectory is returned for handoff/resume"
            (is (string? (:trajectory result)))
            (is (re-find #"get_weather" (:trajectory result)))))))))

(deftest max-rounds-test
  (testing "a loop that never finishes stops at :max-rounds"
    (with-redefs [router/completion
                    (fn [_c _r]
                      (resp (step "spin" "get_weather" {:location "X"})))]
      (let [result (llm/run-tool-loop (provider)
                                      {:system "s",
                                       :user "u",
                                       :tools [weather-tool],
                                       :execute-tool (fn [_ _] {:ok true}),
                                       :max-rounds 2})]
        (is (= :max-rounds (:stopped result)))
        (is (= 2 (:rounds result)))))))

(deftest tool-error-recovers-test
  (testing "an executor exception becomes an observation, not a crash"
    (with-redefs [router/completion
                    (scripted [(step "try" "get_weather" {:location "X"})
                               (step "give up" "finish" {})
                               (extract "r" "Recovered.")])]
      (let [result (llm/run-tool-loop (provider)
                                      {:system "s",
                                       :user "u",
                                       :tools [weather-tool],
                                       :execute-tool
                                       (fn [_ _] (throw (ex-info "boom" {})))})]
        (is (= "Recovered." (:final-text result)))
        (is (re-find #"Execution error in get_weather: boom"
                     (:trajectory result)))))))

(deftest on-event-forwarding-test
  (testing "tool calls surface as :tool-call events; finish is not reported"
    (let [events (atom [])]
      (with-redefs [router/completion
                      (scripted [(step "t" "get_weather" {:location "P"})
                                 (step "f" "finish" {}) (extract "r" "done")])]
        (llm/run-tool-loop (provider)
                           {:system "s",
                            :user "u",
                            :tools [weather-tool],
                            :execute-tool (fn [_ _] {:ok true}),
                            :on-event #(swap! events conj %)})
        (is (some #(= %
                      {:event :tool-call,
                       :round 1,
                       :name "get_weather",
                       :args {:location "P"}})
                  @events))
        (is (not (some #(= "finish" (:name %)) @events)))))))

(deftest chain-of-thought-test
  (testing "chain-of-thought returns reasoning alongside the output"
    (with-redefs [router/completion (fn [_c _r]
                                      (resp (extract "because 6*7" "42")))]
      (let [result (llm/chain-of-thought (provider)
                                         {:inputs [{:name :q, :spec :string}],
                                          :outputs [{:name :final_text,
                                                     :spec :string}]}
                                         {:q "6 times 7?"}
                                         {:validate? false})]
        (is (= "42" (:final_text result)))
        (is (= "because 6*7" (:reasoning result)))))))
