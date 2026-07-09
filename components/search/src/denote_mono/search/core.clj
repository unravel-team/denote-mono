(ns denote-mono.search.core
  "List, filter, and sort Denote notes inside a resolved silo."
  (:require [clojure.string :as str]
            [denote-mono.file-type.interface :as file-type]
            [denote-mono.filename.interface :as filename]
            [denote-mono.filesystem.interface :as fs]
            [denote-mono.process.interface :as process]))

(defn- note-record
  [path root-canonical silo-name config]
  (let [parsed (filename/parse path)]
    (when (:valid-denote-name? parsed)
      {:path path,
       :relative-path (subs path (inc (count root-canonical))),
       :silo silo-name,
       :filename parsed,
       :file-type (file-type/detect path nil (:filename config)),
       :mtime (fs/file-mtime path)})))

(defn- compile-filters
  "Compile regex filter strings once so per-note matching reuses Patterns,
  and lowercase the free-text query."
  [{:keys [match title query], :as filters}]
  (cond-> filters
    match (assoc :match (re-pattern match))
    title (assoc :title (re-pattern title))
    query (assoc :query (str/lower-case query))))

(defn- matches-filters?
  [{:keys [filename relative-path]}
   {:keys [match keyword signature title id query]}]
  (and (or (nil? match) (re-find match (:basename filename)))
       (or (nil? keyword) (some #{keyword} (:keywords filename)))
       (or (nil? signature) (= signature (:signature filename)))
       (or (nil? title)
           (and (:title filename) (re-find title (:title filename))))
       (or (nil? id) (= id (:identifier filename)))
       (or (nil? query) (str/includes? (str/lower-case relative-path) query))))

(defn- parent-dir
  [path]
  (let [i (str/last-index-of path "/")]
    (when (and i (pos? i)) (subs path 0 i))))

(defn- under-excluded-dir?
  "True when an ancestor directory of PATH below ROOT matches PATTERN,
  mirroring how the directory walk prunes excluded directories."
  [pattern root path]
  (loop [dir (parent-dir path)]
    (cond (or (nil? dir) (<= (count dir) (count root))) false
          (re-find pattern dir) true
          :else (recur (parent-dir dir)))))

(defn- list-files-with-fd
  "List files under the canonical ROOT with fd, applying the same filters
  as fs/list-files: backup files, exclusion regexes, and containment.
  Returns nil when fd fails so the caller falls back to the walk.

  fd skips hidden entries by default (matching the walk) but honors
  ignore files, which the walk does not, hence --no-ignore. Results are
  canonicalized so symlinked entries pointing outside the root are
  rejected by a prefix check. [ref:silo_path_containment]"
  [root
   {:keys [follow-symlinks? skip-backups? excluded-directories-regex
           excluded-files-regex],
    :or {follow-symlinks? true, skip-backups? true}} fd-argv]
  (let [{:keys [exit out error]}
          (process/run (into (vec fd-argv)
                             (concat ["--print0" "--absolute-path" "--type" "f"
                                      "--no-ignore"]
                                     (when follow-symlinks? ["--follow"])
                                     ["." root]))
                       {})]
    (when (and (not error) (zero? exit))
      (let [dir-pattern (some-> excluded-directories-regex
                                re-pattern)
            file-pattern (some-> excluded-files-regex
                                 re-pattern)]
        (->> (str/split out #"\u0000")
             (remove str/blank?)
             (remove #(and skip-backups? (fs/backup-file? %)))
             (remove #(and file-pattern (re-find file-pattern %)))
             (remove #(and dir-pattern
                           (under-excluded-dir? dir-pattern root %)))
             (keep (fn [path]
                     (let [real (fs/canonical path)]
                       (when (str/starts-with? real (str root "/")) real))))
             (distinct)
             (sort)
             (vec))))))

(defn list-notes
  "Return note records for all valid Denote files in the context's silo,
  filtered by FILTERS (:match :keyword :signature :title :id :query).
  Files are listed with fd when available, with the directory walk in
  fs/list-files as the fallback."
  [{:keys [silo config]} filters _opts]
  (let [root (fs/canonical (:path silo))
        files-opts (get config :files {})
        fd-argv (get-in config [:tools :fd] ["fd"])
        files (or (when (process/available? (first fd-argv))
                    (list-files-with-fd root files-opts fd-argv))
                  (fs/list-files [root] files-opts))
        filters (compile-filters filters)]
    (into []
          (comp (keep #(note-record % root (:name silo) config))
                (filter #(matches-filters? % filters)))
          files)))

(defn note->wire
  "Shape a note record for JSON/EDN output: stringify the mtime instant and
  the silo keyword."
  [note]
  (-> note
      (update :mtime str)
      (update :silo
              #(some-> %
                       name))))

(defn- text-notes
  [context]
  (filterv #(file-type/text-file? (:path %)) (list-notes context {} {})))

(defn- grep-with-clojure
  [notes pattern]
  (vec (for [{:keys [path relative-path]} notes
             [line-number line]
               (map-indexed vector (str/split-lines (fs/read-text path)))
             :when (re-find pattern line)]
         {:path path,
          :relative-path relative-path,
          :line-number (inc line-number),
          :line line})))

(defn- grep-with-rg
  [notes query rg-argv]
  (let [{:keys [exit out error]}
          ;; --with-filename: with a single file rg would omit the path
          ;; prefix, breaking the path:line:text parse below.
          (process/run (into (vec rg-argv)
                             (concat ["--line-number" "--no-heading"
                                      "--with-filename" "--" query]
                                     (map :path notes)))
                       {})]
    (when (and (not error) (#{0 1} exit))
      (let [by-path (into {} (map (juxt :path identity)) notes)]
        (vec (for [match-line (str/split-lines out)
                   :when (not (str/blank? match-line))
                   :let [[path line-number line] (str/split match-line #":" 3)
                         note (by-path path)]
                   :when note]
               {:path path,
                :relative-path (:relative-path note),
                :line-number (parse-long line-number),
                :line line}))))))

(defn grep
  "Search note contents for QUERY (a regex string). Uses rg when available,
  with a pure Clojure fallback. Only supported text files are read."
  [{:keys [config], :as context} query _opts]
  (let [notes (text-notes context)
        rg-argv (get-in config [:tools :rg] ["rg"])]
    (or (when (and (seq notes) (process/available? (first rg-argv)))
          (grep-with-rg notes query rg-argv))
        (grep-with-clojure notes (re-pattern query)))))

(def ^:private link-id-pattern #"denote:([0-9]{8}T[0-9]{6})")

(defn links
  "Identifiers linked from FILE's contents, resolved to note records when
  the target exists in the silo."
  [context file _opts]
  (let [ids (distinct (map second (re-seq link-id-pattern (fs/read-text file))))
        by-id (into {}
                    (keep (fn [note]
                            (when-let [id (get-in note [:filename :identifier])]
                              [id note])))
                    (list-notes context {} {}))]
    {:identifiers (vec ids), :notes (vec (keep by-id ids))}))

(defn backlinks
  "Notes whose contents link to ID via denote link syntax, excluding the
  note that carries ID itself."
  [context id _opts]
  (vec (for [{:keys [path], :as note} (text-notes context)
             :when (not= id (get-in note [:filename :identifier]))
             :when (str/includes? (fs/read-text path) (str "denote:" id))]
         note)))

(defn- nils-last [value] [(if (nil? value) 1 0) value])

(defn sort-notes
  "Sort NOTES by SORT-KEY: :identifier, :title, :keywords, :signature,
  :modified, or :random. Notes missing the component sort last."
  [notes sort-key _opts]
  (case sort-key
    :identifier (sort-by #(nils-last (get-in % [:filename :identifier])) notes)
    :title (sort-by #(nils-last (get-in % [:filename :title])) notes)
    :keywords (sort-by #(nils-last (some->> (get-in % [:filename :keywords])
                                            (filename/keywords-combine)))
                       notes)
    :signature (sort-by #(nils-last (get-in % [:filename :signature])) notes)
    :modified (sort-by :mtime notes)
    :random (shuffle notes)
    notes))
