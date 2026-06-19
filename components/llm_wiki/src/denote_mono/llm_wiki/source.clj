(ns denote-mono.llm-wiki.source
  (:require [clojure.string :as str]
            [denote-mono.config.interface :as config]
            [denote-mono.file-type.interface :as file-type]
            [denote-mono.filesystem.interface :as fs])
  (:import (java.io File)
           (java.security MessageDigest)))

(defn sha256
  "SHA-256 hex digest of TEXT."
  [text]
  (let [bytes (.digest ^MessageDigest
                       (doto (MessageDigest/getInstance "SHA-256")
                         (.update (.getBytes ^String text "UTF-8"))))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) bytes))))

(defn- strip-file-uri
  [path]
  (cond (str/starts-with? path "file://") (subs path 7)
        (str/starts-with? path "file:") (subs path 5)
        :else path))

(defn- basename
  [path]
  (if-let [i (str/last-index-of path "/")]
    (subs path (inc i))
    path))

(defn- valid-readable? [path] (.canRead (File. ^String path)))

(defn- normalize-source-path
  "Expand a leading `~/` using CONTEXT env, and strip `file:` prefixes."
  [context source-path]
  (-> source-path
      strip-file-uri
      (config/expand-home (or (:env context) {}))))

(defn validate-source!
  [source-path]
  (when-not (fs/exists? source-path)
    (throw (ex-info (str "Source does not exist: " source-path)
                    {:type :validation, :path source-path})))
  (when-not (valid-readable? source-path)
    (throw (ex-info (str "Source is not readable: " source-path)
                    {:type :validation, :path source-path})))
  (when (fs/directory? source-path)
    (throw (ex-info (str "Source is a directory: " source-path)
                    {:type :validation, :path source-path})))
  (when-not (file-type/text-file? source-path)
    (throw (ex-info (str "Source is not a supported text file: " source-path)
                    {:type :validation, :path source-path}))))

(defn prepare-source
  "Return a canonical source record for local text-file PATH.

  Returns {:input PATH :kind :text-file :uri FILE-URI
   :display-name NAME
   :content STRING :fingerprint {:sha256 HEX :mtime MILLIS}}.
  PATH may start with `~/` or `file:`."
  [context source-path _opts]
  (let [expanded (normalize-source-path context source-path)
        _ (validate-source! expanded)
        path (fs/canonical expanded)
        content (fs/read-text path)
        mtime (.toEpochMilli ^java.time.Instant (fs/file-mtime path))]
    {:input source-path,
     :kind :text-file,
     :path path,
     :uri (str "file:" path),
     :display-name (basename path),
     :content content,
     :fingerprint {:sha256 (sha256 content), :mtime mtime}}))
