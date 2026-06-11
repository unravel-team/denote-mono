(ns denote-mono.process.interface-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [denote-mono.process.interface :as process]))

(deftest available?-test
  (is (process/available? "ls"))
  (is (not (process/available? "definitely-not-a-real-tool-xyz"))))

(deftest run-test
  (testing "argv vector execution, no shell interpretation"
    (let [{:keys [exit out]} (process/run ["echo" "hello $HOME ; rm -rf"])]
      (is (zero? exit))
      (is (= "hello $HOME ; rm -rf" (str/trim out)))))
  (testing "non-zero exit is data, not an exception"
    (let [{:keys [exit]} (process/run ["false"])] (is (= 1 exit))))
  (testing "missing binary returns error data"
    (let [{:keys [exit error]} (process/run ["definitely-not-a-real-tool-xyz"])]
      (is (= :missing-binary error))
      (is (not (zero? exit)))))
  (testing "stdin can be supplied"
    (let [{:keys [exit out]} (process/run ["cat"] {:in "piped"})]
      (is (zero? exit))
      (is (= "piped" out))))
  (testing "working directory option"
    (let [{:keys [out]} (process/run ["pwd"] {:dir "/tmp"})]
      (is (str/includes? out "tmp")))))
