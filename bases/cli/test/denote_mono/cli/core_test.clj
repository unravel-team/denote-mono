(ns denote-mono.cli.core-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [denote-mono.cli.core :as cli])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:dynamic *harness* nil)
(def ^:dynamic *config-path* nil)
(def ^:dynamic *notes-root* nil)

(defn- temp-dir
  [prefix]
  (str (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defn- cli-fixture
  [f]
  (let [notes (temp-dir "denote-cli-notes")
        work (temp-dir "denote-cli-work")
        config-path (str (temp-dir "denote-cli-config") "/config.edn")]
    (spit (str notes "/20240101T000000--alpha__clojure.org") "alpha")
    (spit (str notes "/20240102T000000--beta__notes.org") "beta")
    (spit (str work "/20240301T000000--work-note__job.org") "work")
    (spit config-path
          (pr-str {:default-silo :notes,
                   :silos {:notes {:path notes}, :work {:path work}}}))
    (binding [*harness* {:env {"EDITOR" "true"}, :cwd "/elsewhere"}
              *config-path* config-path
              *notes-root* notes]
      (f))))

(use-fixtures :each cli-fixture)

(defn- run-cli
  [& args]
  (cli/run (into ["--config" *config-path*] args) *harness*))

(deftest run-help
  (testing "no args, help, --help, -h all return success and help text"
    (doseq [args [[] ["help"] ["--help"] ["-h"]]]
      (let [{:keys [exit out]} (cli/run args *harness*)]
        (is (zero? exit))
        (is (str/includes? out "Usage: denote"))))))

(deftest run-unknown-command
  (testing "unknown command exits with usage code 2"
    (let [{:keys [exit out]} (run-cli "frobnicate")]
      (is (= 2 exit))
      (is (str/includes? out "Unknown command: frobnicate")))))

(deftest list-command
  (testing "default silo listing, relative paths, sorted by identifier"
    (let [{:keys [exit out]} (run-cli "list")]
      (is (zero? exit))
      (is (= ["20240101T000000--alpha__clojure.org"
              "20240102T000000--beta__notes.org"]
             (str/split-lines out)))))
  (testing "--silo selects another silo"
    (let [{:keys [out]} (run-cli "--silo" "work" "list")]
      (is (= ["20240301T000000--work-note__job.org"] (str/split-lines out)))))
  (testing "keyword filter"
    (let [{:keys [out]} (run-cli "list" "--keyword" "clojure")]
      (is (= ["20240101T000000--alpha__clojure.org"] (str/split-lines out)))))
  (testing "--json emits parseable note records"
    (let [{:keys [out]} (run-cli "list" "--json" "--id" "20240101T000000")
          parsed (json/read-str out :key-fn keyword)]
      (is (= "alpha" (get-in parsed [:filename :title])))
      (is (= "notes" (:silo parsed)))))
  (testing "unknown silo errors with configured list"
    (let [{:keys [exit out]} (run-cli "--silo" "nope" "list")]
      (is (= 3 exit))
      (is (str/includes? out "notes")))))

(deftest find-command
  (testing "find with query prints matching paths"
    (let [{:keys [exit out]} (run-cli "find" "alpha")]
      (is (zero? exit))
      (is (= ["20240101T000000--alpha__clojure.org"] (str/split-lines out)))))
  (testing "find without matches exits 6"
    (is (= 6 (:exit (run-cli "find" "zzz"))))))

(deftest open-command
  (testing "open runs the editor (EDITOR=true) and succeeds"
    (is (zero? (:exit (run-cli "open" "alpha"))))))

(deftest silo-commands
  (testing "silo list shows names and paths"
    (let [{:keys [exit out]} (run-cli "silo" "list")]
      (is (zero? exit))
      (is (str/includes? out "notes"))
      (is (str/includes? out "work"))))
  (testing "silo path defaults to the default silo"
    (is (= *notes-root* (:out (run-cli "silo" "path")))))
  (testing "silo path NAME"
    (let [{:keys [exit out]} (run-cli "silo" "path" "work")]
      (is (zero? exit))
      (is (str/ends-with? out (last (str/split out #"/"))))))
  (testing "silo doctor reports healthy silos"
    (let [{:keys [exit out]} (run-cli "silo" "doctor")]
      (is (zero? exit))
      (is (str/includes? out "OK")))))
