(ns denote-mono.filesystem.interface-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [denote-mono.filesystem.interface :as fs])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:dynamic *root* nil)

(defn- temp-tree-fixture
  [f]
  (let [root (str (Files/createTempDirectory "denote-fs-test"
                                             (make-array FileAttribute 0)))]
    (spit (str root "/20240101T000000--one__kw.org") "one")
    (spit (str root "/20240102T000000--two__kw.md") "two")
    (spit (str root "/plain.org") "plain")
    (spit (str root "/backup.org~") "backup")
    (spit (str root "/#autosave.org#") "autosave")
    (io/make-parents (str root "/sub/nested.org"))
    (spit (str root "/sub/nested.org") "nested")
    (io/make-parents (str root "/.hidden/secret.org"))
    (spit (str root "/.hidden/secret.org") "secret")
    (io/make-parents (str root "/excluded/inside.org"))
    (spit (str root "/excluded/inside.org") "inside")
    (binding [*root* root] (f))))

(use-fixtures :each temp-tree-fixture)

(defn- basenames [files] (set (map #(last (str/split % #"/")) files)))

(deftest list-files-test
  (testing "default listing: regular readable files, no dot dirs, no backups"
    (is (= #{"20240101T000000--one__kw.org" "20240102T000000--two__kw.md"
             "plain.org" "nested.org" "inside.org"}
           (basenames (fs/list-files [*root*] {})))))
  (testing "excluded directories regex"
    (is (= #{"20240101T000000--one__kw.org" "20240102T000000--two__kw.md"
             "plain.org" "nested.org"}
           (basenames (fs/list-files [*root*]
                                     {:excluded-directories-regex
                                      "excluded"})))))
  (testing "excluded files regex"
    (is (not (contains? (basenames (fs/list-files [*root*]
                                                  {:excluded-files-regex
                                                   "plain"}))
                        "plain.org"))))
  (testing "backups included when skip-backups? false"
    (is (contains? (basenames (fs/list-files [*root*] {:skip-backups? false}))
                   "backup.org~"))))

(deftest symlink-containment-test
  (let [outside (str (Files/createTempDirectory "denote-fs-outside"
                                                (make-array FileAttribute 0)))]
    (spit (str outside "/external.org") "external")
    (Files/createSymbolicLink (.toPath (io/file *root* "link-out.org"))
                              (.toPath (io/file outside "external.org"))
                              (make-array FileAttribute 0))
    (testing
      "symlinked file outside root is rejected [ref:silo_path_containment]"
      (is (not (contains? (basenames (fs/list-files [*root*] {}))
                          "link-out.org"))))
    (testing "follow-symlinks? false also drops it"
      (is (not (contains? (basenames (fs/list-files [*root*]
                                                    {:follow-symlinks? false}))
                          "link-out.org"))))))

(deftest backup-file?-test
  (is (fs/backup-file? "/x/foo.org~"))
  (is (fs/backup-file? "/x/#foo.org#"))
  (is (not (fs/backup-file? "/x/foo.org"))))

(deftest read-write-rename-test
  (let [src (str *root* "/20240103T000000--three.org")]
    (fs/write-text src "three")
    (is (= "three" (fs/read-text src)))
    (let [dest (str *root* "/20240103T000000--renamed.org")]
      (fs/rename-file src dest {})
      (is (= "three" (fs/read-text dest)))
      (is (not (.exists (io/file src))))
      (testing "rename refuses existing destination"
        (fs/write-text src "again")
        (is (thrown? Exception (fs/rename-file src dest {})))))))

(deftest canonical-and-inside-root-test
  (is (fs/inside-root? *root* (str *root* "/sub/nested.org")))
  (is (not (fs/inside-root? *root* (str *root* "/../escape.org"))))
  (is (not (fs/inside-root? *root* "/etc/passwd")))
  (testing "canonical resolves dot segments"
    (is (= (fs/canonical *root*) (fs/canonical (str *root* "/sub/.."))))))

(deftest file-mtime-test (is (inst? (fs/file-mtime (str *root* "/plain.org")))))
