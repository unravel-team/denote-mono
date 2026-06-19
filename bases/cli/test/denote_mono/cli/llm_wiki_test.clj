(ns denote-mono.cli.llm-wiki-test
  "CLI-level tests for llm-wiki. DSCloj drives the agent loop; these tests
  stub litellm.router/completion (no network) with DSCloj-format responses
  and provide a dummy API key in the harness env so provider registration
  succeeds."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [denote-mono.cli.core :as cli]
            [denote-mono.filesystem.interface :as fs]
            [denote-mono.llm-wiki.source :as source]
            [litellm.router :as router])
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
    (binding [*harness* {:env {"EDITOR" "true",
                               "HOME" config-dir,
                               "OPENROUTER_API_KEY" "test-key"},
                         :cwd "/elsewhere"}
              *config-path* config-path
              *wiki-root* wiki
              *raw-source* source]
      (f))))

(use-fixtures :each cli-fixture)

(defn- run-cli
  [harness & args]
  (cli/run (into ["--config" *config-path*] args) harness))

(defn- resp
  [content]
  {:choices [{:message {:role "assistant", :content content}}]})

(defn- scripted
  "A litellm.router/completion stub returning each DSCloj-format string in
  CONTENTS in order."
  [contents]
  (let [remaining (atom (vec contents))]
    (fn [_config-name _request]
      (let [c (first @remaining)]
        (swap! remaining rest)
        (resp c)))))

(defn- step
  "A ReAct step response selecting TOOL with ARGS (a map)."
  [thought tool args]
  (str "[[ ## next_thought ## ]]\n"
       thought
       "\n\n"
       "[[ ## next_tool_name ## ]]\n"
       tool
       "\n\n"
       "[[ ## next_tool_args ## ]]\n"
       (json/write-str args)))

(defn- cot
  "A chain-of-thought extraction response producing FIELD = VALUE."
  [field reasoning value]
  (str "[[ ## reasoning ## ]]\n"
       reasoning
       "\n\n"
       "[[ ## " field
       " ## ]]\n" value))

(defn- no-llm [] (fn [& _] (throw (ex-info "LLM must not be called" {}))))

(defn- fake-url-source
  [url]
  (let [content "Fetched CLI URL text."]
    {:input url,
     :kind :url,
     :uri url,
     :display-name "article",
     :content content,
     :fingerprint {:sha256 (source/sha256 content), :final-url url}}))

(defn- fake-pdf-source
  [path]
  (let [content "Fetched CLI PDF text."
        abs (fs/canonical path)]
    {:input path,
     :kind :pdf-file,
     :path abs,
     :uri (str "file:" abs),
     :display-name "paper.pdf",
     :content content,
     :fingerprint {:sha256 (source/sha256 content), :mtime 1710000000000}}))

