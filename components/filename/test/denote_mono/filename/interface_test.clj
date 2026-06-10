(ns denote-mono.filename.interface-test
  "Ported from denote/tests/denote-test.el (retrieve, validity, format,
  extension tests) plus CLI-specific fixtures from the implementation spec."
  (:require [clojure.test :refer [deftest is testing]]
            [denote-mono.filename.interface :as filename]))

(deftest date-identifier?-test
  ;; dt-denote-date-identifier-p
  (is (filename/date-identifier? "20240901T090910"))
  (is (not (filename/date-identifier?
             "20240901T090910-not-identifier-format"))))

(deftest retrieve-identifier-test
  ;; dt-denote-retrieve-filename-identifier
  (is (nil? (filename/extract
              "/path/to/testing/--this-is-a-test-reordered__denote_testing.org"
              :identifier)))
  (doseq
    [f ["/path/to/testing/20240610T194654--this-is-a-test-reordered__denote_testing.org"
        "/path/to/testing/20240610T194654==signature--this-is-a-test-reordered__denote_testing.org"
        "/path/to/testing/--this-is-a-test-reordered__denote_testing@@20240610T194654.org"
        "/path/to/testing/__denote_testing--this-is-a-test-reordered@@20240610T194654.org"
        "/path/to/testing/__denote_testing@@20240610T194654--this-is-a-test-reordered.org"
        "/path/to/testing/==signature__denote_testing@@20240610T194654--this-is-a-test-reordered.org"]]
    (is (= "20240610T194654" (filename/extract f :identifier)) f)))

(deftest retrieve-title-test
  ;; dt-denote-retrieve-filename-title
  (is (nil? (filename/extract
              "/path/to/testing/20240610T194654__denote_testing.org"
              :title)))
  (doseq
    [f ["/path/to/testing/20240610T194654--this-is-a-test-reordered__denote_testing.org"
        "/path/to/testing/20240610T194654==signature--this-is-a-test-reordered__denote_testing.org"
        "/path/to/testing/--this-is-a-test-reordered__denote_testing@@20240610T194654.org"
        "/path/to/testing/__denote_testing--this-is-a-test-reordered@@20240610T194654.org"
        "/path/to/testing/__denote_testing@@20240610T194654--this-is-a-test-reordered.org"
        "/path/to/testing/==signature__denote_testing@@20240610T194654--this-is-a-test-reordered.org"]]
    (is (= "this-is-a-test-reordered" (filename/extract f :title)) f)))

(deftest retrieve-keywords-test
  ;; dt-denote-retrieve-filename-keywords
  (is (nil? (filename/extract
              "/path/to/testing/20240610T194654--this-is-a-test-reordered.org"
              :keywords)))
  (doseq
    [f ["/path/to/testing/20240610T194654--this-is-a-test-reordered__denote_testing.org"
        "/path/to/testing/20240610T194654==signature--this-is-a-test-reordered__denote_testing.org"
        "/path/to/testing/--this-is-a-test-reordered__denote_testing@@20240610T194654.org"
        "/path/to/testing/__denote_testing--this-is-a-test-reordered@@20240610T194654.org"
        "/path/to/testing/__denote_testing@@20240610T194654--this-is-a-test-reordered.org"
        "/path/to/testing/==signature__denote_testing@@20240610T194654--this-is-a-test-reordered.org"]]
    (is (= "denote_testing" (filename/extract f :keywords)) f)))

(deftest retrieve-signature-test
  ;; dt-denote-retrieve-filename-signature
  (is
    (nil?
      (filename/extract
        "/path/to/testing/20240610T194654--this-is-a-test-reordered__denote_testing.org"
        :signature)))
  (doseq
    [f ["/path/to/testing/20240610T194654==signature--this-is-a-test-reordered__denote_testing.org"
        "/path/to/testing/--this-is-a-test-reordered==signature__denote_testing@@20240610T194654.org"
        "/path/to/testing/__denote_testing--this-is-a-test-reordered==signature@@20240610T194654.org"
        "/path/to/testing/__denote_testing@@20240610T194654--this-is-a-test-reordered==signature.org"
        "/path/to/testing/==signature__denote_testing@@20240610T194654--this-is-a-test-reordered.org"]]
    (is (= "signature" (filename/extract f :signature)) f)))

