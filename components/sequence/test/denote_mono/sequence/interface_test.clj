(ns denote-mono.sequence.interface-test
  "Ported from denote-sequence-test.el (dst-* tests)."
  (:require [clojure.test :refer [deftest is testing]]
            [denote-mono.sequence.interface :as sequence]))

(deftest numeric?-test
  ;; dst-denote-sequence-numeric-p
  (is (not (sequence/valid-for-scheme? :numeric "a")))
  (is (not (sequence/valid-for-scheme? :numeric "1a")))
  (is (not (sequence/valid-for-scheme? :numeric "1=a")))
  (is (not (sequence/valid-for-scheme? :numeric "hello")))
  (is (sequence/valid-for-scheme? :numeric "1"))
  (is (sequence/valid-for-scheme? :numeric "1=1")))

(deftest alphanumeric?-test
  ;; dst-denote-sequence-alphanumeric-p
  (is (not (sequence/valid-for-scheme? :alphanumeric "1=1")))
  (is (not (sequence/valid-for-scheme? :alphanumeric "1=a")))
  (is (not (sequence/valid-for-scheme? :alphanumeric "hello")))
  (is (sequence/valid-for-scheme? :alphanumeric "1"))
  (is (sequence/valid-for-scheme? :alphanumeric "1a")))

(deftest alphanumeric-delimited?-test
  ;; dst-denote-sequence-alphanumeric-delimited-p
  (is (not (sequence/valid-for-scheme? :alphanumeric-delimited "1a")))
  (is (not (sequence/valid-for-scheme? :alphanumeric-delimited "1=1")))
  (is (not (sequence/valid-for-scheme? :alphanumeric-delimited "1=a=a")))
  (is (not (sequence/valid-for-scheme? :alphanumeric-delimited "1=a=1")))
  (is (not (sequence/valid-for-scheme? :alphanumeric-delimited "hello")))
  (is (sequence/valid-for-scheme? :alphanumeric-delimited "1"))
  (is (sequence/valid-for-scheme? :alphanumeric-delimited "1=a"))
  (is (sequence/valid-for-scheme? :alphanumeric-delimited "1=a1b"))
  (is (sequence/valid-for-scheme? :alphanumeric-delimited "1=a1b=2a1"))
  (is (sequence/valid-for-scheme? :alphanumeric-delimited "1=zza1zb=2za1")))

(deftest valid?-test
  (is (sequence/valid? "1=1"))
  (is (sequence/valid? "1a"))
  (is (sequence/valid? "1=a1b"))
  (is (not (sequence/valid? "hello"))))

(deftest scheme-of-test
  ;; dst-denote-sequence-and-scheme-p
  (is (thrown? Exception (sequence/scheme-of "a" :numeric)))
  (is (= :numeric (sequence/scheme-of "1=1" :numeric)))
  (is (= :alphanumeric (sequence/scheme-of "1a" :numeric)))
  (is (= :alphanumeric-delimited (sequence/scheme-of "1=a" :numeric)))
  (testing "ambiguous single-level sequence uses the default scheme"
    (is (= :numeric (sequence/scheme-of "1" :numeric)))
    (is (= :alphanumeric (sequence/scheme-of "1" :alphanumeric)))
    (is (= :alphanumeric-delimited
           (sequence/scheme-of "1" :alphanumeric-delimited)))))

(deftest join-test
  ;; dst-denote-sequence-join
  (is (= "1=1=1=1" (sequence/join :numeric ["1" "1" "1" "1"])))
  (is (= "1a1a" (sequence/join :alphanumeric ["1" "a" "1" "a"])))
  (is (= "1=a" (sequence/join :alphanumeric-delimited ["1" "a"])))
  (is (= "1=a1" (sequence/join :alphanumeric-delimited ["1" "a" "1"])))
  (is (= "1=a1a" (sequence/join :alphanumeric-delimited ["1" "a" "1" "a"])))
  (is (= "1=a1a=1a1=a1a"
         (sequence/join :alphanumeric-delimited
                        ["1" "a" "1" "a" "1" "a" "1" "a" "1" "a"]))))