(deftest ingest-command
  (testing "ingest runs the loop and reports created notes"
    (let [{:keys [exit out]}
            (with-redefs [router/completion
                            (scripted
                              [(step "make alpha"
                                     "create_note"
                                     {:title "Alpha",
                                      :keywords ["ml"],
                                      :body "Alpha distilled."})
                               (step "done" "finish" {})
                               (cot "final_text" "r" "Filed under 1.")])]
              (run-cli *harness* "llm-wiki" "ingest" *raw-source*))]
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
  (testing "re-running an unchanged ingested source is skipped"
    (let [{:keys [exit out]}
            (with-redefs [router/completion (no-llm)]
              (run-cli *harness* "llm-wiki" "ingest" *raw-source*))]
      (is (zero? exit))
      (is (str/includes? out "Skipped"))))
  (testing "missing source fails before any LLM call"
    (let [{:keys [exit]}
            (with-redefs [router/completion (no-llm)]
              (run-cli *harness* "llm-wiki" "ingest" "/nope/missing.txt"))]
      (is (= 3 exit))))
  (testing "no SOURCE argument is a usage error"
    (let [{:keys [exit out]} (with-redefs [router/completion (no-llm)]
                               (run-cli *harness* "llm-wiki" "ingest"))]
      (is (= 2 exit))
      (is (str/includes? out "Usage"))
      (is (str/includes? out "SOURCE"))))
  (testing "exhausting --max-rounds maps to the tool exit code"
    (spit *raw-source* "Raw source text for max-rounds failure.")
    (let [{:keys [exit out]}
            (with-redefs [router/completion
                            (scripted
                              [(step "spin"
                                     "create_note"
                                     {:title "Spin", :body "spin"})
                               (cot "final_text" "r" "partial")
                               (cot "remaining" "r" "Everything remains.")])]
              (run-cli *harness*
                       "llm-wiki"
                       "ingest" *raw-source*
                       "--max-rounds" "1"))]
      (is (= 5 exit))
      (testing "the failure tells you progress is saved and how to resume"
        (is (str/includes? out "re-run")))))
  (testing "an empty model reply is a tool failure, not silent success"
    (spit *raw-source* "Raw source text for empty-reply failure.")
    (let [{:keys [exit out]}
            (with-redefs [router/completion (scripted
                                              [(step "nothing" "finish" {})
                                               (cot "final_text" "r" "")])]
              (run-cli *harness* "llm-wiki" "ingest" *raw-source*))]
      (is (= 5 exit))
      (is (str/includes? out "empty reply"))))
  (testing "--fresh is accepted"
    (let [{:keys [exit]}
            (with-redefs [router/completion (scripted
                                              [(step "f" "finish" {})
                                               (cot "final_text" "r" "Done.")])]
              (run-cli *harness* "llm-wiki" "ingest" *raw-source* "--fresh"))]
      (is (zero? exit)))))

(deftest ingest-url-source
  (testing "HTTP/HTTPS sources pass CLI prevalidation and ingest normally"
    (let [url "https://example.com/article"
          calls (atom 0)
          {:keys [exit out]}
            (with-redefs [source/prepare-source (fn [_ source-path _]
                                                  (swap! calls inc)
                                                  (is (= url source-path))
                                                  (fake-url-source source-path))
                          router/completion
                            (scripted
                              [(step "make alpha"
                                     "create_note"
                                     {:title "Alpha",
                                      :keywords ["web"],
                                      :body "Alpha from URL."})
                               (step "done" "finish" {})
                               (cot "final_text" "r" "Filed URL source.")])]
              (run-cli *harness* "llm-wiki" "ingest" url))]
      (is (zero? exit))
      (is (= 1 @calls))
      (is (str/includes? out "Filed URL source.")))))

(deftest ingest-pdf-source
  (testing "PDF sources pass CLI prevalidation and ingest normally"
    (let [pdf (str (temp-dir "denote-llm-wiki-pdf") "/paper.pdf")
          _ (spit pdf "%PDF fake")
          calls (atom 0)
          {:keys [exit out]}
            (with-redefs [source/prepare-source (fn [_ source-path _]
                                                  (swap! calls inc)
                                                  (is (= pdf source-path))
                                                  (fake-pdf-source source-path))
                          router/completion
                            (scripted
                              [(step "make alpha"
                                     "create_note"
                                     {:title "Alpha PDF",
                                      :keywords ["pdf"],
                                      :body "Alpha from PDF."})
                               (step "done" "finish" {})
                               (cot "final_text" "r" "Filed PDF source.")])]
              (run-cli *harness* "llm-wiki" "ingest" pdf))]
      (is (zero? exit))
      (is (= 1 @calls))
      (is (str/includes? out "Filed PDF source.")))))

(deftest ingest-expands-home-source
  (testing "quoted ~/ paths pass CLI prevalidation and ingest normally"
    (let [{:keys [exit out]}
            (with-redefs [router/completion
                            (scripted
                              [(step "make alpha"
                                     "create_note"
                                     {:title "Alpha",
                                      :keywords ["ml"],
                                      :body "Alpha distilled."})
                               (step "done" "finish" {})
                               (cot "final_text" "r" "Filed home source.")])]
              (run-cli *harness* "llm-wiki" "ingest" "~/raw-source.txt"))]
      (is (zero? exit))
      (is (str/includes? out "Filed home source.")))))

(deftest ingest-multiple-sources
  (let [source2 (str (temp-dir "denote-llm-wiki-src2") "/second.txt")]
    (spit source2 "Second source text.")
    (testing "several SOURCE arguments ingest sequentially, labeled per source"
      (let [{:keys [exit out]}
              (with-redefs [router/completion
                              (scripted [(step "one"
                                               "create_note"
                                               {:title "One", :body "one"})
                                         (step "done" "finish" {})
                                         (cot "final_text" "r" "Did one.")
                                         (step "two"
                                               "create_note"
                                               {:title "Two", :body "two"})
                                         (step "done" "finish" {})
                                         (cot "final_text" "r" "Did two.")])]
                (run-cli *harness* "llm-wiki" "ingest" *raw-source* source2))]
        (is (zero? exit))
        (is (str/includes? out *raw-source*))
        (is (str/includes? out source2))
        (is (str/includes? out "Did one."))
        (is (str/includes? out "Did two."))))
    (testing "one bad source fails the batch before any LLM call"
      (let [{:keys [exit]} (with-redefs [router/completion (no-llm)]
                             (run-cli *harness*
                                      "llm-wiki"
                                      "ingest"
                                      *raw-source*
                                      "/nope/missing.txt"))]
        (is (= 3 exit))))
    (testing "an exhausted source fails the batch; later sources still run"
      (spit *raw-source* "Raw source text for batch exhaustion.")
      (spit source2 "Second source text after batch exhaustion.")
      (let [{:keys [exit out]}
              (with-redefs [router/completion
                              (scripted
                                [(step "spin"
                                       "create_note"
                                       {:title "Spin", :body "s"})
                                 (cot "final_text" "r" "partial")
                                 (cot "remaining" "r" "More to do.")
                                 (step "f" "finish" {})
                                 (cot "final_text" "r" "Second done.")])]
                (run-cli *harness*
                         "llm-wiki"
                         "ingest"
                         *raw-source*
                         source2
                         "--max-rounds"
                         "1"))]
        (is (= 5 exit))
        (is (str/includes? out "re-run"))
        (is (str/includes? out "Second done."))))))

(deftest ingest-progress
  (let [responses
          [(step "make alpha" "create_note" {:title "Alpha", :body "Body."})
           (step "done" "finish" {}) (cot "final_text" "r" "Done.")]]
    (testing "on a terminal, progress is narrated on stderr"
      (let [err (java.io.StringWriter.)]
        (binding [*err* err]
          (with-redefs [router/completion (scripted responses)]
            (run-cli (assoc *harness* :tty? true)
                     "llm-wiki"
                     "ingest"
                     *raw-source*)))
        (is (str/includes? (str err) "creating note: Alpha"))))
    (testing "a tty on stderr alone narrates too (stdin piped, e.g. xargs)"
      (spit *raw-source* "Raw source text for stderr progress.")
      (let [err (java.io.StringWriter.)]
        (binding [*err* err]
          (with-redefs [router/completion (scripted responses)]
            (run-cli (assoc *harness* :stderr-tty? true)
                     "llm-wiki"
                     "ingest"
                     *raw-source*)))
        (is (str/includes? (str err) "creating note: Alpha"))))
    (testing "piped output stays silent"
      (spit *raw-source* "Raw source text for piped progress.")
      (let [err (java.io.StringWriter.)]
        (binding [*err* err]
          (with-redefs [router/completion (scripted responses)]
            (run-cli *harness* "llm-wiki" "ingest" *raw-source*)))
        (is (= "" (str err)))))))

(deftest query-command
  (testing "query prints the answer"
    (let [{:keys [exit out]}
            (with-redefs [router/completion
                            (scripted
                              [(step "I know" "finish" {})
                               (cot "final_text" "r" "Alpha is the root.")])]
              (run-cli *harness* "llm-wiki" "query" "What is alpha?"))]
      (is (zero? exit))
      (is (str/includes? out "Alpha is the root."))
      (is (not (str/includes? out "Saved:")))))
  (testing "query --save files the answer and reports the path"
    (let [{:keys [exit out]}
            (with-redefs [router/completion
                            (scripted
                              [(step "I know" "finish" {})
                               (cot "final_text" "r" "Alpha is the root.")])]
              (run-cli *harness* "llm-wiki" "query" "What is alpha?" "--save"))]
      (is (zero? exit))
      (is (str/includes? out "Saved: "))
      (let [saved (str/trim (subs out (+ (str/index-of out "Saved: ") 7)))]
        (is (str/includes? saved "what-is-alpha"))
        (is (.exists (java.io.File. (str *wiki-root* "/" saved)))))))
  (testing "an empty answer is a tool failure, not silent success"
    (let [{:keys [exit out]}
            (with-redefs [router/completion (scripted
                                              [(step "nothing" "finish" {})
                                               (cot "final_text" "r" "")])]
              (run-cli *harness* "llm-wiki" "query" "Anything?"))]
      (is (= 5 exit))
      (is (str/includes? out "empty reply"))))
  (testing "no QUESTION argument is a usage error"
    (let [{:keys [exit]} (with-redefs [router/completion (no-llm)]
                           (run-cli *harness* "llm-wiki" "query"))]
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
    (let [{:keys [exit out]}
            (with-redefs [router/completion
                            (scripted
                              [(step "audit" "finish" {})
                               (cot "final_text" "r" "No contradictions.")])]
              (run-cli *harness* "llm-wiki" "lint" "--deep"))]
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
      (is (str/includes? out "llm-wiki ingest SOURCE"))))
  (testing "completions know the command and its options"
    (let [{:keys [out]} (run-cli *harness* "completions" "bash")]
      (is (str/includes? out "llm-wiki"))
      (is (str/includes? out "--deep"))
      (is (str/includes? out "--fresh")))))
