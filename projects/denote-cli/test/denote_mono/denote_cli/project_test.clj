(ns denote-mono.denote-cli.project-test
  (:require [clojure.test :refer [deftest is]]
            [denote-mono.cli.core :as cli]))

(deftest cli-help-smoke (is (zero? (:exit (cli/run ["help"])))))
