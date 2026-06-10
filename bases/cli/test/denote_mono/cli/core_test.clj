(ns denote-mono.cli.core-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [denote-mono.cli.core :as cli]
            [denote-mono.process.interface :as process])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:dynamic *harness* nil)
(def ^:dynamic *config-path* nil)
(def ^:dynamic *notes-root* nil)

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
              *notes-root* notes]
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

(deftest run-unknown-command
  (testing "unknown command exits with usage code 2"
    (let [{:keys [exit out]} (run-cli "frobnicate")]
      (is (= 2 exit))
      (is (str/includes? out "Unknown command: frobnicate")))))

(deftest list-command
  (testing "default silo listing, relative paths, sorted by identifier"
    (let [{:keys [exit out]} (run-cli "list")]
      (is (zero? exit))
      (is (= ["20240101T000000--alpha__clojure.org"
              "20240102T000000--beta__notes.org"]
             (str/split-lines out)))))
  (testing "--silo selects another silo"
    (let [{:keys [out]} (run-cli "--silo" "work" "list")]
      (is (= ["20240301T000000--work-note__job.org"] (str/split-lines out)))))
  (testing "keyword filter"
    (let [{:keys [out]} (run-cli "list" "--keyword" "clojure")]
      (is (= ["20240101T000000--alpha__clojure.org"] (str/split-lines out)))))
  (testing "--json emits parseable note records"
    (let [{:keys [out]} (run-cli "list" "--json" "--id" "20240101T000000")
          parsed (json/read-str out :key-fn keyword)]
      (is (= "alpha" (get-in parsed [:filename :title])))
      (is (= "notes" (:silo parsed)))))
  (testing "unknown silo errors with configured list"
    (let [{:keys [exit out]} (run-cli "--silo" "nope" "list")]
      (is (= 3 exit))
      (is (str/includes? out "notes")))))

(deftest find-command
  (testing "find with query prints matching paths"
    (let [{:keys [exit out]} (run-cli "find" "alpha")]
      (is (zero? exit))
      (is (= ["20240101T000000--alpha__clojure.org"] (str/split-lines out)))))
  (testing "find without matches exits 6"
    (is (= 6 (:exit (run-cli "find" "zzz"))))))

(deftest open-command
  (testing "open runs the editor (EDITOR=true) and succeeds"
    (is (zero? (:exit (run-cli "open" "alpha"))))))

(deftest find-fzf-command
  ;; head -1 stands in for fzf: it "selects" the first candidate line.
  (let [fzf-config (str (temp-dir "denote-cli-fzf") "/config.edn")
        base (read-string (slurp *config-path*))]
    (spit fzf-config (pr-str (assoc base :tools {:fzf ["head" "-1"]})))
    (testing "--fzf narrows to the selector's choice"
      (let [{:keys [exit out]} (cli/run ["--config" fzf-config "find" "--fzf"]
                                        *harness*)]
        (is (zero? exit))
        (is (= ["20240101T000000--alpha__clojure.org"] (str/split-lines out)))))
    (testing "a cancelling selector exits with no-match"
      (spit fzf-config (pr-str (assoc base :tools {:fzf ["false"]})))
      (let [{:keys [exit out]} (cli/run ["--config" fzf-config "find" "--fzf"]
                                        *harness*)]
        (is (= 6 exit))
        (is (str/includes? out "cancelled"))))
    (testing "missing selector falls back to all matches"
      (spit fzf-config
            (pr-str (assoc base
                      :tools {:fzf ["definitely-not-a-real-tool-xyz"]})))
      (let [{:keys [exit out]} (cli/run ["--config" fzf-config "find" "--fzf"]
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
  (testing "file outside silo exits with validation code"
    (is (= 3 (:exit (run-cli "rename" "/etc/hosts" "--title" "x"))))))

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
                                 "--front-matter" "none")))))))

(deftest rename-many-command
  (testing "without --yes prints plan and asks for confirmation"
    (let [{:keys [exit out]}
            (run-cli "rename-many" "--add-keyword"
                     "extra" (str *notes-root*
                                  "/20240101T000000--alpha__clojure.org"))]
      (is (= 3 exit))
      (is (str/includes? out "--yes"))))
  (testing "with --yes applies the batch"
    (let [{:keys [exit out]}
            (run-cli "rename-many"
                     "--add-keyword" "extra"
                     "--front-matter" "none"
                     "--yes" (str *notes-root*
                                  "/20240101T000000--alpha__clojure.org"))]
      (is (zero? exit))
      (is (str/includes? out "Renamed 1"))
      (is (.exists (java.io.File.
                     (str *notes-root*
                          "/20240101T000000--alpha__clojure_extra.org")))))))

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
  (testing "validate"
    (is (zero? (:exit (run-cli "seq" "validate" "1=1"))))
    (is (= 3 (:exit (run-cli "seq" "validate" "not-a-seq")))))
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
  (testing "--prefix rebases indentation on the subtree"
    (let [{:keys [out]} (run-cli "seq" "tree" "--prefix" "1=1")]
      (is (= ["1=1  20250102T000000==1=1--one-one.org"
              "  1=1=1  20250103T000000==1=1=1--one-one-one.org"]
             (str/split-lines out)))))
  (testing "--depth limits the tree"
    (let [{:keys [out]} (run-cli "seq" "tree" "--depth" "2")]
      (is (= ["1  20250101T000000==1--one.org"
              "  1=1  20250102T000000==1=1--one-one.org"
              "2  20250104T000000==2--two.org"]
             (str/split-lines out))))))

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
      (is (str/includes? out "rename-many"))
      (is (str/includes? out "--break-links"))
      (is (str/includes? out "as-parent"))
      (let [script (str (temp-dir "denote-completions") "/denote.bash")]
        (spit script out)
        (is (zero? (:exit (process/run ["bash" "-n" script])))))))
  (testing "zsh script has compdef header and parses with zsh -n"
    (let [{:keys [exit out]} (run-cli "completions" "zsh")]
      (is (zero? exit))
      (is (str/starts-with? out "#compdef denote"))
      (is (str/includes? out "'rename:Rename one note'"))
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
