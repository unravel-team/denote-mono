(ns denote-mono.cli.llm-wiki-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [denote-mono.cli.core :as cli])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:dynamic *harness* nil)
(def ^:dynamic *config-path* nil)
(def ^:dynamic *wiki-root* nil)
(def ^:dynamic *raw-source* nil)

(defn- temp-dir
  [prefix]
  (str (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defn- cli-fixture
  [f]
  (let [notes (temp-dir "denote-llm-wiki-notes")
        wiki (temp-dir "denote-llm-wiki-wiki")
        config-dir (temp-dir "denote-llm-wiki-config")
        config-path (str config-dir "/config.edn")
        source (str config-dir "/raw-source.txt")]
    (spit (str notes "/20240101T000000--alpha__clojure.org") "alpha")
    (spit source "Raw source text about alpha.")
    (spit config-path
          (pr-str {:default-silo :notes,
                   :default-llm-wiki-silo :wiki,
                   :silos {:notes {:path notes},
                           :wiki {:path wiki, :llm-wiki true}}}))
    (binding [*harness* {:env {"EDITOR" "true"}, :cwd "/elsewhere"}
              *config-path* config-path
              *wiki-root* wiki
              *raw-source* source]
      (f))))

(use-fixtures :each cli-fixture)

(defn- run-cli
  [harness & args]
  (cli/run (into ["--config" *config-path*] args) harness))

(defn- scripted
  [responses]
  (let [remaining (atom (vec responses))]
    (fn [_request]
      (let [msg (first @remaining)]
        (swap! remaining subvec 1)
        {:choices [{:message msg}]}))))

(defn- tool-call
  [id name args]
  {:role :assistant,
   :content nil,
   :tool-calls [{:id id,
                 :type "function",
                 :function {:name name, :arguments (json/write-str args)}}]})

(defn- no-llm [] (fn [_request] (throw (ex-info "LLM must not be called" {}))))

(deftest ingest-command
  (testing "ingest runs the loop and reports created notes"
    (let [harness (assoc *harness*
                    :llm-complete (scripted [(tool-call "c1"
                                                        "create_note"
                                                        {:title "Alpha",
                                                         :keywords ["ml"],
                                                         :body
                                                         "Alpha distilled."})
                                             {:role :assistant,
                                              :content "Filed under 1."}]))
          {:keys [exit out]} (run-cli harness "llm-wiki" "ingest" *raw-source*)]
      (is (zero? exit))
      (is (str/includes? out "Created: "))
      (is (str/includes? out "Filed under 1."))
      (testing "wiki gained the note, index, and log"
        (let [files (map str (.list (java.io.File. *wiki-root*)))]
          (is (some #(str/ends-with? % ".md") files))
          (is (some #{"index.md"} files))
          (is (some #{"log.md"} files))))
      (testing "the source is untouched"
        (is (= "Raw source text about alpha." (slurp *raw-source*))))))
  (testing "missing source fails before any LLM call"
    (let [{:keys [exit]} (run-cli (assoc *harness* :llm-complete (no-llm))
                                  "llm-wiki"
                                  "ingest"
                                  "/nope/missing.txt")]
      (is (= 3 exit))))
  (testing "no FILE argument is a usage error"
    (let [{:keys [exit out]} (run-cli (assoc *harness* :llm-complete (no-llm))
                                      "llm-wiki"
                                      "ingest")]
      (is (= 2 exit))
      (is (str/includes? out "Usage"))))
  (testing "exhausting --max-rounds maps to the tool exit code"
    (let [harness (assoc *harness*
                    :llm-complete (scripted [(tool-call "c1"
                                                        "create_note"
                                                        {:title "Spin",
                                                         :body "spin"})]))
          {:keys [exit]} (run-cli harness
                                  "llm-wiki"
                                  "ingest" *raw-source*
                                  "--max-rounds" "1")]
      (is (= 5 exit)))))

(deftest query-command
  (testing "query prints the answer"
    (let [harness (assoc *harness*
                    :llm-complete (scripted [{:role :assistant,
                                              :content "Alpha is the root."}]))
          {:keys [exit out]}
            (run-cli harness "llm-wiki" "query" "What is alpha?")]
      (is (zero? exit))
      (is (str/includes? out "Alpha is the root."))
      (is (not (str/includes? out "Saved:")))))
  (testing "query --save files the answer and reports the path"
    (let [harness (assoc *harness*
                    :llm-complete (scripted [{:role :assistant,
                                              :content "Alpha is the root."}]))
          {:keys [exit out]}
            (run-cli harness "llm-wiki" "query" "What is alpha?" "--save")]
      (is (zero? exit))
      (is (str/includes? out "Saved: "))
      (let [saved (str/trim (subs out (+ (str/index-of out "Saved: ") 7)))]
        (is (str/includes? saved "what-is-alpha"))
        (is (.exists (java.io.File. (str *wiki-root* "/" saved)))))))
  (testing "no QUESTION argument is a usage error"
    (let [{:keys [exit]} (run-cli (assoc *harness* :llm-complete (no-llm))
                                  "llm-wiki"
                                  "query")]
      (is (= 2 exit)))))

(deftest lint-command
  (testing "an unscaffolded wiki has problems"
    (let [{:keys [exit out]} (run-cli *harness* "llm-wiki" "lint")]
      (is (= 3 exit))
      (is (str/includes? out "stale-index"))
      (is (str/includes? out "missing-scaffold"))))
  (testing "--fix repairs the scaffold and index"
    (let [{:keys [exit out]} (run-cli *harness* "llm-wiki" "lint" "--fix")]
      (is (zero? exit))
      (is (str/includes? out "Fixed: index.md"))))
  (testing "a repaired empty wiki is clean"
    (let [{:keys [exit out]} (run-cli *harness* "llm-wiki" "lint")]
      (is (zero? exit))
      (is (str/includes? out "OK"))))
  (testing "note problems are reported as check: path lines"
    (spit (str *wiki-root* "/20240105T000000==1--dangler.md")
          "Links to [x](denote:19990101T000000) only.\n")
    (let [{:keys [exit out]} (run-cli *harness* "llm-wiki" "lint")]
      (is (= 3 exit))
      (is (str/includes? out "broken-link"))
      (is (str/includes? out "missing-source"))))
  (testing "--deep appends the LLM report"
    (run-cli *harness* "llm-wiki" "lint" "--fix")
    (let [harness (assoc *harness*
                    :llm-complete (scripted [{:role :assistant,
                                              :content "No contradictions."}]))
          {:keys [exit out]} (run-cli harness "llm-wiki" "lint" "--deep")]
      (is (= 3 exit))
      (is (str/includes? out "No contradictions.")))))

(deftest silo-selection
  (testing "--silo naming a non-llm-wiki silo is a validation error"
    (let [{:keys [exit out]}
            (run-cli *harness* "--silo" "notes" "llm-wiki" "lint")]
      (is (= 3 exit))
      (is (str/includes? out "not an llm-wiki silo"))))
  (testing "no default and no flagged silo is a validation error"
    (let [config-path (str (temp-dir "denote-llm-wiki-config2") "/config.edn")
          _ (spit config-path
                  (pr-str {:default-silo :notes,
                           :silos {:notes {:path (temp-dir "notes2")}}}))
          {:keys [exit out]} (cli/run ["--config" config-path "llm-wiki" "lint"]
                                      *harness*)]
      (is (= 3 exit))
      (is (str/includes? out "No llm-wiki silo")))))

(deftest surface-tests
  (testing "unknown subcommand is a usage error"
    (let [{:keys [exit out]} (run-cli *harness* "llm-wiki" "bogus")]
      (is (= 2 exit))
      (is (str/includes? out "Usage"))))
  (testing "help mentions llm-wiki"
    (let [{:keys [out]} (run-cli *harness* "help")]
      (is (str/includes? out "llm-wiki ingest FILE"))))
  (testing "completions know the command and its options"
    (let [{:keys [out]} (run-cli *harness* "completions" "bash")]
      (is (str/includes? out "llm-wiki"))
      (is (str/includes? out "--deep")))))
