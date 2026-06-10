(ns denote-mono.slug.interface-test
  "Ported from denote/tests/denote-test.el (dt-denote-sluggify* and slug helpers)."
  (:require [clojure.test :refer [deftest is testing]]
            [denote-mono.slug.interface :as slug]))

(deftest keep-only-ascii-test
  ;; dt-denote-slug-keep-only-ascii
  (is (= "There are no-ASCII   characters   here  "
         (slug/keep-only-ascii "There are no-ASCII ： characters ｜ here 😀"))))

(deftest hyphenate-test
  ;; dt-denote-slug-hyphenate
  (is (= "This-is-a-test" (slug/hyphenate "__  This is   a    test  __  "))))

(deftest put-equals-test
  ;; Ddenote--slug-put-equals
  (is (= "This=is=a=test" (slug/put-equals "__  This is   a    test  __  "))))

(deftest slug-title-test
  ;; dt-denote-sluggify-title
  (is (= "this-is-test" (slug/slug-title "this-is-!@#test"))))

(deftest slug-signature-test
  ;; dt-denote-sluggify-signature
  (is (= "this=is=a=test"
         (slug/slug-signature "--- ___ !~!!$%^ This -iS- a tEsT ++ ?? "))))

(deftest slug-keyword-test
  ;; dt-denote-sluggify-keyword
  (is (= "thisisatest"
         (slug/slug-keyword "--- ___ !~!!$%^ This iS a - tEsT ++ ?? "))))

(deftest slug-component-test
  ;; dt-denote-sluggify
  (is (= "this-is-a-test"
         (slug/slug-component :title " ___ !~!!$%^ This iS a tEsT ++ ?? "))))

(deftest slug-keywords-test
  ;; dt-denote-sluggify-keywords
  (is (= ["oneone" "two" "three"]
         (slug/slug-keywords ["one !@# --- one" "   two" "__  three  __"]))))

(deftest slug-identifier-test
  (testing "identifier keeps content but removes query prefixes, brackets, dots"
    (is (= "20240101T120000" (slug/slug-identifier "20240101T120000")))
    (is (= "20240101T120000"
           (slug/slug-identifier "query-filenames:[20240101T120000]")))
    (is (= "abc123" (slug/slug-identifier "abc.123")))
    (is (= "abc" (slug/slug-identifier "abc==--__@@")))))

(deftest slug-component-rules-test
  (testing "consecutive token characters collapse and trailing tokens trim"
    (is (= "a=b" (slug/slug-component :signature "a==b")))
    (is (= "one-two" (slug/slug-component :title "one--two=__")))
    (is (= "ab" (slug/slug-component :keyword "a_b")))))