(deftest split-test
  ;; dst-denote-sequence-split
  (is (= ["1"] (sequence/split "1")))
  (is (= ["1" "1" "2"] (sequence/split "1=1=2")))
  (is (= ["1" "za" "5" "zx"] (sequence/split "1za5zx")))
  (is (= ["1" "za" "5" "zx"] (sequence/split "1=za5zx")))
  (is (= ["1" "a" "2" "b"] (sequence/split "1=a2b")))
  (is (= ["1" "a" "2" "b" "1" "c" "3"] (sequence/split "1=a2b=1c3"))))

(deftest depth-test
  (is (= 3 (sequence/depth "1=2=1")))
  (is (= 3 (sequence/depth "1b1"))))

(deftest convert-test
  ;; dst-denote-sequence-make-conversion
  (is (= "c" (sequence/convert-part "3" :alphanumeric)))
  (is (= "r" (sequence/convert-part "18" :alphanumeric)))
  (is (= "z" (sequence/convert-part "26" :alphanumeric)))
  (is (= "za" (sequence/convert-part "27" :alphanumeric)))
  (is (= "zzzzz" (sequence/convert-part "130" :alphanumeric)))
  (is (= "zzzzza" (sequence/convert-part "131" :alphanumeric)))
  (is (= "3" (sequence/convert-part "c" :numeric)))
  (is (= "18" (sequence/convert-part "r" :numeric)))
  (is (= "26" (sequence/convert-part "z" :numeric)))
  (is (= "27" (sequence/convert-part "za" :numeric)))
  (is (= "130" (sequence/convert-part "zzzzz" :numeric)))
  (is (= "131" (sequence/convert-part "zzzzza" :numeric)))
  (is (= "1a2" (sequence/convert "1=1=2" :alphanumeric)))
  (is (= "1=1=2" (sequence/convert "1a2" :numeric)))
  (is (= "1za2zzc" (sequence/convert "1=27=2=55" :alphanumeric)))
  (is (= "1=za2zzc" (sequence/convert "1=27=2=55" :alphanumeric-delimited)))
  (is (= "1=27=2=55" (sequence/convert "1za2zzc" :numeric)))
  (is (= "1a2b4a" (sequence/convert "1=1=2=2=4=1" :alphanumeric)))
  (is (= "1=a2b=4a" (sequence/convert "1=1=2=2=4=1" :alphanumeric-delimited)))
  (is (= "1=1=2=2=4=1" (sequence/convert "1a2b4a" :numeric)))
  ;; dst-denote-sequence--number-to-alpha-complete fixtures
  (is (= "1a1a1a1" (sequence/convert "1=1=1=1=1=1=1" :alphanumeric)))
  (is (= "1=a1a=1a1"
         (sequence/convert "1=1=1=1=1=1=1" :alphanumeric-delimited))))

(deftest increment-part-test
  ;; dst-denote-sequence-increment-partial
  (is (= "2" (sequence/increment-part "1")))
  (is (= "b" (sequence/increment-part "a")))
  (is (= "za" (sequence/increment-part "z")))
  (is (= "zza" (sequence/increment-part "zz")))
  (is (= "bbcza" (sequence/increment-part "bbcz")))
  (is (thrown? Exception (sequence/increment-part "1=1")))
  (is (thrown? Exception (sequence/increment-part "1a"))))

(deftest decrement-part-test
  ;; dst-denote-sequence-decrement-partial
  (is (nil? (sequence/decrement-part "1")))
  (is (nil? (sequence/decrement-part "a")))
  (is (= "1" (sequence/decrement-part "2")))
  (is (= "a" (sequence/decrement-part "b")))
  (is (= "z" (sequence/decrement-part "za")))
  (is (= "zz" (sequence/decrement-part "zza")))
  (is (= "bbcz" (sequence/decrement-part "bbcza")))
  (is (thrown? Exception (sequence/decrement-part "1a"))))