(deftest valid-denote-filename?-test
  ;; dt-denote-file-has-denoted-filename-p
  (is (filename/valid-denote-filename? "20230522T154900--test__keyword.txt"))
  (is (not (filename/valid-denote-filename? "hello")))
  (testing "valid name without identifier (spec 13.1)"
    (is (filename/valid-denote-filename? "--title__kw.org")))
  (testing "hidden dotfiles are never valid"
    (is (not (filename/valid-denote-filename? ".20230522T154900--test.txt")))))

(deftest extension-test
  ;; dt-denote-get-file-extension
  (is (= ""
         (filename/extension
           "20231010T105034--some-test-file__denote_testing")))
  (is (= ".org"
         (filename/extension
           "20231010T105034--some-test-file__denote_testing.org")))
  (is (= ".org.gpg"
         (filename/extension
           "20231010T105034--some-test-file__denote_testing.org.gpg")))
  (is (= ".org.age"
         (filename/extension
           "20231010T105034--some-test-file__denote_testing.org.age"))))

(deftest base-extension-test
  ;; dt-denote-get-file-extension-sans-encryption
  (is (= ""
         (filename/base-extension
           "20231010T105034--some-test-file__denote_testing")))
  (is (= ".org"
         (filename/base-extension
           "20231010T105034--some-test-file__denote_testing.org")))
  (is (= ".org"
         (filename/base-extension
           "20231010T105034--some-test-file__denote_testing.org.gpg")))
  (is (= ".org"
         (filename/base-extension
           "20231010T105034--some-test-file__denote_testing.org.age"))))

(deftest encryption-suffix-test
  (is (nil? (filename/encryption-suffix "a.org")))
  (is (= ".gpg" (filename/encryption-suffix "a.org.gpg")))
  (is (= ".age" (filename/encryption-suffix "a.org.age"))))

(deftest format-filename-test
  ;; dt-denote-format-file-name
  (let [dir "/tmp/test-denote/"
        id "20231128T055311"
        kws ["one" "two"]
        title "Some test"
        ext ".org"]
    (is (thrown? Exception
                 (filename/format-filename {:directory nil,
                                            :identifier id,
                                            :keywords kws,
                                            :title title,
                                            :extension ext,
                                            :signature ""})))
    (is (thrown? Exception
                 (filename/format-filename {:directory "",
                                            :identifier id,
                                            :keywords kws,
                                            :title title,
                                            :extension ext,
                                            :signature ""})))
    (is (thrown? Exception
                 (filename/format-filename {:directory "/tmp/test-denote",
                                            :identifier id,
                                            :keywords kws,
                                            :title title,
                                            :extension ext,
                                            :signature ""})))
    (is (thrown? Exception
                 (filename/format-filename {:directory dir,
                                            :identifier "",
                                            :keywords nil,
                                            :title "",
                                            :extension ext,
                                            :signature ""})))
    (is (= "/tmp/test-denote/--some-test__one_two.org"
           (filename/format-filename {:directory dir,
                                      :identifier nil,
                                      :keywords kws,
                                      :title title,
                                      :extension ext,
                                      :signature ""})))
    (is (= "/tmp/test-denote/--some-test__one_two.org"
           (filename/format-filename {:directory dir,
                                      :identifier "",
                                      :keywords kws,
                                      :title title,
                                      :extension ext,
                                      :signature ""})))
    (is (= "/tmp/test-denote/@@0123456--some-test__one_two.org"
           (filename/format-filename {:directory dir,
                                      :identifier "0123456",
                                      :keywords kws,
                                      :title title,
                                      :extension ext,
                                      :signature ""})))
    (is (= "/tmp/test-denote/20231128T055311--some-test__one_two.org"
           (filename/format-filename {:directory dir,
                                      :identifier id,
                                      :keywords kws,
                                      :title title,
                                      :extension ext,
                                      :signature ""})))
    (is (= "/tmp/test-denote/20231128T055311.org"
           (filename/format-filename {:directory dir,
                                      :identifier id,
                                      :keywords nil,
                                      :title "",
                                      :extension ext,
                                      :signature ""})))
    (is (= "/tmp/test-denote/20231128T055311.org"
           (filename/format-filename {:directory dir,
                                      :identifier id,
                                      :keywords nil,
                                      :title nil,
                                      :extension ext,
                                      :signature nil})))
    (is (= "/tmp/test-denote/20231128T055311==sig--some-test__one_two.org"
           (filename/format-filename {:directory dir,
                                      :identifier id,
                                      :keywords kws,
                                      :title title,
                                      :extension ext,
                                      :signature "sig"})))))

