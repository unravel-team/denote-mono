(ns denote-mono.llm-wiki.source-test
  (:require [clojure.test :refer [deftest is testing]]
            [denote-mono.config.interface :as config]
            [denote-mono.filesystem.interface :as fs]
            [denote-mono.llm-wiki.source :as source])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir
  []
  (str (Files/createTempDirectory "denote-llm-wiki-source-test"
                                  (make-array FileAttribute 0))))

(defn- make-context
  [home]
  {:config (config/default-config), :env {"HOME" home}})

(deftest prepare-text-file-source-test
  (let [dir (temp-dir)
        path (str dir "/notes.md")
        content "Alpha source text.\n"]
    (spit path content)
    (let [prepared (source/prepare-source (make-context dir) path {})
          abs (fs/canonical path)]
      (is (= path (:input prepared)))
      (is (= :text-file (:kind prepared)))
      (is (= (str "file:" abs) (:uri prepared)))
      (is (= "notes.md" (:display-name prepared)))
      (is (= content (:content prepared)))
      (is (= (source/sha256 content) (get-in prepared [:fingerprint :sha256])))
      (is (integer? (get-in prepared [:fingerprint :mtime]))))))

(deftest prepare-text-file-expands-home-test
  (let [home (temp-dir)
        dir (str home "/Documents")
        _ (.mkdirs (java.io.File. dir))
        path (str dir "/quoted.txt")]
    (spit path "quoted home path")
    (testing "quoted ~/ paths expand from the command context env"
      (let [prepared (source/prepare-source (make-context home)
                                            "~/Documents/quoted.txt"
                                            {})]
        (is (= "~/Documents/quoted.txt" (:input prepared)))
        (is (= (str "file:" (fs/canonical path)) (:uri prepared)))))))