(deftest infer-sibling-test
  ;; dst-denote-sequence--infer-sibling
  (is (= "2" (sequence/infer-sibling "1" :next :numeric)))
  (is (= "1b" (sequence/infer-sibling "1a" :next :numeric)))
  (is (= "1za" (sequence/infer-sibling "1z" :next :numeric)))
  (is (= "1=2" (sequence/infer-sibling "1=1" :next :numeric)))
  (is (= "1" (sequence/infer-sibling "2" :previous :numeric)))
  (is (= "1a" (sequence/infer-sibling "1b" :previous :numeric)))
  (is (= "1z" (sequence/infer-sibling "1za" :previous :numeric)))
  (is (= "1=1" (sequence/infer-sibling "1=2" :previous :numeric)))
  (is (nil? (sequence/infer-sibling "1" :previous :numeric)))
  (is (nil? (sequence/infer-sibling "1=1" :previous :numeric)))
  (is (nil? (sequence/infer-sibling "1a" :previous :numeric))))

(def ^:private numeric-sequences
  ["1" "1=1" "1=1=1" "1=1=2" "1=2" "1=2=1" "1=2=1=1" "2" "10" "10=1" "10=1=1"
   "10=2" "10=10" "10=10=1"])

(deftest get-new-numeric-test
  ;; dst-denote-sequence--get-new-exhaustive, numeric block
  (let [scheme :numeric
        seqs numeric-sequences]
    (is (= "11" (sequence/next-parent seqs scheme)))
    (is (= "1=3" (sequence/next-child seqs "1" scheme)))
    (is (= "1=1=3" (sequence/next-child seqs "1=1" scheme)))
    (is (= "1=1=2=1" (sequence/next-child seqs "1=1=2" scheme)))
    (is (= "1=2=2" (sequence/next-child seqs "1=2" scheme)))
    (is (= "1=2=1=2" (sequence/next-child seqs "1=2=1" scheme)))
    (is (= "2=1" (sequence/next-child seqs "2" scheme)))
    (is (thrown? Exception (sequence/next-child seqs "11" scheme)))
    (is (= "11" (sequence/next-sibling seqs "1" scheme)))
    (is (= "1=3" (sequence/next-sibling seqs "1=1" scheme)))
    (is (= "1=1=3" (sequence/next-sibling seqs "1=1=1" scheme)))
    (is (= "1=1=3" (sequence/next-sibling seqs "1=1=2" scheme)))
    (is (= "1=3" (sequence/next-sibling seqs "1=2" scheme)))
    (is (= "1=2=2" (sequence/next-sibling seqs "1=2=1" scheme)))
    (is (= "11" (sequence/next-sibling seqs "2" scheme)))
    (is (thrown? Exception (sequence/next-sibling seqs "12" scheme)))))

(deftest get-new-alphanumeric-test
  ;; dst-denote-sequence--get-new-exhaustive, alphanumeric block
  (let [scheme :alphanumeric
        seqs ["1" "1a" "1a1" "1a2" "1b" "1b1" "1b1a" "2" "10" "10a" "10b"]]
    (is (= "11" (sequence/next-parent seqs scheme)))
    (is (= "1c" (sequence/next-child seqs "1" scheme)))
    (is (= "1a3" (sequence/next-child seqs "1a" scheme)))
    (is (= "1a2a" (sequence/next-child seqs "1a2" scheme)))
    (is (= "1b2" (sequence/next-child seqs "1b" scheme)))
    (is (= "1b1b" (sequence/next-child seqs "1b1" scheme)))
    (is (= "2a" (sequence/next-child seqs "2" scheme)))
    (is (thrown? Exception (sequence/next-child seqs "11" scheme)))
    (is (= "11" (sequence/next-sibling seqs "1" scheme)))
    (is (= "1c" (sequence/next-sibling seqs "1a" scheme)))
    (is (= "1a3" (sequence/next-sibling seqs "1a1" scheme)))
    (is (= "1a3" (sequence/next-sibling seqs "1a2" scheme)))
    (is (= "1c" (sequence/next-sibling seqs "1b" scheme)))
    (is (= "1b2" (sequence/next-sibling seqs "1b1" scheme)))
    (is (= "11" (sequence/next-sibling seqs "2" scheme)))
    (is (thrown? Exception (sequence/next-sibling seqs "12" scheme)))))

