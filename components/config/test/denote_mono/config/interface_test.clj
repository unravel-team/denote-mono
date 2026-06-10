(ns denote-mono.config.interface-test
  (:require [clojure.test :refer [deftest is testing]]
            [denote-mono.config.interface :as config])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(deftest default-config-test
  (let [defaults (config/default-config)]
    (is (= [:identifier :signature :title :keywords]
           (get-in defaults [:filename :components-order])))
    (is (true? (get-in defaults [:filename :sort-keywords?])))
    (is (= :org (get-in defaults [:filename :file-type])))
    (is (= :numeric (get-in defaults [:sequence :scheme])))
    (is (= {} (:silos defaults)))))

(deftest config-path-test
  (testing "XDG_CONFIG_HOME wins"
    (is (= "/xdg/denote-mono/config.edn"
           (config/config-path {"XDG_CONFIG_HOME" "/xdg"}))))
  (testing "falls back to ~/.config"
    (is (= "/home/u/.config/denote-mono/config.edn"
           (config/config-path {"HOME" "/home/u"})))))

(deftest expand-home-test
  (is (= "/home/u/notes" (config/expand-home "~/notes" {"HOME" "/home/u"})))
  (is (= "/abs/notes" (config/expand-home "/abs/notes" {"HOME" "/home/u"}))))

(deftest load-config-test
  (let [dir (str (Files/createTempDirectory "denote-config-test"
                                            (make-array FileAttribute 0)))
        path (str dir "/config.edn")]
    (testing "missing file yields defaults"
      (is (= (config/default-config) (config/load-config {:path path}))))
    (testing "user config deep-merges over defaults"
      (spit path
            (pr-str {:default-silo :notes,
                     :silos {:notes {:path "~/n"}},
                     :filename {:sort-keywords? false}}))
      (let [loaded (config/load-config {:path path})]
        (is (= :notes (:default-silo loaded)))
        (is (= "~/n" (get-in loaded [:silos :notes :path])))
        (is (false? (get-in loaded [:filename :sort-keywords?])))
        ;; untouched defaults survive the merge
        (is (= [:identifier :signature :title :keywords]
               (get-in loaded [:filename :components-order])))))))

(deftest validate-test
  (testing "valid config passes through"
    (let [cfg (assoc (config/default-config)
                :silos {:notes {:path "/n"}}
                :default-silo :notes)]
      (is (= cfg (config/validate cfg)))))
  (testing "default-silo must reference a configured silo"
    (is (thrown? Exception
                 (config/validate (assoc (config/default-config)
                                    :default-silo :nope)))))
  (testing "silo entries need a :path"
    (is (thrown? Exception
                 (config/validate (assoc (config/default-config)
                                    :silos {:notes {}}))))))

(deftest merge-cli-test
  (let [cfg (config/default-config)]
    (is (false? (get-in (config/merge-cli cfg
                                          {:filename {:sort-keywords? false}})
                        [:filename :sort-keywords?])))))
