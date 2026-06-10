(ns denote-mono.sequence.core
  "Folgezettel sequence operations, ported from denote-sequence.el.
  A sequence lives in the Denote signature component. All functions take
  the selected scheme explicitly (:numeric, :alphanumeric, or
  :alphanumeric-delimited) where the Emacs source consulted the
  denote-sequence-scheme variable."
  (:require [clojure.string :as str]
            [denote-mono.filename.interface :as filename]))

(def schemes [:numeric :alphanumeric :alphanumeric-delimited])

(defn- error!
  [message data]
  (throw (ex-info message (merge {:type :validation} data))))

;;;; Validators (denote-sequence-numeric-p and friends)

(defn numeric?
  [sequence]
  (boolean (and (re-find #"=?[0-9]+" sequence)
                (not (re-find #"[a-zA-Z]" sequence))
                (not (str/ends-with? sequence "=")))))

(defn alphanumeric?
  [sequence]
  (boolean (and (re-find #"[0-9]+" sequence)
                (re-find #"\A[0-9]+" sequence)
                (not (str/includes? sequence "=")))))

(defn- delimited-split
  "Split into runs of digits, runs of letters, and = signs."
  [sequence]
  (vec (re-seq #"[0-9]+|[a-zA-Z]+|=" sequence)))

(defn- delimited-alternation?
  [strings]
  (loop [strings strings
         last-type nil]
    (if-let [[s & more] (seq strings)]
      (if (= s "=")
        (recur more last-type)
        (let [current-type (if (re-matches #"[0-9]+" s) :numeric :alpha)]
          (if (= current-type last-type) false (recur more current-type))))
      true)))

(defn- delimited-depths?
  "One level before the first =, then exactly three per inner group and up
  to three in the last group."
  [strings]
  (let [depths (map count
                 (remove #(= % ["="]) (partition-by #(= % "=") strings)))]
    (and (every? #(<= % 3) (rest depths))
         (= 1 (first depths))
         (or (<= (count depths) 2) (every? #(= % 3) (butlast (rest depths)))))))

(defn alphanumeric-delimited?
  [sequence]
  (cond (re-matches #"[0-9]+" sequence) true
        (and (str/includes? sequence "=") (not (numeric? sequence)))
          (let [strings (delimited-split sequence)]
            (boolean (and (delimited-alternation? strings)
                          (delimited-depths? strings))))
        :else false))

(defn valid-for-scheme?
  [scheme sequence]
  (case scheme
    :numeric (numeric? sequence)
    :alphanumeric (alphanumeric? sequence)
    :alphanumeric-delimited (alphanumeric-delimited? sequence)))

(defn valid?
  [sequence]
  (boolean (some #(valid-for-scheme? % sequence) schemes)))

;;;; Scheme inference (denote-sequence-and-scheme-p)

(defn- numeric-partial? [s] (boolean (re-matches #"[0-9]+" s)))

(defn- alpha-partial? [s] (boolean (re-matches #"[a-zA-Z]+" s)))

(defn scheme-of
  "Scheme of SEQUENCE, with DEFAULT-SCHEME deciding ambiguous all-digit
  sequences. With PARTIAL?, SEQUENCE is a single level of depth."
  ([sequence default-scheme] (scheme-of sequence default-scheme false))
  ([sequence default-scheme partial?]
   (cond (and (not partial?) (re-matches #"[0-9]+" sequence)) default-scheme
         (and (not partial?)
              (not (re-find #"[a-zA-Z]" sequence))
              (= default-scheme :numeric))
           :numeric
         (or (and partial? (alpha-partial? sequence)) (alphanumeric? sequence))
           :alphanumeric
         (or (and partial? (numeric-partial? sequence)) (numeric? sequence))
           :numeric
         (or (and partial?
                  (or (numeric-partial? sequence) (alpha-partial? sequence)))
             (alphanumeric-delimited? sequence))
           :alphanumeric-delimited
         :else (error! (str "Cannot determine scheme of sequence: " sequence)
                       {:sequence sequence}))))

;;;; Split and join

(defn join
  [scheme parts]
  (case scheme
    :numeric (str/join "=" parts)
    :alphanumeric (apply str parts)
    :alphanumeric-delimited
      (loop [i 0
             [part & more] parts
             acc ""]
        (if part
          (recur (inc i)
                 more
                 (str acc part (when (and (zero? (mod i 3)) more) "=")))
          acc))))

(defn split
  "Split SEQUENCE into its levels of depth."
  ([sequence] (split sequence :numeric false))
  ([sequence default-scheme partial?]
   (let [scheme (scheme-of sequence default-scheme partial?)]
     (if (= scheme :numeric)
       (vec (remove str/blank? (str/split sequence #"=")))
       (let [bare (str/replace sequence "=" "")
             parts (vec (mapcat (fn [[_ digits letters]]
                                  (if letters [digits letters] [digits]))
                          (re-seq #"([0-9]+)([a-zA-Z]+)?" bare)))]
         (if (seq parts) parts (mapv str bare)))))))

(defn depth [sequence] (count (split sequence)))

(defn- children-implied? [sequence] (> (depth sequence) 1))

;;;; Partial increment/decrement and conversion

(defn increment-part
  [part]
  (cond (numeric-partial? part) (str (inc (parse-long part)))
        (alpha-partial? part)
          (let [last-char (last part)]
            (cond (= part "z") "za"
                  (= 1 (count part)) (str (char (inc (int last-char))))
                  (= last-char \z) (str part "a")
                  :else (str (subs part 0 (dec (count part)))
                             (char (inc (int last-char))))))
        :else (error! (str "Not a sequence part: " part) {:part part})))

(defn decrement-part
  [part]
  (cond (numeric-partial? part) (let [n (parse-long part)]
                                  (when (> n 1) (str (dec n))))
        (alpha-partial? part)
          (let [last-char (last part)]
            (cond (= part "a") nil
                  (= 1 (count part)) (str (char (dec (int last-char))))
                  (= last-char \a) (subs part 0 (dec (count part)))
                  :else (str (subs part 0 (dec (count part)))
                             (char (dec (int last-char))))))
        :else (error! (str "Not a sequence part: " part) {:part part})))

(defn- alpha->number
  "Nonstandard alphabetic value: the sum of letter values (a=1 .. z=26),
  e.g. za = 27, zzzzz = 130."
  [s]
  (str (reduce + (map #(- (int %) 96) s))))

(defn- number->alpha
  [s]
  (let [num (parse-long s)]
    (cond (zero? num) "a"
          (<= num 26) (str (char (+ num 96)))
          :else (let [times (quot num 26)
                      remainder (rem num 26)
                      prefix (apply str (repeat times \z))]
                  (if (pos? remainder)
                    (str prefix (char (+ remainder 96)))
                    prefix)))))

(defn convert-part
  [part target-scheme]
  (if (= target-scheme :numeric) (alpha->number part) (number->alpha part)))

(defn convert
  "Convert SEQUENCE to TARGET-SCHEME, preserving Denote's nonstandard
  letter arithmetic."
  [sequence target-scheme]
  (when-not (some #{target-scheme} schemes)
    (error! (str "Unknown target scheme: " target-scheme)
            {:scheme target-scheme}))
  (if (= target-scheme :numeric)
    (if (numeric? sequence)
      sequence
      (join :numeric
            (map #(if (numeric-partial? %) % (alpha->number %))
              (split sequence))))
    (if (alphanumeric? sequence)
      sequence
      (join target-scheme
            (map-indexed (fn [i part]
                           (cond (even? i) part
                                 (alpha-partial? part) part
                                 :else (number->alpha part)))
                         (split sequence))))))

;;;; Inferred relatives (no file knowledge)

(defn infer-parent
  [sequence default-scheme]
  (when (children-implied? sequence)
    (join (scheme-of sequence default-scheme) (butlast (split sequence)))))

(defn infer-child
  [sequence default-scheme]
  (let [scheme (scheme-of sequence default-scheme)
        parts (split sequence)
        last-part (peek parts)
        new-depth (cond (= scheme :numeric) "1"
                        (numeric-partial? last-part) "a"
                        :else "1")]
    (join scheme (conj parts new-depth))))

(defn infer-sibling
  [sequence direction default-scheme]
  (let [scheme (scheme-of sequence default-scheme)
        parts (split sequence)
        step (case direction
               :next increment-part
               :previous decrement-part)]
    (when-let [new-part (step (peek parts))]
      (join scheme (conj (vec (butlast parts)) new-part)))))

;;;; Sorting and largest-of (denote-sequence--pad and friends)

(defn- pad-part [s] (str (apply str (repeat (- 5 (count s)) " ")) s))

(defn- pad-single [s] (str (apply str (repeat (- 32 (count s)) " ")) s))

(defn- pad
  [sequence type]
  (let [parts (split sequence)
        chosen (cond (= type :all) parts
                     (children-implied? sequence) (case type
                                                    :parent (first parts)
                                                    :sibling parts
                                                    :child (peek parts))
                     :else sequence)]
    (if (coll? chosen)
      (str/join "=" (map pad-part chosen))
      (pad-single chosen))))

(defn sort-sequences [sequences] (vec (sort-by #(pad % :all) sequences)))

(defn keep-siblings
  "Sorted SEQUENCES strictly :greater or :lesser than SEQUENCE."
  [direction sequence sequences]
  (let [target (pad sequence :all)
        comparison (case direction
                     :greater pos?
                     :lesser neg?)]
    (vec (filter #(comparison (compare (pad % :all) target))
           (sort-sequences sequences)))))

(defn- largest-by-order
  [sequences type]
  (last (sort-by #(pad % type) sequences)))

(defn- length-sans-delimiter
  [sequence scheme]
  (if (= scheme :alphanumeric)
    (count sequence)
    (count (str/replace sequence "=" ""))))

(defn- largest-by-length
  [sequences scheme]
  (let [longest (apply max (map #(length-sans-delimiter % scheme) sequences))
        largest (filter #(= longest (length-sans-delimiter % scheme))
                  sequences)]
    (if (= 1 (count largest)) (first largest) largest)))

(defn- largest
  [sequences type scheme]
  (if (= type :child)
    (let [result (largest-by-length sequences scheme)]
      (if (coll? result) (largest-by-order result type) result))
    (largest-by-order sequences type)))

;;;; Collections of sequences

(defn filter-scheme
  [sequences scheme]
  (filterv #(valid-for-scheme? scheme %) sequences))

(defn- prefix?
  [prefix-parts sequence scheme]
  (and (valid-for-scheme? scheme sequence)
       (= prefix-parts (vec (take (count prefix-parts) (split sequence))))))

(defn sequences-with-prefix
  [sequences sequence scheme]
  (let [prefix-parts (split sequence)]
    (filterv #(prefix? prefix-parts % scheme) sequences)))

(defn- sequences-with-max-depth
  [sequences max-depth]
  (vec (distinct (filter #(<= (depth %) max-depth) sequences))))

(defn- get-start
  "First component of a new level of depth under SEQUENCE."
  [scheme sequence]
  (if (= scheme :numeric)
    "1"
    (cond (nil? sequence) "1"
          (alpha-partial? (str (last sequence))) "1"
          :else "a")))

;;;; New parent/child/sibling (denote-sequence-get-new)

(defn next-parent
  [sequences scheme]
  (if (empty? sequences)
    (get-start scheme nil)
    (let [largest-parent (largest sequences :parent scheme)
          first-component (first (split largest-parent))]
      (str (inc (parse-long first-component))))))

(defn next-child
  [sequences sequence scheme]
  (let [max-depth (inc (depth sequence))
        with-prefix (sequences-with-prefix sequences sequence scheme)
        start-child (get-start scheme sequence)]
    (cond (empty? with-prefix) (error! (str "Cannot find sequences for "
                                            sequence)
                                       {:sequence sequence, :scheme scheme})
          (= 1 (count with-prefix)) (join scheme
                                          (conj (split sequence) start-child))
          :else (let [bounded (or (not-empty (sequences-with-max-depth
                                               with-prefix
                                               max-depth))
                                  with-prefix)
                      candidates (filter-scheme bounded scheme)
                      largest-child (largest candidates :child scheme)]
                  (if (children-implied? largest-child)
                    (let [child-scheme (scheme-of largest-child scheme)
                          parts (split largest-child)]
                      (join child-scheme
                            (conj (vec (butlast parts))
                                  (increment-part (peek parts)))))
                    (join scheme (conj (split largest-child) start-child)))))))

(defn- sibling-prefix
  [sequence scheme]
  (when (children-implied? sequence)
    (join (scheme-of sequence scheme) (butlast (split sequence)))))

(defn next-sibling
  [sequences sequence scheme]
  (let [siblings? (children-implied? sequence)
        max-depth (depth sequence)
        unfiltered (if siblings?
                     (sequences-with-prefix sequences
                                            (sibling-prefix sequence scheme)
                                            scheme)
                     sequences)
        bounded (sequences-with-max-depth unfiltered max-depth)
        candidates (filter-scheme bounded scheme)]
    (when-not (some #{sequence} candidates)
      (error! (str "Cannot find sequences for " sequence)
              {:sequence sequence, :scheme scheme}))
    (if siblings?
      (let [largest-sibling (largest candidates :sibling scheme)
            sibling-scheme (scheme-of largest-sibling scheme)
            parts (split largest-sibling)]
        (join sibling-scheme
              (conj (vec (butlast parts)) (increment-part (peek parts)))))
      (str (inc (parse-long (largest candidates :parent scheme)))))))

;;;; Relatives among existing sequences

(defn relative
  "Find TYPE relatives of SEQUENCE among SEQUENCES. TYPE :parent returns a
  single sequence; the rest return vectors."
  [sequences sequence type scheme]
  (let [seq-depth (depth sequence)
        seq-scheme (scheme-of sequence scheme)
        parts (split sequence)
        with-depth (fn [comparison prefix]
                     (filterv #(comparison (depth %) seq-depth)
                       (if (and prefix (not (str/blank? prefix)))
                         (sequences-with-prefix sequences prefix scheme)
                         sequences)))]
    (case type
      :parent (let [parent (join seq-scheme (butlast parts))]
                (some #(when (= % parent) %) sequences))
      :all-parents (let [ancestors (map #(join seq-scheme (take % parts))
                                     (range 1 seq-depth))]
                     (filterv (set sequences) ancestors))
      :siblings (vec (remove #{sequence}
                       (with-depth = (join seq-scheme (butlast parts)))))
      :children (with-depth #(= %1 (inc %2)) sequence)
      :all-children (with-depth > sequence)
      (error! (str "Unknown relative type: " type) {:relative-type type}))))

(defn file-sequence
  "The sequence in PATH's signature component, when it is a valid sequence."
  [path]
  (when-let [signature (filename/extract path :signature)]
    (when (valid? signature) signature)))
