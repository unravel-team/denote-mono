(ns denote-mono.editor.interface-test
  (:require [clojure.test :refer [deftest is testing]]
            [denote-mono.editor.interface :as editor]))

(deftest shellwords-test
  (is (= ["nvim"] (editor/shellwords "nvim")))
  (is (= ["emacsclient" "-n"] (editor/shellwords "emacsclient -n")))
  (is (= ["code" "--wait" "my editor"]
         (editor/shellwords "code --wait 'my editor'")))
  (is (= ["x" "a b"] (editor/shellwords "x \"a b\""))))

(deftest editor-command-test
  (testing "VISUAL wins over EDITOR"
    (is (= ["nvim"]
           (editor/editor-command {"VISUAL" "nvim", "EDITOR" "vi"} {}))))
  (testing "EDITOR with arguments is parsed into argv"
    (is (= ["emacsclient" "-n"]
           (editor/editor-command {"EDITOR" "emacsclient -n"} {}))))
  (testing "config editor vector is used after env vars"
    (is (= ["hx" "--"]
           (editor/editor-command {} {:tools {:editor ["hx" "--"]}}))))
  (testing "platform fallback" (is (= ["vi"] (editor/editor-command {} {})))))

(deftest open-test
  (testing "open runs the editor command with files appended"
    (let [{:keys [exit]} (editor/open ["/tmp/a.org" "/tmp/b.org"]
                                      {:env {"EDITOR" "true"}})]
      (is (zero? exit)))))
