(ns denote-mono.note.interface-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [denote-mono.note.interface :as note])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:dynamic *root* nil)

(defn- note-fixture
  [f]
  (let [root (str (Files/createTempDirectory "denote-note-test"
                                             (make-array FileAttribute 0)))]
    (binding [*root* root] (f))))

(use-fixtures :each note-fixture)

(defn- context [] {:silo {:name :test, :path *root*}, :config {}})

(deftest plan-new-test
  (let [plan (note/plan-new {:title "My Note",
                             :keywords ["one" "two"],
                             :date "2024-12-09 10:55:50"}
                            (context)
                            {})]
    (is (= "20241209T105550" (:identifier plan)))
    (is (str/ends-with? (:path plan) "/20241209T105550--my-note__one_two.org"))
    (is (str/includes? (:content plan) "#+title:      My Note"))
    (is (str/includes? (:content plan) "#+filetags:   :one:two:"))
    (is (str/includes? (:content plan) "#+identifier: 20241209T105550"))))

(deftest identifier-uniqueness-test
  (testing "colliding date identifiers increment seconds"
    (let [first-note (note/create (note/plan-new {:title "a",
                                                  :date "2024-12-09 10:55:50"}
                                                 (context)
                                                 {})
                                  {})
          second-plan (note/plan-new {:title "b", :date "2024-12-09 10:55:50"}
                                     (context)
                                     {})]
      (is (:created first-note))
      (is (= "20241209T105551" (:identifier second-plan))))))

(deftest create-collision-test
  (let [plan (note/plan-new {:title "x", :date "2024-01-01 00:00:00"}
                            (context)
                            {})]
    (spit (:path plan) "occupied")
    (is (thrown? Exception (note/create plan {})))))

(deftest subdir-test
  (testing "subdirectory inside the silo"
    (let [plan (note/plan-new
                 {:title "x", :date "2024-01-01 00:00:00", :subdir "projects"}
                 (context)
                 {})]
      (is (str/includes? (:path plan) "/projects/"))))
  (testing "subdirectory escaping the silo is rejected"
    (is (thrown?
          Exception
          (note/plan-new {:title "x", :subdir "../escape"} (context) {})))))

(deftest file-type-test
  (let [plan (note/plan-new
               {:title "x", :date "2024-01-01 00:00:00", :type :markdown-yaml}
               (context)
               {})]
    (is (str/ends-with? (:path plan) ".md"))
    (is (str/starts-with? (:content plan) "---"))))
