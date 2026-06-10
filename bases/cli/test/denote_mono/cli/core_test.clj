(ns denote-mono.cli.core-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [denote-mono.cli.core :as cli]))

(deftest run-help
  (testing "no args, help, --help, -h all return success and help text"
    (doseq [args [[] ["help"] ["--help"] ["-h"]]]
      (let [{:keys [exit out]} (cli/run args)]
        (is (zero? exit))
        (is (str/includes? out "Usage: denote"))))))

(deftest run-unknown-command
  (testing "unknown command exits with usage code 2"
    (let [{:keys [exit out]} (cli/run ["frobnicate"])]
      (is (= 2 exit))
      (is (str/includes? out "Unknown command: frobnicate")))))
