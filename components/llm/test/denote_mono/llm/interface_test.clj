(ns denote-mono.llm.interface-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [denote-mono.llm.interface :as llm]))

(defn- scripted
  "Complete-fn that pops canned assistant messages off RESPONSES and
  records every request it receives in REQUESTS."
  [responses requests]
  (let [remaining (atom (vec responses))]
    (fn [request]
      (swap! requests conj request)
      (let [msg (first @remaining)]
        (swap! remaining subvec 1)
        {:choices [{:message msg}]}))))

(def ^:private weather-tool
  {:type "function",
   :function {:name "get_weather",
              :description "Get the weather",
              :parameters {:type "object",
                           :properties {:location {:type "string"}},
                           :required ["location"]}}})

(deftest single-round-test
  (testing "a plain assistant reply ends the loop"
    (let [requests (atom [])
          result (llm/run-tool-loop
                   (scripted [{:role :assistant, :content "All done."}]
                             requests)
                   {:system "You are a test.",
                    :user "Hello",
                    :tools [weather-tool],
                    :execute-tool (fn [_ _] (throw (ex-info "no tools" {})))})]
      (is (= "All done." (:final-text result)))
      (is (= :done (:stopped result)))
      (is (= 1 (:rounds result)))
      (let [request (first @requests)]
        (is (= [weather-tool] (:tools request)))
        (is (= [{:role :system, :content "You are a test."}
                {:role :user, :content "Hello"}]
               (:messages request)))))))

(deftest tool-round-test
  (testing "tool calls are executed and results fed back"
    (let [requests (atom [])
          tool-args (atom nil)
          result
            (llm/run-tool-loop
              (scripted [{:role :assistant,
                          :content nil,
                          :tool-calls [{:id "call_1",
                                        :type "function",
                                        :function {:name "get_weather",
                                                   :arguments
                                                   "{\"location\":\"Pune\"}"}}]}
                         {:role :assistant, :content "It is sunny."}]
                        requests)
              {:system "sys",
               :user "weather?",
               :tools [weather-tool],
               :execute-tool
               (fn [name args] (reset! tool-args [name args]) {:temp 31})})]
      (is (= "It is sunny." (:final-text result)))
      (is (= :done (:stopped result)))
      (is (= 2 (:rounds result)))
      (testing "arguments arrive parsed with keyword keys"
        (is (= ["get_weather" {:location "Pune"}] @tool-args)))
      (testing "second request carries assistant msg and tool result"
        (let [messages (:messages (second @requests))
              [_sys _user assistant tool-result] messages]
          (is (= 4 (count messages)))
          (is (:tool-calls assistant))
          (is (not (contains? assistant :content)))
          (is (= :tool (:role tool-result)))
          (is (= "call_1" (:tool-call-id tool-result)))
          (is (= {"temp" 31} (json/read-str (:content tool-result)))))))))

(deftest tool-error-test
  (testing "an executor exception becomes an error result, not a crash"
    (let [requests (atom [])
          result (llm/run-tool-loop
                   (scripted [{:role :assistant,
                               :content nil,
                               :tool-calls [{:id "call_1",
                                             :type "function",
                                             :function {:name "explode",
                                                        :arguments "{}"}}]}
                              {:role :assistant, :content "Recovered."}]
                             requests)
                   {:system "sys",
                    :user "go",
                    :tools [],
                    :execute-tool (fn [_ _] (throw (ex-info "boom" {})))})]
      (is (= "Recovered." (:final-text result)))
      (let [tool-result (last (:messages (second @requests)))]
        (is (= :tool (:role tool-result)))
        (is (= {"error" "boom"} (json/read-str (:content tool-result))))))))

(deftest on-event-test
  (testing "the loop reports requests and tool calls as they happen"
    (let [events (atom [])
          _ (llm/run-tool-loop
              (scripted [{:role :assistant,
                          :content nil,
                          :tool-calls [{:id "call_1",
                                        :type "function",
                                        :function {:name "get_weather",
                                                   :arguments
                                                   "{\"location\":\"Pune\"}"}}]}
                         {:role :assistant, :content "Done."}]
                        (atom []))
              {:system "s",
               :user "u",
               :tools [],
               :execute-tool (fn [_ _] {:ok true}),
               :on-event #(swap! events conj %)})]
      (is (= [{:event :request, :round 1}
              {:event :tool-call,
               :round 1,
               :name "get_weather",
               :args {:location "Pune"}} {:event :request, :round 2}]
             @events))))
  (testing "executor failures are reported as tool-error events"
    (let [events (atom [])
          _ (llm/run-tool-loop
              (scripted [{:role :assistant,
                          :content nil,
                          :tool-calls [{:id "call_1",
                                        :type "function",
                                        :function {:name "explode",
                                                   :arguments "{}"}}]}
                         {:role :assistant, :content "Done."}]
                        (atom []))
              {:system "s",
               :user "u",
               :tools [],
               :execute-tool (fn [_ _] (throw (ex-info "boom" {}))),
               :on-event #(swap! events conj %)})]
      (is (some
            #(=
               %
               {:event :tool-error, :round 1, :name "explode", :message "boom"})
            @events)))))

(deftest max-rounds-test
  (testing "the loop gives up after :max-rounds tool rounds"
    (let [tool-call {:role :assistant,
                     :content nil,
                     :tool-calls [{:id "call_1",
                                   :type "function",
                                   :function {:name "spin", :arguments "{}"}}]}
          requests (atom [])
          result (llm/run-tool-loop (scripted (repeat 3 tool-call) requests)
                                    {:system "sys",
                                     :user "go",
                                     :tools [],
                                     :execute-tool (fn [_ _] {:ok true}),
                                     :max-rounds 3})]
      (is (= :max-rounds (:stopped result)))
      (is (nil? (:final-text result)))
      (is (= 3 (:rounds result))))))
