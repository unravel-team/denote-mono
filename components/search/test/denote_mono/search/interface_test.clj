(ns denote-mono.search.interface-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
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

(deftest grep-test
  (spit (str (:path *silo*) "/20240101T000000--alpha__clojure.org")
        "first line\nsecond with NEEDLE here\nthird\n")
  (let [matches (search/grep (context) "NEEDLE" {})]
    (is (= 1 (count matches)))
    (is (= 2 (:line-number (first matches))))
    (is (str/includes? (:line (first matches)) "NEEDLE"))))

(deftest links-and-backlinks-test
  (let [source (str (:path *silo*) "/20240101T000000--alpha__clojure.org")]
    (spit source "See [[denote:20240102T000000][beta note]] for more.\n")
    (testing "links resolves outgoing identifiers"
      (let [{:keys [identifiers notes]} (search/links (context) source {})]
        (is (= ["20240102T000000"] identifiers))
        (is (= "beta" (get-in (first notes) [:filename :title])))))
    (testing "backlinks finds the linking note"
      (let [notes (search/backlinks (context) "20240102T000000" {})]
        (is (= ["alpha"] (mapv #(get-in % [:filename :title]) notes)))))
    (testing "no backlinks for an unlinked id"
      (is (empty? (search/backlinks (context) "20240103T000000" {}))))))

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
