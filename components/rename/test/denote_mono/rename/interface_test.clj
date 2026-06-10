(ns denote-mono.rename.interface-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [denote-mono.rename.interface :as rename])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:dynamic *root* nil)

(defn- rename-fixture
  [f]
  (let [root (str (Files/createTempDirectory "denote-rename-test"
                                             (make-array FileAttribute 0)))]
    (spit (str root "/20240101T000000--alpha__one.org")
          (str "#+title:      alpha\n"
               "#+date:       [2024-01-01 Mon 00:00]\n" "#+filetags:   :one:\n"
               "#+identifier: 20240101T000000\n" "\nBody\n"))
    (spit (str root "/20240102T000000--beta__two.org") "no front matter\n")
    (spit (str root "/plain-note.org") "plain\n")
    (binding [*root* root] (f))))

(use-fixtures :each rename-fixture)

(defn- context [] {:silo {:name :test, :path *root*}, :config {}})

(deftest plan-rename-title-test
  (let [file (str *root* "/20240101T000000--alpha__one.org")
        plan (rename/plan-rename file {:title "New Alpha"} (context) {})]
    (testing "destination renames only the title component"
      (is (str/ends-with? (:destination plan)
                          "/20240101T000000--new-alpha__one.org")))
    (testing "old and new components are recorded"
      (is (= "alpha" (get-in plan [:old :title])))
      (is (= "New Alpha" (get-in plan [:new :title]))))
    (testing "front matter sync plans a title line change"
      (is (some #(= :title (:component %))
                (get-in plan [:content-change :actions]))))))

(deftest plan-rename-keep-and-remove-test
  (let [file (str *root* "/20240101T000000--alpha__one.org")]
    (testing "omitted components are kept"
      (let [plan (rename/plan-rename file {:title "x"} (context) {})]
        (is (= ["one"] (get-in plan [:new :keywords])))))
    (testing "explicit empty string removes the component"
      (let [plan (rename/plan-rename file {:title ""} (context) {})]
        (is (nil? (get-in plan [:new :title])))
        (is (str/ends-with? (:destination plan)
                            "/20240101T000000__one.org"))))))

(deftest plan-rename-generates-id-test
  (testing "file without identifier gets one from --date"
    (let [plan (rename/plan-rename (str *root* "/plain-note.org")
                                   {:date "2024-12-09 10:55:50",
                                    :title "plain note"}
                                   (context)
                                   {:front-matter :none})]
      (is (= "20241209T105550" (get-in plan [:new :identifier])))
      (is (str/ends-with? (:destination plan)
                          "/20241209T105550--plain-note.org")))))

(deftest plan-rename-containment-test
  (testing "file outside the silo is rejected"
    (is (thrown? Exception
                 (rename/plan-rename "/etc/hosts" {:title "x"} (context) {})))))

(deftest apply-plan-test
  (let [file (str *root* "/20240101T000000--alpha__one.org")
        plan (rename/plan-rename file {:title "Renamed"} (context) {})
        applied (rename/apply-plan (rename/validate-plan plan {}) {})]
    (is (:applied applied))
    (is (.exists (java.io.File. ^String (:destination plan))))
    (testing "front matter was rewritten in the new file"
      (is (str/includes? (slurp (:destination plan)) "#+title:      Renamed")))
    (testing "body preserved"
      (is (str/includes? (slurp (:destination plan)) "Body")))))

(deftest validate-plan-collision-test
  (let [file (str *root* "/20240102T000000--beta__two.org")
        ;; alpha's filename already exists
        plan (rename/plan-rename file
                                 {:identifier "20240101T000000",
                                  :title "alpha",
                                  :keywords ["one"]}
                                 (context)
                                 {:front-matter :none})]
    (is (thrown? Exception (rename/validate-plan plan {})))))

(deftest front-matter-none-mode-test
  (let [file (str *root* "/20240102T000000--beta__two.org")
        plan (rename/plan-rename file
                                 {:title "Gamma"}
                                 (context)
                                 {:front-matter :none})]
    (is (nil? (:content-change plan)))))

(deftest batch-keyword-operations-test
  (let [files [(str *root* "/20240101T000000--alpha__one.org")
               (str *root* "/20240102T000000--beta__two.org")]]
    (testing "add keyword to all files"
      (let [{:keys [plans errors]} (rename/plan-batch files
                                                      {:add-keyword "shared"}
                                                      (context)
                                                      {:front-matter :none})]
        (is (empty? errors))
        (is (every? #(some #{"shared"} (get-in % [:new :keywords])) plans))))
    (testing "remove keyword"
      (let [{:keys [plans]} (rename/plan-batch files
                                               {:remove-keyword "one"}
                                               (context)
                                               {:front-matter :none})]
        (is (nil? (get-in (first plans) [:new :keywords])))))
    (testing "replace keywords"
      (let [{:keys [plans]} (rename/plan-batch files
                                               {:replace-keywords ["a" "b"]}
                                               (context)
                                               {:front-matter :none})]
        (is (every? #(= ["a" "b"] (get-in % [:new :keywords])) plans))))))

(deftest batch-duplicate-destination-test
  (let [files [(str *root* "/20240101T000000--alpha__one.org")
               (str *root* "/20240102T000000--beta__two.org")]
        {:keys [errors]} (rename/plan-batch files
                                            {:identifier "20240909T090909",
                                             :title "same",
                                             :keywords ["kw"]}
                                            (context)
                                            {:front-matter :none})]
    (is (seq errors))
    (is (every? #(= :collision (:type %)) errors))))

(deftest from-front-matter-test
  (let [file (str *root* "/20240101T000000--alpha__one.org")]
    ;; Change the title and identifier lines; the identifier line, not the
    ;; date line, must drive the new filename ID.
    (spit file
          (str "#+title:      From FM\n"
               "#+date:       [2030-12-31 Tue 00:00]\n" "#+filetags:   :fmkw:\n"
               "#+identifier: 20250505T050505\n" "\nBody\n"))
    (let [{:keys [plans errors]} (rename/plan-batch [file]
                                                    {:from-front-matter true}
                                                    (context)
                                                    {:front-matter :none})
          plan (first plans)]
      (is (empty? errors))
      (is (= "20250505T050505" (get-in plan [:new :identifier])))
      (is (= "From FM" (get-in plan [:new :title])))
      (is (= ["fmkw"] (get-in plan [:new :keywords])))
      (is (str/ends-with? (:destination plan)
                          "/20250505T050505--from-fm__fmkw.org")))))

(deftest apply-batch-stops-on-failure-test
  (spit (str *root* "/20240103T000000--x__two.org") "occupied")
  (let [file-a (str *root* "/20240101T000000--alpha__one.org")
        file-b (str *root* "/20240102T000000--beta__two.org")
        plan-a (rename/plan-rename file-a
                                   {:title "ok"}
                                   (context)
                                   {:front-matter :none})
        ;; plan-b collides with the occupied path at apply time
        plan-b (rename/plan-rename file-b
                                   {:identifier "20240103T000000", :title "x"}
                                   (context)
                                   {:front-matter :none})
        {:keys [applied failed]} (rename/apply-batch [plan-a plan-b] {})]
    (is (= 1 (count applied)))
    (is failed)))
