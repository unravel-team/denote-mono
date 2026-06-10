(ns denote-mono.file-type.interface-test
  "Ported from denote/tests/denote-test.el (dt-denote-filetype-heuristics,
  dt-denote-file-has-supported-extension-p, extension listings)."
  (:require [clojure.test :refer [deftest is testing]]
            [denote-mono.file-type.interface :as file-type]))

(deftest detect-by-extension-test
  ;; dt-denote-filetype-heuristics
  (is (nil? (file-type/detect "20231010T105034--f__kw" nil)))
  (is (= :org (file-type/detect "20231010T105034--f__kw.org" nil)))
  (is (= :org (file-type/detect "20231010T105034--f__kw.org.gpg" nil)))
  (is (= :org (file-type/detect "20231010T105034--f__kw.org.age" nil)))
  (is (= :text (file-type/detect "20231010T105034--f__kw.txt" nil)))
  (is (= :text (file-type/detect "20231010T105034--f__kw.txt.gpg" nil)))
  (is (= :text (file-type/detect "20231010T105034--f__kw.txt.age" nil))))

(deftest detect-markdown-ambiguity-test
  (testing "content disambiguates YAML vs TOML"
    (is (= :markdown-yaml (file-type/detect "a.md" "---\ntitle: x\n---\n")))
    (is (= :markdown-toml
           (file-type/detect "a.md" "+++\ntitle = \"x\"\n+++\n"))))
  (testing "no content signal falls back to configured type when it matches .md"
    (is (= :markdown-toml
           (file-type/detect "a.md" "plain text" {:file-type :markdown-toml})))
    (is (= :markdown-yaml
           (file-type/detect "a.md" "plain text" {:file-type :markdown-yaml}))))
  (testing "otherwise first matching .md type wins"
    (is (= :markdown-yaml (file-type/detect "a.md" nil)))
    (is (= :markdown-yaml
           (file-type/detect "a.md" "plain text" {:file-type :org})))))

(deftest supported-extension?-test
  ;; dt-denote-file-has-supported-extension-p
  (is (file-type/supported-extension?
        "20230522T154900==sig--test__keyword.txt"))
  (is (file-type/supported-extension? "a.org.gpg"))
  (is (file-type/supported-extension? "a.md.age"))
  (is (not (file-type/supported-extension?
             "20230522T154900==sig--test__keyword")))
  (is (not (file-type/supported-extension? "a.pdf"))))

(deftest extensions-test
  ;; dt-denote-file-type-extensions and -with-encryption
  (let [extensions (file-type/extensions)]
    (is (some #{".md"} extensions))
    (is (some #{".org"} extensions))
    (is (some #{".txt"} extensions))
    (is (= (count extensions) (count (distinct extensions)))))
  (let [extensions (file-type/extensions-with-encryption)]
    (is (some #{".org.gpg"} extensions))
    (is (some #{".md.age"} extensions))))

(deftest extension-for-test
  (is (= ".org" (file-type/extension-for :org)))
  (is (= ".md" (file-type/extension-for :markdown-yaml)))
  (is (= ".md" (file-type/extension-for :markdown-toml)))
  (is (= ".txt" (file-type/extension-for :text)))
  (is (nil? (file-type/extension-for :unknown))))

(deftest properties-test
  (testing "registry exposes link fields for backlink matching"
    (let [org (file-type/properties :org)]
      (is (= "[denote:%s]" (:link-retrieval-format org)))
      (is (re-find (:link-in-context-regexp org)
                   "[[denote:20240101T000000][title]]")))
    (let [md (file-type/properties :markdown-yaml)]
      (is (= "(denote:%s)" (:link-retrieval-format md)))
      (is (re-find (:link-in-context-regexp md)
                   "[title](denote:20240101T000000)")))))

(deftest text-file?-test
  (is (file-type/text-file? "a.org"))
  (is (file-type/text-file? "a.md.gpg"))
  (is (not (file-type/text-file? "a.bin"))))
