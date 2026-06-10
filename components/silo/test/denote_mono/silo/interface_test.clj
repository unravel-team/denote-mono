(ns denote-mono.silo.interface-test
  (:require [clojure.test :refer [deftest is testing]]
            [denote-mono.filesystem.interface :as fs]
            [denote-mono.silo.interface :as silo])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir
  []
  (str (Files/createTempDirectory "denote-silo-test"
                                  (make-array FileAttribute 0))))

(defn- test-config
  [notes-path work-path]
  {:default-silo :notes,
   :silos {:notes {:path notes-path}, :work {:path work-path}}})

(deftest all-silos-test
  (let [notes (temp-dir)
        work (temp-dir)
        silos (silo/all-silos (test-config notes work) {})]
    (is (= #{:notes :work} (set (keys silos))))
    (is (= notes (get-in silos [:notes :path])))))

(deftest path-for-test
  (let [notes (temp-dir)
        config (test-config notes (temp-dir))]
    (is (= notes (silo/path-for config :notes {})))
    (is (nil? (silo/path-for config :nope {})))))

(deftest expand-home-in-silo-path-test
  (let [config {:silos {:notes {:path "~/notes"}}}
        silos (silo/all-silos config {"HOME" "/home/u"})]
    (is (= "/home/u/notes" (get-in silos [:notes :path])))))

(deftest resolve-silo-test
  (let [notes (temp-dir)
        work (temp-dir)
        config (test-config notes work)]
    (testing "--silo name wins"
      (is (= :work (:name (silo/resolve-silo config {:silo :work} nil {})))))
    (testing "string silo names are accepted"
      (is (= :work (:name (silo/resolve-silo config {:silo "work"} nil {})))))
    (testing "unknown silo name errors with configured list"
      (let [e (try (silo/resolve-silo config {:silo :nope} nil {})
                   (catch Exception e (ex-data e)))]
        (is (= :validation (:type e)))
        (is (= #{:notes :work} (set (:silos e))))))
    (testing "--root path"
      (let [resolved (silo/resolve-silo config {:root work} nil {})]
        (is (nil? (:name resolved)))
        (is (= (fs/canonical work) (:path resolved)))))
    (testing "cwd inside a silo selects it"
      (is (= :work (:name (silo/resolve-silo config {} (str work "/sub") {})))))
    (testing "falls back to default silo"
      (is (= :notes (:name (silo/resolve-silo config {} "/elsewhere" {})))))
    (testing "no default and no match errors"
      (is (thrown? Exception
                   (silo/resolve-silo (dissoc config :default-silo)
                                      {}
                                      "/elsewhere"
                                      {}))))))

(deftest in-silo?-test
  (let [notes (temp-dir)]
    (is (silo/in-silo? (str notes "/a.org") {:name :notes, :path notes}))
    (is (not (silo/in-silo? "/etc/passwd" {:name :notes, :path notes})))
    (testing "path traversal is canonicalized before the check"
      (is (not (silo/in-silo? (str notes "/../escape.org")
                              {:name :notes, :path notes}))))))
