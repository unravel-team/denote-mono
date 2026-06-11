(ns denote-mono.process.core
  "External tool adapter. Tools always run as argv vectors through
  ProcessBuilder, never through a shell, so configured commands and user
  input cannot be shell-injected."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io File)
           (java.lang ProcessBuilder$Redirect)))

(defn available?
  "True when EXECUTABLE is an absolute path to an executable file or is
  found on PATH."
  [executable]
  (if (str/includes? executable "/")
    (let [f (io/file executable)] (and (.canExecute f) (.isFile f)))
    (boolean (some (fn [dir]
                     (let [f (io/file dir executable)]
                       (and (.isFile f) (.canExecute f))))
                   (str/split (or (System/getenv "PATH") "") #":")))))

(defn run
  "Run ARGV (vector of strings) and return {:exit N :out S :err S}.
  Options: :dir (working directory), :in (stdin string), :inherit-io?
  (attach the child to this terminal, e.g. for fzf/editor).
  A missing binary yields {:exit 127 :error :missing-binary} instead of
  throwing."
  [argv {:keys [dir in inherit-io?]}]
  (let [builder (ProcessBuilder. ^java.util.List (vec argv))]
    (when dir (.directory builder (File. ^String dir)))
    (when inherit-io?
      (.redirectInput builder ProcessBuilder$Redirect/INHERIT)
      (.redirectOutput builder ProcessBuilder$Redirect/INHERIT)
      (.redirectError builder ProcessBuilder$Redirect/INHERIT))
    (try (let [proc (.start builder)]
           (when (and in (not inherit-io?))
             (with-open [stdin (.getOutputStream proc)]
               (.write stdin (.getBytes ^String in "UTF-8"))))
           (let [out (when-not inherit-io? (slurp (.getInputStream proc)))
                 err (when-not inherit-io? (slurp (.getErrorStream proc)))
                 exit (.waitFor proc)]
             {:exit exit, :out (or out ""), :err (or err "")}))
         (catch java.io.IOException e
           {:exit 127, :out "", :err (ex-message e), :error :missing-binary}))))
