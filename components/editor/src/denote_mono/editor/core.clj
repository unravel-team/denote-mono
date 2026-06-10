(ns denote-mono.editor.core
  "Editor resolution and launching. Editor commands are argv vectors; env
  values that contain arguments are split with a small shellwords parser,
  never handed to a shell."
  (:require [denote-mono.process.interface :as process]))

(defn shellwords
  "Split S into words, honoring single/double quotes and backslashes."
  [s]
  (loop [chars (seq s)
         current nil
         quote-char nil
         words []]
    (if-let [[c & more] chars]
      (cond (and quote-char (= c quote-char))
              (recur more (or current "") nil words)
            quote-char (recur more (str current c) quote-char words)
            (#{\' \"} c) (recur more (or current "") c words)
            (= c \\) (if-let [[escaped & rest-chars] more]
                       (recur rest-chars (str current escaped) nil words)
                       (recur nil (str current \\) nil words))
            (Character/isWhitespace ^char c)
              (recur more nil nil (cond-> words current (conj current)))
            :else (recur more (str current c) nil words))
      (cond-> words current (conj current)))))

(defn editor-command
  "Resolve the editor argv: $VISUAL, then $EDITOR, then config
  [:tools :editor] (a vector), then \"vi\"."
  [env config]
  (or (some #(some-> (get env %)
                     not-empty
                     shellwords)
            ["VISUAL" "EDITOR"])
      (not-empty (get-in config [:tools :editor]))
      ["vi"]))

(defn open
  "Open FILES in the resolved editor, attached to this terminal."
  [files {:keys [env config inherit-io?], :or {inherit-io? false}}]
  (process/run (into (editor-command env config) files)
               {:inherit-io? inherit-io?}))
