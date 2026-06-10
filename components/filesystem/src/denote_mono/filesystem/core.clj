(ns denote-mono.filesystem.core
  "Filesystem primitives: directory walking with Denote's filters, text IO,
  and collision-safe renames. Hides Java NIO details from higher layers."
  (:require [clojure.string :as str])
  (:import (java.io File)
           (java.nio.file Files LinkOption Path Paths StandardCopyOption)
           (java.time Instant)))

(defn- to-path ^Path [s] (Paths/get ^String s (make-array String 0)))

(def ^:private no-follow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(def ^:private follow (make-array LinkOption 0))

(defn canonical
  "Real path of S when it exists, else the normalized absolute path."
  [s]
  (let [p (.toAbsolutePath (to-path s))]
    (str (try (.toRealPath p follow)
              (catch java.io.IOException _ (.normalize p))))))

(defn inside-root?
  "True when PATH resolves inside ROOT after canonicalization.
  [ref:silo_path_containment]"
  [root path]
  (let [root-path (to-path (canonical root))
        target (to-path (canonical path))]
    (.startsWith target root-path)))

(defn backup-file?
  "True for Emacs-style backup (name~) and auto-save (#name#) files."
  [path]
  (let [base (.getName (File. ^String path))]
    (boolean (or (str/ends-with? base "~")
                 (and (str/starts-with? base "#") (str/ends-with? base "#"))))))

(defn- hidden-name? [^File f] (str/starts-with? (.getName f) "."))

(defn- list-files-in-root
  [root
   {:keys [follow-symlinks? skip-backups? excluded-directories-regex],
    :or {follow-symlinks? true, skip-backups? true}}]
  (let [root-canonical (canonical root)
        excluded-dir-pattern (some-> excluded-directories-regex
                                     re-pattern)
        result (transient [])
        seen-dirs (volatile! #{})]
    (letfn
      [(walk [^File dir]
         (doseq [^File entry (sort-by #(.getName ^File %)
                                      (or (.listFiles dir) []))]
           (let [path (.getPath entry)
                 entry-path (to-path path)
                 symlink? (Files/isSymbolicLink entry-path)]
             (cond (hidden-name? entry) nil
                   (Files/isDirectory entry-path
                                      (if follow-symlinks? follow no-follow))
                     (when (and (or follow-symlinks? (not symlink?))
                                (not (and excluded-dir-pattern
                                          (re-find excluded-dir-pattern path)))
                                (inside-root? root-canonical path))
                       ;; Track real paths to break symlink cycles.
                       (let [real (canonical path)]
                         (when-not (@seen-dirs real)
                           (vswap! seen-dirs conj real)
                           (walk entry))))
                   (Files/isRegularFile entry-path
                                        (if follow-symlinks? follow no-follow))
                     (when (and (or follow-symlinks? (not symlink?))
                                (.canRead entry)
                                (not (and skip-backups? (backup-file? path)))
                                (inside-root? root-canonical path))
                       (conj! result path))))))]
      (walk (File. ^String root-canonical)))
    (persistent! result)))

(defn list-files
  "Recursively list readable regular files under ROOTS, skipping dot
  directories and dotfiles, applying Denote's exclusion and backup-file
  filters. Returns absolute path strings."
  [roots {:keys [excluded-files-regex], :as opts}]
  (let [files (into [] (mapcat #(list-files-in-root % opts)) roots)
        excluded-file-pattern (some-> excluded-files-regex
                                      re-pattern)]
    (if excluded-file-pattern
      (filterv #(not (re-find excluded-file-pattern %)) files)
      files)))

(defn read-text [path] (slurp (File. ^String path) :encoding "UTF-8"))

(defn write-text
  [path content]
  (let [f (File. ^String path)]
    (when-let [parent (.getParentFile f)] (.mkdirs parent))
    (spit f content :encoding "UTF-8")))

(defn rename-file
  "Rename SOURCE to DEST. Refuses an existing destination unless
  {:overwrite? true}."
  [source dest {:keys [overwrite?]}]
  (let [dest-path (to-path dest)]
    (when (and (not overwrite?) (Files/exists dest-path no-follow))
      (throw (ex-info "Destination already exists"
                      {:type :collision, :source source, :destination dest})))
    (Files/move (to-path source)
                dest-path
                (if overwrite?
                  (into-array StandardCopyOption
                              [StandardCopyOption/REPLACE_EXISTING])
                  (make-array StandardCopyOption 0)))
    dest))

(defn file-mtime
  [path]
  (Instant/ofEpochMilli (.lastModified (File. ^String path))))

(defn writable? [path] (.canWrite (File. ^String path)))

(defn exists? [path] (.exists (File. ^String path)))

(defn directory? [path] (.isDirectory (File. ^String path)))
