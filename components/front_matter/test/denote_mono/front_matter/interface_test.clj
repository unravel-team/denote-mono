(ns denote-mono.front-matter.interface-test
  "Format fixtures ported from denote-test.el dt-denote--format-front-matter
  (with denote-date-format pinned to %Y-%m-%d, here the Java pattern
  yyyy-MM-dd). Parse and rewrite tests are CLI-specific."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [denote-mono.front-matter.interface :as front-matter]))

(def ^:private all-present
  {:present-even-if-empty [:title :keywords :signature :date :identifier],
   :date-format "yyyy-MM-dd"})

(def ^:private filled
  {:title "Some test",
   :date "2023-06-05",
   :keywords ["one" "two"],
   :identifier "20230605T102234",
   :signature "sig"})

(def ^:private empty-meta
  {:title "", :date "2024-01-01", :keywords [], :identifier "", :signature ""})

(deftest format-text-test
  (is (= (str/join "\n"
                   ["title:      " "date:       2024-01-01" "tags:       "
                    "identifier: " "signature:  "
                    "---------------------------\n\n"])
         (front-matter/format :text empty-meta all-present)))
  (is (= (str/join "\n"
                   ["title:      Some test" "date:       2023-06-05"
                    "tags:       one  two" "identifier: 20230605T102234"
                    "signature:  sig" "---------------------------\n\n"])
         (front-matter/format :text filled all-present))))

(deftest format-org-test
  (is (= (str/join "\n"
                   ["#+title:      " "#+date:       2024-01-01" "#+filetags:   "
                    "#+identifier: " "#+signature:  " "\n"])
         (front-matter/format :org empty-meta all-present)))
  (is (= (str/join "\n"
                   ["#+title:      Some test" "#+date:       2023-06-05"
                    "#+filetags:   :one:two:" "#+identifier: 20230605T102234"
                    "#+signature:  sig" "\n"])
         (front-matter/format :org filled all-present))))

(deftest format-markdown-yaml-test
  (is (= (str/join "\n"
                   ["---" "title:      \"\"" "date:       2024-01-01"
                    "tags:       []" "identifier: \"\"" "signature:  \"\"" "---"
                    "\n"])
         (front-matter/format :markdown-yaml empty-meta all-present)))
  (is (= (str/join "\n"
                   ["---" "title:      \"Some test\"" "date:       2023-06-05"
                    "tags:       [\"one\", \"two\"]"
                    "identifier: \"20230605T102234\"" "signature:  \"sig\""
                    "---" "\n"])
         (front-matter/format :markdown-yaml filled all-present))))

(deftest format-markdown-toml-test
  (is (= (str/join "\n"
                   ["+++" "title      = \"\"" "date       = 2024-01-01"
                    "tags       = []" "identifier = \"\"" "signature  = \"\""
                    "+++" "\n"])
         (front-matter/format :markdown-toml empty-meta all-present)))
  (is (= (str/join "\n"
                   ["+++" "title      = \"Some test\"" "date       = 2023-06-05"
                    "tags       = [\"one\", \"two\"]"
                    "identifier = \"20230605T102234\"" "signature  = \"sig\""
                    "+++" "\n"])
         (front-matter/format :markdown-toml filled all-present))))

(deftest format-drops-empty-lines-by-default
  (testing "signature line is dropped when empty (not present-even-if-empty)"
    (let [out (front-matter/format :org
                                   (assoc filled :signature "")
                                   {:date-format "yyyy-MM-dd"})]
      (is (not (str/includes? out "#+signature")))
      (is (str/includes? out "#+title")))))

(deftest format-sluggifies-keywords-and-signature
  (let [out (front-matter/format :org
                                 (assoc filled
                                   :keywords ["One Word" "two"]
                                   :signature "A Sig")
                                 {:date-format "yyyy-MM-dd"})]
    (is (str/includes? out ":oneword:two:"))
    (is (str/includes? out "#+signature:  a=sig"))))

(deftest parse-test
  (let [content (front-matter/format :org filled all-present)
        parsed (front-matter/parse :org content {})]
    (is (= "Some test" (:title parsed)))
    (is (= ["one" "two"] (:keywords parsed)))
    (is (= "20230605T102234" (:identifier parsed)))
    (is (= "sig" (:signature parsed)))
    (is (= "2023-06-05" (:date parsed))))
  (testing "markdown values trim quotes"
    (let [content (front-matter/format :markdown-yaml filled all-present)
          parsed (front-matter/parse :markdown-yaml content {})]
      (is (= "Some test" (:title parsed)))
      (is (= ["one" "two"] (:keywords parsed)))))
  (testing "absent front matter parses to empty map"
    (is (= {} (front-matter/parse :org "Just some text\n" {})))))

(deftest has-front-matter?-test
  (is (front-matter/has-front-matter? :org "#+title: x\n\nbody\n" {}))
  (is (not (front-matter/has-front-matter? :org "body only\n" {}))))

(deftest rewrite-sync-test
  (let [content (str (front-matter/format :org filled all-present)
                     "Body text\n")]
    (testing "modify existing line"
      (let [plan (front-matter/plan-rewrite :org
                                            content
                                            (assoc filled :title "New title")
                                            {:mode :sync,
                                             :date-format "yyyy-MM-dd"})
            rewritten (front-matter/apply-rewrite content plan)]
        (is (str/includes? rewritten "#+title:      New title"))
        (is (str/includes? rewritten "Body text"))
        (is (not (str/includes? rewritten "Some test")))))
    (testing "remove line for emptied component"
      (let [plan (front-matter/plan-rewrite :org
                                            content
                                            (assoc filled :signature "")
                                            {:mode :sync,
                                             :date-format "yyyy-MM-dd"})
            rewritten (front-matter/apply-rewrite content plan)]
        (is (not (str/includes? rewritten "#+signature")))))
    (testing "add missing line"
      (let [no-sig (str/replace content #"#\+signature.*\n" "")
            plan (front-matter/plan-rewrite :org
                                            no-sig
                                            filled
                                            {:mode :sync,
                                             :date-format "yyyy-MM-dd"})
            rewritten (front-matter/apply-rewrite no-sig plan)]
        (is (str/includes? rewritten "#+signature:  sig"))))
    (testing "update-existing never adds lines"
      (let [no-sig (str/replace content #"#\+signature.*\n" "")
            plan (front-matter/plan-rewrite :org
                                            no-sig
                                            filled
                                            {:mode :update-existing,
                                             :date-format "yyyy-MM-dd"})
            rewritten (front-matter/apply-rewrite no-sig plan)]
        (is (not (str/includes? rewritten "#+signature")))))
    (testing "mode :none plans nothing"
      (let [plan (front-matter/plan-rewrite :org
                                            content
                                            (assoc filled :title "X")
                                            {:mode :none})]
        (is (empty? (:actions plan)))
        (is (= content (front-matter/apply-rewrite content plan)))))
    (testing "unchanged metadata plans nothing"
      (let [plan (front-matter/plan-rewrite :org
                                            content
                                            filled
                                            {:mode :sync,
                                             :date-format "yyyy-MM-dd"})]
        (is (empty? (:actions plan)))))))