(deftest get-new-alphanumeric-delimited-test
  ;; dst-denote-sequence--get-new-exhaustive, alphanumeric-delimited block
  (let [scheme :alphanumeric-delimited
        seqs ["1" "1=a" "1=a1" "1=a2" "1=b" "1=b1" "1=b1a" "2" "10" "10=a"
              "10=b"]]
    (is (= "11" (sequence/next-parent seqs scheme)))
    (is (= "1=c" (sequence/next-child seqs "1" scheme)))
    (is (= "1=a3" (sequence/next-child seqs "1=a" scheme)))
    (is (= "1=a2a" (sequence/next-child seqs "1=a2" scheme)))
    (is (= "1=b2" (sequence/next-child seqs "1=b" scheme)))
    (is (= "1=b1b" (sequence/next-child seqs "1=b1" scheme)))
    (is (= "2=a" (sequence/next-child seqs "2" scheme)))
    (is (thrown? Exception (sequence/next-child seqs "11" scheme)))
    (is (= "11" (sequence/next-sibling seqs "1" scheme)))
    (is (= "1=c" (sequence/next-sibling seqs "1=a" scheme)))
    (is (= "1=a3" (sequence/next-sibling seqs "1=a1" scheme)))
    (is (= "1=a3" (sequence/next-sibling seqs "1=a2" scheme)))
    (is (= "1=c" (sequence/next-sibling seqs "1=b" scheme)))
    (is (= "1=b2" (sequence/next-sibling seqs "1=b1" scheme)))
    (is (= "11" (sequence/next-sibling seqs "2" scheme)))
    (is (thrown? Exception (sequence/next-sibling seqs "12" scheme)))))

(deftest relatives-test
  ;; relative lookups from the numeric exhaustive block, on sequences
  (let [scheme :numeric
        seqs numeric-sequences]
    (is (= "1=2=1" (sequence/relative seqs "1=2=1=1" :parent scheme)))
    (is (= "10=1" (sequence/relative seqs "10=1=1" :parent scheme)))
    (is (= "10=10" (sequence/relative seqs "10=10=1" :parent scheme)))
    (is (= ["1" "1=2" "1=2=1"]
           (sequence/relative seqs "1=2=1=1" :all-parents scheme)))
    (is (= ["10" "10=1"] (sequence/relative seqs "10=1=1" :all-parents scheme)))
    (is (= #{"1=2"} (set (sequence/relative seqs "1=1" :siblings scheme))))
    (is (= #{"10=2" "10=10"}
           (set (sequence/relative seqs "10=1" :siblings scheme))))
    (is (= #{"1=1" "1=2"} (set (sequence/relative seqs "1" :children scheme))))
    (is (= #{"10=1" "10=2" "10=10"}
           (set (sequence/relative seqs "10" :children scheme))))
    (is (= #{"1=1=1" "1=1=2"}
           (set (sequence/relative seqs "1=1" :all-children scheme))))))

(deftest keep-siblings-test
  ;; dst-denote-sequence--keep-siblings
  (is (=
        ["1c" "1d" "1e" "1f"]
        (sequence/keep-siblings :greater "1b" ["1f" "1b" "1a" "1d" "1e" "1c"])))
  (is (= ["1a" "1b" "1c"]
         (sequence/keep-siblings :lesser "1d" ["1f" "1b" "1a" "1d" "1e" "1c"])))
  (is (empty?
        (sequence/keep-siblings :greater "1f" ["1f" "1b" "1a" "1d" "1e" "1c"])))
  (is (empty?
        (sequence/keep-siblings :lesser "1a" ["1f" "1b" "1a" "1d" "1e" "1c"]))))

(deftest sort-sequences-test
  (is (= ["1" "1=1" "1=2" "2" "10" "10=10"]
         (sequence/sort-sequences ["10" "1=2" "2" "10=10" "1" "1=1"]))))
