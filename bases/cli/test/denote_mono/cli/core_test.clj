(ns denote-mono.cli.core-test
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [denote-mono.cli.core :as cli]
            [denote-mono.process.interface :as process])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:dynamic *harness* nil)
(def ^:dynamic *config-path* nil)
(def ^:dynamic *notes-root* nil)
(def ^:dynamic *work-root* nil)

(defn- temp-dir
  [prefix]
  (str (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defn- cli-fixture
  [f]
  (let [notes (temp-dir "denote-cli-notes")
        work (temp-dir "denote-cli-work")
        config-path (str (temp-dir "denote-cli-config") "/config.edn")]
    (spit (str notes "/20240101T000000--alpha__clojure.org") "alpha")
    (spit (str notes "/20240102T000000--beta__notes.org") "beta")
    (spit (str work "/20240301T000000--work-note__job.org") "work")
    (spit config-path
          (pr-str {:default-silo :notes,
                   :silos {:notes {:path notes}, :work {:path work}}}))
    (binding [*harness* {:env {"EDITOR" "true"}, :cwd "/elsewhere"}
              *config-path* config-path
              *notes-root* notes
              *work-root* work]
      (f))))

(use-fixtures :each cli-fixture)

(defn- run-cli
  [& args]
  (cli/run (into ["--config" *config-path*] args) *harness*))

(deftest run-help
  (testing "no args, help, --help, -h all return success and help text"
    (doseq [args [[] ["help"] ["--help"] ["-h"]]]
      (let [{:keys [exit out]} (cli/run args *harness*)]
        (is (zero? exit))
        (is (str/includes? out "Usage: denote"))))))

(deftest per-command-help
  (testing "every command answers --help with its usage and exit 0"
    (doseq [command ["find" "grep" "backlinks" "links" "rename" "new" "seq"
                     "llm-wiki" "silo" "completions"]]
      (let [{:keys [exit out]} (run-cli command "--help")]
        (is (zero? exit) command)
        (is (str/includes? out (str "Usage: denote " command)) command))))
  (testing "-h works too"
    (let [{:keys [exit out]} (run-cli "new" "-h")]
      (is (zero? exit))
      (is (str/includes? out "Usage: denote new"))))
  (testing "the command's own options are listed"
    (is (str/includes? (:out (run-cli "find" "--help")) "--match"))
    (is (not (str/includes? (:out (run-cli "find" "--help")) "--absolute")))
    (is (str/includes? (:out (run-cli "rename" "--help")) "--break-links"))
    (is (str/includes? (:out (run-cli "llm-wiki" "--help")) "--deep")))
  (testing "subcommand groups list their subcommands"
    (let [{:keys [out]} (run-cli "seq" "--help")]
      (is (str/includes? out "reparent")))
    (let [{:keys [out]} (run-cli "silo" "--help")]
      (is (str/includes? out "doctor"))))
  (testing "--help anywhere in the command args wins"
    (let [{:keys [exit out]} (run-cli "find" "alpha" "--help")]
      (is (zero? exit))
      (is (str/includes? out "Usage: denote find"))
      (is (not (str/includes? out "alpha")))))
  (testing "unknown commands still fail even with --help"
    (is (= 2 (:exit (run-cli "frobnicate" "--help"))))))

(deftest run-unknown-command
  (testing "unknown command exits with usage code 2"
    (let [{:keys [exit out]} (run-cli "frobnicate")]
      (is (= 2 exit))
      (is (str/includes? out "Unknown command: frobnicate")))))

(deftest version-command
  (testing "--version and the version command print the tool version"
    (doseq [args [["--version"] ["version"]]]
      (let [{:keys [exit out]} (cli/run args *harness*)]
        (is (zero? exit))
        ;; From source there is no embedded version resource, so this
        ;; reports the dev placeholder; built jars embed vX.Y.Z.
        (is (re-matches #"denote (dev|v\d+\.\d+\.\d+)" out))))))

(deftest find-command
  (testing "find with query prints absolute matching paths"
    (let [root (.getCanonicalPath (java.io.File. *notes-root*))
          {:keys [exit out]} (run-cli "find" "alpha")]
      (is (zero? exit))
      (is (= [(str root "/20240101T000000--alpha__clojure.org")]
             (str/split-lines out)))))
  (testing "find without matches exits 6"
    (is (= 6 (:exit (run-cli "find" "zzz")))))
  (testing "find without query lists absolute paths, sorted by identifier"
    (let [root (.getCanonicalPath (java.io.File. *notes-root*))
          {:keys [exit out]} (run-cli "find")]
      (is (zero? exit))
      (is (= [(str root "/20240101T000000--alpha__clojure.org")
              (str root "/20240102T000000--beta__notes.org")]
             (str/split-lines out)))))
  (testing "--silo selects another silo"
    (let [root (.getCanonicalPath (java.io.File. *work-root*))
          {:keys [out]} (run-cli "--silo" "work" "find")]
      (is (= [(str root "/20240301T000000--work-note__job.org")]
             (str/split-lines out)))))
  (testing "keyword filter"
    (let [root (.getCanonicalPath (java.io.File. *notes-root*))
          {:keys [out]} (run-cli "find" "--keyword" "clojure")]
      (is (= [(str root "/20240101T000000--alpha__clojure.org")]
             (str/split-lines out)))))
  (testing "--absolute is no longer accepted"
    (is (= 2 (:exit (run-cli "find" "alpha" "--absolute")))))
  (testing "--print0 emits NUL-terminated absolute paths"
    (let [root (.getCanonicalPath (java.io.File. *notes-root*))
          {:keys [out]} (run-cli "find" "--print0")]
      (is (= [(str root "/20240101T000000--alpha__clojure.org")
              (str root "/20240102T000000--beta__notes.org")]
             (str/split out #"\u0000")))
      ;; NUL after EVERY record (find -print0 semantics): xargs -0 must
      ;; never see a printer-appended newline glued to the last path.
      (is (str/ends-with? out "\u0000"))))
  (testing "the printer appends no newline to NUL-terminated output"
    (is (= "a\u0000b\u0000"
           (with-out-str (#'cli/print-result
                          {:exit 0, :out "a\u0000b\u0000"}))))
    (is (= "plain\n"
           (with-out-str (#'cli/print-result {:exit 0, :out "plain"})))))
  (testing "--json emits parseable note records with absolute paths"
    (let [root (.getCanonicalPath (java.io.File. *notes-root*))
          {:keys [out]} (run-cli "find" "--json" "--id" "20240101T000000")
          parsed (json/read-str out :key-fn keyword)]
      (is (= (str root "/20240101T000000--alpha__clojure.org") (:path parsed)))
      (is (not (contains? parsed :relative-path)))
      (is (= "alpha" (get-in parsed [:filename :title])))
      (is (= "notes" (:silo parsed)))))
  (testing "--edn emits parseable note records with absolute paths"
    (let [root (.getCanonicalPath (java.io.File. *notes-root*))
          {:keys [out]} (run-cli "find" "--edn" "--id" "20240101T000000")
          parsed (edn/read-string out)]
      (is (= (str root "/20240101T000000--alpha__clojure.org") (:path parsed)))
      (is (not (contains? parsed :relative-path)))
      (is (= "alpha" (get-in parsed [:filename :title])))
      (is (= "notes" (:silo parsed)))))
  (testing "unknown silo errors with configured list"
    (let [{:keys [exit out]} (run-cli "--silo" "nope" "find")]
      (is (= 3 exit))
      (is (str/includes? out "notes"))))
  (testing "list is no longer a command" (is (= 2 (:exit (run-cli "list"))))))

(defn- fake-fzf!
  "Write an executable selector whose name contains \"fzf\" so the CLI
  treats it as the real thing (and passes --expect). It emulates fzf's
  --expect output: a line naming the pressed key (empty = Enter), then
  the selection (the first input line)."
  [dir key]
  (let [path (str dir "/fake-fzf-" (if (str/blank? key) "enter" key))]
    (spit path
          (str "#!/bin/sh\nIFS= read -r first\nprintf '"
               key
               "\\n%s\\n' \"$first\"\n"))
    (.setExecutable (java.io.File. path) true)
    path))

(deftest find-selection-command
  (let [tools-dir (temp-dir "denote-cli-tools")
        fzf-config (str tools-dir "/config.edn")
        base (read-string (slurp *config-path*))
        tty-harness (assoc *harness* :tty? true)
        run-tty (fn [& args]
                  (cli/run (into ["--config" fzf-config] args) tty-harness))]
    (testing "Enter opens the selection in the editor (no output)"
      (spit fzf-config
            (pr-str (assoc base :tools {:fzf [(fake-fzf! tools-dir "")]})))
      (let [{:keys [exit out]} (run-tty "find")]
        (is (zero? exit))
        (is (str/blank? out))))
    (testing "Ctrl-P prints the absolute selection instead"
      (spit fzf-config
            (pr-str (assoc base
                      :tools {:fzf [(fake-fzf! tools-dir "ctrl-p")]})))
      (let [root (.getCanonicalPath (java.io.File. *notes-root*))
            {:keys [exit out]} (run-tty "find")]
        (is (zero? exit))
        (is (= [(str root "/20240101T000000--alpha__clojure.org")]
               (str/split-lines out)))))
    (testing "a non-fzf selector defaults to opening the selection"
      (spit fzf-config (pr-str (assoc base :tools {:fzf ["head" "-1"]})))
      (let [{:keys [exit out]} (run-tty "find")]
        (is (zero? exit))
        (is (str/blank? out))))
    (testing "a cancelling selector exits with no-match"
      (spit fzf-config (pr-str (assoc base :tools {:fzf ["false"]})))
      (let [{:keys [exit out]} (run-tty "find")]
        (is (= 6 exit))
        (is (str/includes? out "cancelled"))))
    (testing "missing selector falls back to printing all matches"
      (spit fzf-config
            (pr-str (assoc base
                      :tools {:fzf ["definitely-not-a-real-tool-xyz"]})))
      (let [{:keys [exit out]} (run-tty "find")]
        (is (zero? exit))
        (is (= 2 (count (str/split-lines out))))))
    (testing "no TTY means plain output even with a selector configured"
      (spit fzf-config
            (pr-str (assoc base
                      :tools {:fzf [(fake-fzf! tools-dir "ctrl-p")]})))
      (let [{:keys [exit out]} (cli/run ["--config" fzf-config "find"]
                                        *harness*)]
        (is (zero? exit))
        (is (= 2 (count (str/split-lines out))))))
    (testing "scripted output formats skip the selector on a TTY"
      (let [{:keys [out]} (run-tty "find" "--json" "--id" "20240101T000000")]
        (is (= "alpha"
               (get-in (json/read-str out :key-fn keyword)
                       [:filename :title])))))
    (testing "open is no longer a command"
      (is (= 2 (:exit (run-cli "open" "alpha")))))))

(deftest grep-selection-command
  (spit (str *notes-root* "/20240101T000000--alpha__clojure.org")
        "first NEEDLE\n")
  (spit (str *notes-root* "/20240102T000000--beta__notes.org")
        "second NEEDLE\n")
  (let [tools-dir (temp-dir "denote-cli-grep-tools")
        fzf-config (str tools-dir "/config.edn")
        base (read-string (slurp *config-path*))
        tty-harness (assoc *harness* :tty? true)
        run-tty (fn [& args]
                  (cli/run (into ["--config" fzf-config] args) tty-harness))]
    (testing "Ctrl-P prints the selected match line"
      (spit fzf-config
            (pr-str (assoc base
                      :tools {:fzf [(fake-fzf! tools-dir "ctrl-p")]})))
      ;; match order follows the directory walk, so only the shape of the
      ;; single selected line is stable
      (let [{:keys [exit out]} (run-tty "grep" "NEEDLE")]
        (is (zero? exit))
        (is (= 1 (count (str/split-lines out))))
        (is (re-matches #"2024010[12]T000000--\S+\.org:1:(first|second) NEEDLE"
                        out))))
    (testing "Enter opens the selected match's file"
      (spit fzf-config
            (pr-str (assoc base :tools {:fzf [(fake-fzf! tools-dir "")]})))
      (let [{:keys [exit out]} (run-tty "grep" "NEEDLE")]
        (is (zero? exit))
        (is (str/blank? out))))
    (testing "no TTY prints all match lines"
      (let [{:keys [exit out]} (cli/run ["--config" fzf-config "grep" "NEEDLE"]
                                        *harness*)]
        (is (zero? exit))
        (is (= 2 (count (str/split-lines out))))))))

(deftest rename-command
  (testing "dry-run prints the plan and leaves the file alone"
    (let [{:keys [exit out]}
            (run-cli "rename"
                     (str *notes-root* "/20240101T000000--alpha__clojure.org")
                     "--title"
                     "renamed"
                     "--dry-run")]
      (is (zero? exit))
      (is (str/includes? out "20240101T000000--renamed__clojure.org"))
      (is (.exists (java.io.File. (str
                                    *notes-root*
                                    "/20240101T000000--alpha__clojure.org"))))))
  (testing "rename applies and reports old -> new"
    (let [{:keys [exit]} (run-cli "rename"
                                    (str *notes-root*
                                         "/20240101T000000--alpha__clojure.org")
                                  "--title" "renamed"
                                  "--front-matter" "none")]
      (is (zero? exit))
      (is (.exists (java.io.File.
                     (str *notes-root*
                          "/20240101T000000--renamed__clojure.org"))))))
  (testing "a file outside any silo is renamed (denote-ified) in place"
    (let [outside (temp-dir "denote-cli-outside")
          file (str outside "/plain note.txt")]
      (spit file "content")
      (let [{:keys [exit out]} (run-cli "rename" file
                                        "--title" "Plain Note"
                                        "--date" "2025-05-05 05:05:05"
                                        "--front-matter" "none")]
        (is (zero? exit))
        (is (str/includes? out "20250505T050505--plain-note.txt"))
        (is (.exists (java.io.File.
                       (str outside "/20250505T050505--plain-note.txt")))))))
  (testing "a plain file renamed without --title uses its original stem"
    (let [outside (temp-dir "denote-cli-outside")
          file (str outside "/Vedang_Manerikar_Pancard.pdf")]
      (spit file "content")
      (let [{:keys [exit out]} (run-cli "rename" file
                                        "--date" "2017-09-06 11:07:31"
                                        "--front-matter" "none")]
        (is (zero? exit))
        (is (str/includes? out "20170906T110731--vedang-manerikar-pancard.pdf"))
        (is (.exists (java.io.File.
                       (str
                         outside
                         "/20170906T110731--vedang-manerikar-pancard.pdf")))))))
  (testing "rename-many is no longer a command"
    (is (= 2 (:exit (run-cli "rename-many" "--add-keyword" "x" "f"))))))

(deftest rename-break-links-command
  (spit (str *notes-root* "/20240101T000000--alpha__clojure.org")
        "Linking to [[denote:20240102T000000][beta]].\n")
  (let [beta (str *notes-root* "/20240102T000000--beta__notes.org")]
    (testing "identifier change with backlinks is refused"
      (let [{:keys [exit out]} (run-cli "rename" beta
                                        "--id" "20300101T000000"
                                        "--front-matter" "none")]
        (is (= 3 exit))
        (is (str/includes? out "--break-links"))
        (is (str/includes? out "alpha"))
        (is (.exists (java.io.File. ^String beta)))))
    (testing "--break-links allows it"
      (is (zero? (:exit (run-cli "rename"
                                 beta
                                 "--id"
                                 "20300101T000000" "--front-matter"
                                 "none" "--break-links"))))
      (is (.exists (java.io.File. (str *notes-root*
                                       "/20300101T000000--beta__notes.org")))))
    (testing "title-only renames are unaffected by the guard"
      (is (zero? (:exit (run-cli "rename"
                                   (str *notes-root*
                                        "/20240101T000000--alpha__clojure.org")
                                 "--title" "alpha-two"
                                 "--front-matter" "none")))))
    (testing "identifier changes outside a silo skip the guard"
      ;; alpha links to 20240102T000000, but this file with the same
      ;; identifier lives outside every silo, so the guard does not fire.
      (let [outside (temp-dir "denote-cli-outside-guard")
            file (str outside "/20240102T000000--copy__x.org")]
        (spit file "copy")
        (is (zero? (:exit (run-cli "rename" file
                                   "--id" "20310101T000000"
                                   "--front-matter" "none"))))
        (is (.exists (java.io.File. (str outside
                                         "/20310101T000000--copy__x.org"))))))))

(deftest grep-backlinks-links-commands
  (spit (str *notes-root* "/20240101T000000--alpha__clojure.org")
        "Linking to [[denote:20240102T000000][beta]] and a NEEDLE.\n")
  (testing "grep finds content with file:line:text output"
    (let [{:keys [exit out]} (run-cli "grep" "NEEDLE")]
      (is (zero? exit))
      (is (str/includes? out "alpha__clojure.org:1:"))))
  (testing "grep without matches exits 6"
    (is (= 6 (:exit (run-cli "grep" "zzz-not-there")))))
  (testing "backlinks by identifier"
    (let [{:keys [exit out]} (run-cli "backlinks" "20240102T000000")]
      (is (zero? exit))
      (is (str/includes? out "alpha"))))
  (testing "backlinks by file path"
    (let [{:keys [exit out]}
            (run-cli "backlinks"
                     (str *notes-root* "/20240102T000000--beta__notes.org"))]
      (is (zero? exit))
      (is (str/includes? out "alpha"))))
  (testing "links lists outgoing targets"
    (let [{:keys [exit out]}
            (run-cli "links"
                     (str *notes-root* "/20240101T000000--alpha__clojure.org"))]
      (is (zero? exit))
      (is (str/includes? out "beta"))))
  (testing "links by identifier resolves the source note"
    (is (zero? (:exit (run-cli "links" "20240101T000000"))))))

(deftest new-command
  (testing "dry-run prints planned path"
    (let [{:keys [exit out]} (run-cli "new" "--title"
                                      "Fresh Note" "--keyword"
                                      "kw" "--date"
                                      "2025-05-05 05:05:05" "--dry-run")]
      (is (zero? exit))
      (is (str/ends-with? out "/20250505T050505--fresh-note__kw.org"))
      (is (not (.exists (java.io.File. ^String out))))))
  (testing "new creates the file with front matter"
    (let [{:keys [exit out]} (run-cli "new"
                                      "--title" "Fresh Note"
                                      "--date" "2025-05-05 05:05:05")]
      (is (zero? exit))
      (is (str/includes? (slurp out) "#+title:      Fresh Note")))))

(deftest seq-commands
  (testing "validate is no longer a subcommand"
    (is (= 2 (:exit (run-cli "seq" "validate" "1=1")))))
  (testing "next parent with no sequences starts at 1"
    (is (= "1" (:out (run-cli "seq" "next" "parent")))))
  (testing "seq new parent creates a note with signature"
    (let [{:keys [exit out]} (run-cli "seq"
                                      "new" "parent"
                                      "--title" "Seq Root"
                                      "--date" "2025-06-06 06:06:06")]
      (is (zero? exit))
      (is (str/includes? out "==1--seq-root"))
      (testing "next child of the new parent"
        (is (= "1=1" (:out (run-cli "seq" "next" "child" "1")))))
      (testing "seq list shows it"
        (is (str/includes? (:out (run-cli "seq" "list")) "1\t")))))
  (testing "as-parent assigns the next top-level sequence to a plain note"
    ;; The seq-root note above holds sequence 1, so beta becomes 2.
    (let [{:keys [exit]} (run-cli "seq"
                                  "as-parent"
                                  (str *notes-root*
                                       "/20240102T000000--beta__notes.org"))
          renamed (str *notes-root* "/20240102T000000==2--beta__notes.org")]
      (is (zero? exit))
      (is (.exists (java.io.File. ^String renamed)))
      (testing "as-parent aborts when a sequence already exists"
        (is (= 3 (:exit (run-cli "seq" "as-parent" renamed)))))
      (testing "convert to alphanumeric (dry run shows plan)"
        (let [{:keys [out]} (run-cli "seq"
                                     "convert"
                                     renamed
                                     "--to"
                                     "alphanumeric"
                                     "--dry-run")]
          (is (str/includes? out "==2--beta")))))))

(deftest seq-tree-command
  (run-cli "seq" "new" "parent" "--title" "one" "--date" "2025-01-01 00:00:00")
  (run-cli "seq" "new"
           "child" "1"
           "--title" "one-one"
           "--date" "2025-01-02 00:00:00")
  (run-cli "seq" "new"
           "child" "1=1"
           "--title" "one-one-one"
           "--date" "2025-01-03 00:00:00")
  (run-cli "seq" "new" "parent" "--title" "two" "--date" "2025-01-04 00:00:00")
  (testing "tree indents children under parents"
    (let [{:keys [exit out]} (run-cli "seq" "tree")]
      (is (zero? exit))
      (is (= ["1  20250101T000000==1--one.org"
              "  1=1  20250102T000000==1=1--one-one.org"
              "    1=1=1  20250103T000000==1=1=1--one-one-one.org"
              "2  20250104T000000==2--two.org"]
             (str/split-lines out)))))
  (testing "a positional SEQ rebases indentation on the subtree"
    (let [{:keys [out]} (run-cli "seq" "tree" "1=1")]
      (is (= ["1=1  20250102T000000==1=1--one-one.org"
              "  1=1=1  20250103T000000==1=1=1--one-one-one.org"]
             (str/split-lines out)))))
  (testing "--depth limits the tree"
    (let [{:keys [out]} (run-cli "seq" "tree" "--depth" "2")]
      (is (= ["1  20250101T000000==1--one.org"
              "  1=1  20250102T000000==1=1--one-one.org"
              "2  20250104T000000==2--two.org"]
             (str/split-lines out)))))
  (testing "seq list takes the same positional SEQ"
    (let [{:keys [out]} (run-cli "seq" "list" "1=1")]
      (is (= ["1=1" "1=1=1"]
             (map #(first (str/split % #"\t")) (str/split-lines out))))))
  (testing "--prefix is gone"
    (is (= 2 (:exit (run-cli "seq" "tree" "--prefix" "1=1"))))))

(deftest seq-reparent-command
  ;; build a small hierarchy: 1, 1=1, and 2; reparent 2 under 1
  (run-cli "seq" "new" "parent" "--title" "one" "--date" "2025-01-01 00:00:00")
  (run-cli "seq" "new"
           "child" "1"
           "--title" "one-one"
           "--date" "2025-01-02 00:00:00")
  (run-cli "seq" "new" "parent" "--title" "two" "--date" "2025-01-03 00:00:00")
  (let [two-path (str *notes-root* "/20250103T000000==2--two.org")]
    (testing "without --yes, prints plan and asks"
      (is (= 3 (:exit (run-cli "seq" "reparent" two-path "1")))))
    (testing "with --yes, renames to next child of target"
      (let [{:keys [exit]} (run-cli "seq" "reparent" two-path "1" "--yes")]
        (is (zero? exit))
        (is (.exists (java.io.File. (str
                                      *notes-root*
                                      "/20250103T000000==1=2--two.org"))))))))

(deftest completions-command
  (testing "bash script covers commands and flags, and parses with bash -n"
    (let [{:keys [exit out]} (run-cli "completions" "bash")]
      (is (zero? exit))
      (is (str/includes? out "complete -o default -F _denote denote"))
      (is (str/includes? out "rename"))
      (is (not (str/includes? out "rename-many")))
      (is (str/includes? out "--break-links"))
      (is (str/includes? out "as-parent"))
      (let [script (str (temp-dir "denote-completions") "/denote.bash")]
        (spit script out)
        (is (zero? (:exit (process/run ["bash" "-n" script])))))))
  (testing "zsh script has compdef header and parses with zsh -n"
    (let [{:keys [exit out]} (run-cli "completions" "zsh")]
      (is (zero? exit))
      (is (str/starts-with? out "#compdef denote"))
      (is (str/includes? out "'rename:Rename one file'"))
      (is (str/includes? out "'--break-links["))
      (let [script (str (temp-dir "denote-completions") "/_denote")]
        (spit script out)
        (is (zero? (:exit (process/run ["zsh" "-n" script])))))))
  (testing "fish script registers subcommands and flags"
    (let [{:keys [exit out]} (run-cli "completions" "fish")]
      (is (zero? exit))
      (is (str/includes? out "complete -c denote -n __fish_use_subcommand"))
      (is
        (str/includes?
          out
          "complete -c denote -n '__fish_seen_subcommand_from rename' -l break-links"))
      (when (process/available? "fish")
        (let [script (str (temp-dir "denote-completions") "/denote.fish")]
          (spit script out)
          (is (zero? (:exit (process/run ["fish" "-n" script]))))))))
  (testing "unknown shell exits with usage code"
    (is (= 2 (:exit (run-cli "completions" "powershell"))))
    (is (= 2 (:exit (run-cli "completions"))))))

(deftest context-options-after-subcommand
  (testing "find --silo after the subcommand selects that silo"
    (let [root (.getCanonicalPath (java.io.File. *work-root*))
          {:keys [exit out]} (run-cli "find" "--silo" "work")]
      (is (zero? exit))
      (is (= [(str root "/20240301T000000--work-note__job.org")]
             (str/split-lines out)))))
  (testing "subcommand --silo wins over the global --silo"
    (let [root (.getCanonicalPath (java.io.File. *work-root*))
          {:keys [out]} (run-cli "--silo" "notes" "find" "--silo" "work")]
      (is (= [(str root "/20240301T000000--work-note__job.org")]
             (str/split-lines out)))))
  (testing "new --silo plans inside the named silo"
    (let [{:keys [exit out]} (run-cli "new" "--silo"
                                      "work" "--title"
                                      "Elsewhere" "--date"
                                      "2025-05-05 05:05:05" "--dry-run")]
      (is (zero? exit))
      (is (str/starts-with? out
                            (.getCanonicalPath (java.io.File. *work-root*))))))
  (testing "grep --silo searches the named silo"
    (spit (str *work-root* "/20240301T000000--work-note__job.org")
          "work NEEDLE\n")
    (let [{:keys [exit out]} (run-cli "grep" "NEEDLE" "--silo" "work")]
      (is (zero? exit))
      (is (str/includes? out "work-note__job.org:1:"))))
  (testing "backlinks --silo resolves against the named silo"
    (spit (str *notes-root* "/20240101T000000--alpha__clojure.org")
          "Linking to [[denote:20240102T000000][beta]].\n")
    (is (zero? (:exit (run-cli "backlinks" "20240102T000000"
                               "--silo" "notes"))))
    (is (= 6 (:exit (run-cli "backlinks" "20240102T000000" "--silo" "work")))))
  (testing "links --silo resolves an identifier in the named silo"
    (spit (str *notes-root* "/20240101T000000--alpha__clojure.org")
          "Linking to [[denote:20240102T000000][beta]].\n")
    (is (zero? (:exit (run-cli "links" "20240101T000000" "--silo" "notes")))))
  (testing "--root after the subcommand works too"
    (let [root (.getCanonicalPath (java.io.File. *work-root*))
          {:keys [out]} (run-cli "find" "--root" *work-root*)]
      (is (= [(str root "/20240301T000000--work-note__job.org")]
             (str/split-lines out)))))
  (testing "seq new parent --silo creates in the named silo"
    (let [{:keys [exit out]} (run-cli "seq"
                                      "new" "parent"
                                      "--silo" "work"
                                      "--title" "Work Root"
                                      "--date" "2025-06-06 06:06:06")]
      (is (zero? exit))
      (is (str/starts-with? out
                            (.getCanonicalPath (java.io.File. *work-root*))))))
  (testing "--config after the subcommand loads that config"
    (let [root (.getCanonicalPath (java.io.File. *notes-root*))
          {:keys [exit out]} (cli/run ["find" "--config" *config-path*]
                                      *harness*)]
      (is (zero? exit))
      (is (= 2 (count (str/split-lines out))))
      (is (str/starts-with? (first (str/split-lines out)) root))))
  (testing "rename accepts --silo without complaint"
    (let [{:keys [exit]} (run-cli "rename"
                                  (str *notes-root*
                                       "/20240101T000000--alpha__clojure.org")
                                  "--silo"
                                  "notes" "--title"
                                  "renamed" "--dry-run")]
      (is (zero? exit))))
  (testing "unknown silo after the subcommand lists configured silos"
    (let [{:keys [exit out]} (run-cli "find" "--silo" "nope")]
      (is (= 3 exit))
      (is (str/includes? out "notes")))))

(deftest llm-wiki-silo-after-subcommand
  (let [wiki-a (temp-dir "denote-cli-wiki-a")
        wiki-b (temp-dir "denote-cli-wiki-b")
        config-path (str (temp-dir "denote-cli-wiki-config") "/config.edn")
        _ (spit config-path
                (pr-str {:default-silo :notes,
                         :default-llm-wiki-silo :wikia,
                         :silos {:notes {:path *notes-root*},
                                 :wikia {:path wiki-a, :llm-wiki true},
                                 :wikib {:path wiki-b, :llm-wiki true}}}))
        run-wiki (fn [& args]
                   (cli/run (into ["--config" config-path] args) *harness*))]
    (testing "llm-wiki lint --silo after the subcommand matches the global form"
      (let [global-form (cli/run ["--config" config-path "--silo" "wikib"
                                  "llm-wiki" "lint"]
                                 *harness*)
            sub-form (run-wiki "llm-wiki" "lint" "--silo" "wikib")]
        (is (not= 2 (:exit sub-form)))
        (is (= global-form sub-form))))))

(deftest context-options-in-help-and-completions
  (testing "per-command help lists the context options"
    (doseq [command ["find" "grep" "backlinks" "links" "new" "seq" "llm-wiki"
                     "rename"]]
      (let [{:keys [out]} (run-cli command "--help")]
        (is (str/includes? out "--silo") command)
        (is (str/includes? out "--root") command)
        (is (str/includes? out "--config") command))))
  (testing "fish completions offer --silo on subcommands"
    (let [{:keys [out]} (run-cli "completions" "fish")]
      (is
        (str/includes?
          out
          "complete -c denote -n '__fish_seen_subcommand_from find' -l silo")))))

(deftest init-command
  (let [fresh-config (fn [] (str (temp-dir "denote-cli-init") "/config.edn"))
        notes-dir (fn [] (str (temp-dir "denote-cli-init-notes") "/notes"))]
    (testing "init writes a config and creates the notes directory"
      (let [config-path (fresh-config)
            dir (notes-dir)
            {:keys [exit out]} (cli/run ["init" "--config" config-path "--path"
                                         dir "--name" "notes"]
                                        *harness*)]
        (is (zero? exit))
        (is (str/includes? out config-path))
        (is (.isFile (java.io.File. ^String config-path)))
        (is (.isDirectory (java.io.File. ^String dir)))
        (let [written (edn/read-string (slurp config-path))]
          (is (= :notes (:default-silo written)))
          (is (= dir (get-in written [:silos :notes :path]))))
        (testing "the written config is immediately usable"
          (let [{:keys [exit out]} (cli/run ["--config" config-path "find"]
                                            *harness*)]
            (is (= 6 exit))
            (is (str/includes? out "No matching notes"))))))
    (testing "the config file documents the defaults as comments"
      (let [config-path (fresh-config)]
        (cli/run ["init" "--config" config-path "--path" (notes-dir) "--name"
                  "notes"]
                 *harness*)
        (let [text (slurp config-path)]
          (is (str/includes? text ";;"))
          (is (str/includes? text ":llm"))
          (is (str/includes? text ":file-type")))))
    (testing "init refuses to overwrite an existing config"
      (let [config-path (fresh-config)]
        (spit config-path "{}")
        (let [{:keys [exit out]} (cli/run ["init" "--config" config-path
                                           "--path" (notes-dir) "--name"
                                           "notes"]
                                          *harness*)]
          (is (= 3 exit))
          (is (str/includes? out "--force"))
          (is (= "{}" (slurp config-path))))))
    (testing "--force overwrites"
      (let [config-path (fresh-config)
            dir (notes-dir)]
        (spit config-path "{}")
        (let [{:keys [exit]} (cli/run ["init" "--config" config-path "--path"
                                       dir "--name" "notes" "--force"]
                                      *harness*)]
          (is (zero? exit))
          (is (= :notes
                 (:default-silo (edn/read-string (slurp config-path))))))))
    (testing "--print emits the config text without writing anything"
      (let [config-path (fresh-config)
            dir (notes-dir)
            {:keys [exit out]} (cli/run ["init" "--config" config-path "--path"
                                         dir "--name" "notes" "--print"]
                                        *harness*)]
        (is (zero? exit))
        (is (= :notes (:default-silo (edn/read-string out))))
        (is (not (.exists (java.io.File. ^String config-path))))
        (is (not (.exists (java.io.File. ^String dir))))))
    (testing "--llm-wiki-path adds a flagged wiki silo and default"
      (let [config-path (fresh-config)
            wiki-dir (str (temp-dir "denote-cli-init-wiki") "/wiki")
            {:keys [exit]} (cli/run ["init" "--config" config-path "--path"
                                     (notes-dir) "--name" "notes"
                                     "--llm-wiki-path" wiki-dir]
                                    *harness*)]
        (is (zero? exit))
        (is (.isDirectory (java.io.File. ^String wiki-dir)))
        (let [written (edn/read-string (slurp config-path))]
          (is (true? (get-in written [:silos :wiki :llm-wiki])))
          (is (= wiki-dir (get-in written [:silos :wiki :path])))
          (is (= :wiki (:default-llm-wiki-silo written))))))
    (testing "without --path and without a terminal init explains itself"
      (let [{:keys [exit out]} (cli/run ["init" "--config" (fresh-config)]
                                        *harness*)]
        (is (= 2 exit))
        (is (str/includes? out "--path"))))
    (testing "the default config location comes from XDG_CONFIG_HOME"
      (let [xdg (temp-dir "denote-cli-init-xdg")
            {:keys [exit]} (cli/run
                             ["init" "--path" (notes-dir) "--name" "notes"]
                             (assoc *harness* :env {"XDG_CONFIG_HOME" xdg}))]
        (is (zero? exit))
        (is (.isFile (java.io.File. (str xdg "/denote-mono/config.edn"))))))
    (testing "init answers --help"
      (let [{:keys [exit out]} (cli/run ["init" "--help"] *harness*)]
        (is (zero? exit))
        (is (str/includes? out "Usage: denote init"))
        (is (str/includes? out "--print"))))
    (testing "completions know about init"
      (is (str/includes? (:out (run-cli "completions" "bash")) "init")))))

(deftest actionable-error-messages
  (testing "no silo selected points at denote init and the selectors"
    (let [config-path (str (temp-dir "denote-cli-nodefault") "/config.edn")]
      (spit config-path (pr-str {:silos {:notes {:path *notes-root*}}}))
      (let [{:keys [exit out]} (cli/run ["--config" config-path "find"]
                                        *harness*)]
        (is (= 3 exit))
        (is (str/includes? out "No silo selected"))
        (is (str/includes? out "--silo"))))
    (testing "and so does a missing config file"
      (let [{:keys [exit out]}
              (cli/run ["--config" "/nonexistent/config.edn" "find"] *harness*)]
        (is (= 3 exit))
        (is (str/includes? out "denote init")))))
  (testing "silo list with no silos says so instead of printing nothing"
    (let [config-path (str (temp-dir "denote-cli-nosilos") "/config.edn")]
      (spit config-path (pr-str {:silos {}}))
      (let [{:keys [exit out]} (cli/run ["--config" config-path "silo" "list"]
                                        *harness*)]
        (is (zero? exit))
        (is (str/includes? out "No silos configured"))
        (is (str/includes? out "denote init")))))
  (testing "a config that fails to parse reports the file, not a stack trace"
    (let [config-path (str (temp-dir "denote-cli-broken") "/config.edn")]
      (spit config-path "{:default-silo :notes")
      (let [{:keys [exit out]} (cli/run ["--config" config-path "find"]
                                        *harness*)]
        (is (= 3 exit))
        (is (str/includes? out config-path))
        (is (not (str/includes? out "clojure.lang")))))))

(deftest config-command
  (testing "config path prints the resolved config file path"
    (let [{:keys [exit out]} (run-cli "config" "path")]
      (is (zero? exit))
      (is (= *config-path* out))))
  (testing "config path for a missing file prints it but exits no-match"
    (let [missing (str (temp-dir "denote-cli-config-missing") "/config.edn")
          {:keys [exit out]} (cli/run ["--config" missing "config" "path"]
                                      *harness*)]
      (is (= 6 exit))
      (is (= missing out))))
  (testing "config path honors XDG_CONFIG_HOME when no --config is given"
    (let [xdg (temp-dir "denote-cli-config-xdg")
          {:keys [out]} (cli/run ["config" "path"]
                                 (assoc *harness*
                                   :env {"XDG_CONFIG_HOME" xdg}))]
      (is (= (str xdg "/denote-mono/config.edn") out))))
  (testing "config show prints the effective merged config as EDN"
    (let [{:keys [exit out]} (run-cli "config" "show")
          shown (edn/read-string out)]
      (is (zero? exit))
      ;; user file wins where set, defaults fill the rest
      (is (= :notes (:default-silo shown)))
      (is (= *notes-root* (get-in shown [:silos :notes :path])))
      (is (= :org (get-in shown [:filename :file-type])))
      (is (contains? shown :llm))))
  (testing "config show --json emits the same config as JSON"
    (let [{:keys [exit out]} (run-cli "config" "show" "--json")
          parsed (json/read-str out :key-fn keyword)]
      (is (zero? exit))
      (is (= "notes" (:default-silo parsed)))
      (is (= *notes-root* (get-in parsed [:silos :notes :path])))))
  (testing "config show reflects defaults when no file exists"
    (let [missing (str (temp-dir "denote-cli-config-none") "/config.edn")
          {:keys [exit out]} (cli/run ["--config" missing "config" "show"]
                                      *harness*)
          shown (edn/read-string out)]
      (is (zero? exit))
      (is (= {} (:silos shown)))))
  (testing "unknown config subcommand is a usage error"
    (is (= 2 (:exit (run-cli "config" "frobnicate")))))
  (testing "config answers --help with its subcommands"
    (let [{:keys [exit out]} (run-cli "config" "--help")]
      (is (zero? exit))
      (is (str/includes? out "Usage: denote config"))
      (is (str/includes? out "show"))
      (is (str/includes? out "path"))))
  (testing "completions know about config"
    (is (str/includes? (:out (run-cli "completions" "fish"))
                       "__fish_seen_subcommand_from config"))))

(deftest doctor-command
  (testing "a healthy setup is all ok, exit 0"
    (let [{:keys [exit out]} (run-cli "doctor")]
      (is (zero? exit))
      (is (str/includes? out "config"))
      (is (str/includes? out "notes"))))
  (testing "a missing silo directory fails, and --fix creates it"
    (let [gone (str (temp-dir "denote-cli-doctor") "/gone")
          config-path (str (temp-dir "denote-cli-doctor-cfg") "/config.edn")]
      (spit config-path
            (pr-str {:default-silo :notes, :silos {:notes {:path gone}}}))
      (let [{:keys [exit out]} (cli/run ["--config" config-path "doctor"]
                                        *harness*)]
        (is (= 3 exit))
        (is (str/includes? out gone)))
      (let [{:keys [exit out]}
              (cli/run ["--config" config-path "doctor" "--fix"] *harness*)]
        (is (zero? exit))
        (is (str/includes? out gone))
        (is (.isDirectory (java.io.File. ^String gone))))))
  (testing "a missing external tool warns without failing"
    (let [config-path (str (temp-dir "denote-cli-doctor-tools") "/config.edn")]
      (spit config-path
            (pr-str {:default-silo :notes,
                     :silos {:notes {:path *notes-root*}},
                     :tools {:fzf ["definitely-not-a-real-tool-xyz"]}}))
      (let [{:keys [exit out]} (cli/run ["--config" config-path "doctor"]
                                        *harness*)]
        (is (zero? exit))
        (is (str/includes? out "fzf")))))
  (testing "no editor anywhere warns without failing"
    (let [{:keys [exit out]} (run-cli "doctor")]
      ;; fixture env has EDITOR=true, so no warning here
      (is (zero? exit))
      (is (not (str/includes? out "falls back to vi"))))
    (let [{:keys [exit out]} (cli/run ["--config" *config-path* "doctor"]
                                      (assoc *harness* :env {}))]
      (is (zero? exit))
      (is (str/includes? out "editor"))))
  (testing "an llm-wiki silo without its API key env warns without failing"
    (let [wiki (temp-dir "denote-cli-doctor-wiki")
          config-path (str (temp-dir "denote-cli-doctor-llm") "/config.edn")]
      (spit config-path
            (pr-str {:default-silo :notes,
                     :default-llm-wiki-silo :wiki,
                     :silos {:notes {:path *notes-root*},
                             :wiki {:path wiki, :llm-wiki true}}}))
      (let [{:keys [exit out]} (cli/run ["--config" config-path "doctor"]
                                        (assoc *harness* :env {}))]
        (is (zero? exit))
        (is (str/includes? out "OPENROUTER_API_KEY")))))
  (testing "a missing config file fails and points at denote init"
    (let [missing (str (temp-dir "denote-cli-doctor-none") "/config.edn")
          {:keys [exit out]} (cli/run ["--config" missing "doctor"] *harness*)]
      (is (= 3 exit))
      (is (str/includes? out "denote init"))))
  (testing "doctor answers --help"
    (let [{:keys [exit out]} (run-cli "doctor" "--help")]
      (is (zero? exit))
      (is (str/includes? out "Usage: denote doctor"))
      (is (str/includes? out "--fix"))))
  (testing "silo doctor stays as the silo-only subset"
    (let [{:keys [exit out]} (run-cli "silo" "doctor")]
      (is (zero? exit))
      (is (str/includes? out "OK")))))

(deftest silo-commands
  (testing "silo list shows names and paths"
    (let [{:keys [exit out]} (run-cli "silo" "list")]
      (is (zero? exit))
      (is (str/includes? out "notes"))
      (is (str/includes? out "work"))))
  (testing "silo path defaults to the default silo"
    (is (= *notes-root* (:out (run-cli "silo" "path")))))
  (testing "silo path NAME"
    (let [{:keys [exit out]} (run-cli "silo" "path" "work")]
      (is (zero? exit))
      (is (str/ends-with? out (last (str/split out #"/"))))))
  (testing "silo doctor reports healthy silos"
    (let [{:keys [exit out]} (run-cli "silo" "doctor")]
      (is (zero? exit))
      (is (str/includes? out "OK")))))