(deftest format-filename-options-test
  (let [base {:directory "/tmp/n/",
              :identifier "20231128T055311",
              :keywords ["b" "a"],
              :title "test",
              :extension ".org",
              :signature "sig"}]
    (testing "identifier-delimiter-always? keeps @@ on leading date identifiers"
      (is (= "/tmp/n/@@20231128T055311--test__a_b.org"
             (filename/format-filename (dissoc base :signature)
                                       {:identifier-delimiter-always? true}))))
    (testing "keywords sort by default, preserved when disabled"
      (is (= "/tmp/n/20231128T055311==sig--test__a_b.org"
             (filename/format-filename base)))
      (is (= "/tmp/n/20231128T055311==sig--test__b_a.org"
             (filename/format-filename base {:sort-keywords? false}))))
    (testing "component order config normalizes duplicates and missing entries"
      (is (= "/tmp/n/--test==sig@@20231128T055311__a_b.org"
             (filename/format-filename
               base
               {:components-order [:title :signature :title :identifier]})))
      (is (= "/tmp/n/__a_b@@20231128T055311==sig--test.org"
             (filename/format-filename base
                                       {:components-order [:keywords]}))))))

(deftest parse-test
  (let [parsed (filename/parse
                 "/notes/20231128T055311==1--title__kw_two.org.gpg")]
    (is (= "/notes/" (:directory parsed)))
    (is (= "20231128T055311==1--title__kw_two.org.gpg" (:basename parsed)))
    (is (= "20231128T055311==1--title__kw_two" (:stem parsed)))
    (is (= ".org.gpg" (:extension parsed)))
    (is (= ".org" (:extension/base parsed)))
    (is (= ".gpg" (:encryption-suffix parsed)))
    (is (= "20231128T055311" (:identifier parsed)))
    (is (true? (:identifier/date? parsed)))
    (is (= "1" (:signature parsed)))
    (is (= "title" (:title parsed)))
    (is (= ["kw" "two"] (:keywords parsed)))
    (is (= [:identifier :signature :title :keywords]
           (:components-order parsed)))
    (is (true? (:valid-denote-name? parsed))))
  (testing "reordered components"
    (let [parsed (filename/parse
                   "/x/__denote_testing@@20240610T194654--reordered.org")]
      (is (= [:keywords :identifier :title] (:components-order parsed)))
      (is (= "20240610T194654" (:identifier parsed)))
      (is (true? (:valid-denote-name? parsed)))))
  (testing "non-denote name"
    (let [parsed (filename/parse "/x/hello.org")]
      (is (false? (:valid-denote-name? parsed)))
      (is (nil? (:identifier parsed)))))
  (testing "no identifier still valid but not ID-addressable"
    (let [parsed (filename/parse "/x/--title__kw.org")]
      (is (true? (:valid-denote-name? parsed)))
      (is (nil? (:identifier parsed)))
      (is (nil? (:identifier/date? parsed))))))
