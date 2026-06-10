(ns denote-mono.search.interface-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [denote-mono.search.interface :as search])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:dynamic *silo* nil)

(defn- silo-fixture
  [f]
  (let [root (str (Files/createTempDirectory "denote-search-test"
                                             (make-array FileAttribute 0)))]
    (spit (str root "/20240101T000000--alpha__clojure.org") "alpha")
    (spit (str root "/20240102T000000==1--beta__notes.org") "beta")
    (spit (str root "/20240103T000000--gamma__clojure_notes.md") "gamma")
    (spit (str root "/--no-id__kw.org") "no id")
    (spit (str root "/not-denote.org") "ignored")
    (binding [*silo* {:name :test, :path root}] (f))))

(use-fixtures :each silo-fixture)

(defn- context [] {:silo *silo*, :config {}})

(defn- titles [notes] (mapv #(get-in % [:filename :title]) notes))

(deftest list-notes-test
  (testing "valid Denote names only, including identifier-less ones"
    (let [notes (search/list-notes (context) {} {})]
      (is (= #{"alpha" "beta" "gamma" "no-id"} (set (titles notes))))
      (is (every? :relative-path notes))
      (is (every? #(= :test (:silo %)) notes)))))

(deftest list-notes-filters-test
  (testing ":id filter requires identifier"
    (is (= ["alpha"]
           (titles (search/list-notes (context) {:id "20240101T000000"} {})))))
  (testing ":keyword filter"
    (is (= #{"alpha" "gamma"}
           (set (titles
                  (search/list-notes (context) {:keyword "clojure"} {}))))))
  (testing ":signature filter"
    (is (= ["beta"]
           (titles (search/list-notes (context) {:signature "1"} {})))))
  (testing ":title regex filter"
    (is (= ["gamma"] (titles (search/list-notes (context) {:title "gam"} {})))))
  (testing ":match regex on basename"
    (is (= ["gamma"]
           (titles (search/list-notes (context) {:match "\\.md$"} {})))))
  (testing ":query free-text filter is case-insensitive on relative path"
    (is (= ["alpha"]
           (titles (search/list-notes (context) {:query "ALPHA"} {}))))))

(deftest sort-notes-test
  (let [notes (search/list-notes (context) {} {})]
    (testing "sort by identifier puts identifier-less notes last"
      (is (= ["alpha" "beta" "gamma" "no-id"]
             (titles (search/sort-notes notes :identifier {})))))
    (testing "sort by title"
      (is (= ["alpha" "beta" "gamma" "no-id"]
             (titles (search/sort-notes notes :title {})))))
    (testing "sort by signature"
      (is (= "beta"
             (first (titles (search/sort-notes notes :signature {}))))))))
