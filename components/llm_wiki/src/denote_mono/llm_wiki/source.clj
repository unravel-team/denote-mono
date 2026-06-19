(ns denote-mono.llm-wiki.source
  (:require [clojure.string :as str]
            [denote-mono.config.interface :as config]
            [denote-mono.file-type.interface :as file-type]
            [denote-mono.filesystem.interface :as fs]
            [denote-mono.process.interface :as process])
  (:import (java.io File)
           (java.net URI)
           (java.net.http HttpClient
                          HttpClient$Redirect
                          HttpHeaders
                          HttpRequest
                          HttpResponse
                          HttpResponse$BodyHandlers)
           (java.nio.charset Charset StandardCharsets)
           (java.security MessageDigest)
           (java.time Duration)
           (java.util Optional)))

(def ^:private default-fetch-timeout-ms 15000)

(defn sha256
  "SHA-256 hex digest of TEXT."
  [text]
  (let [bytes (.digest ^MessageDigest
                       (doto (MessageDigest/getInstance "SHA-256")
                         (.update (.getBytes ^String text "UTF-8"))))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) bytes))))

(defn url?
  "True when SOURCE is an HTTP/HTTPS URL."
  [source]
  (let [source (str source)]
    (or (str/starts-with? source "http://")
        (str/starts-with? source "https://"))))

(defn file-uri-path
  "Return the local path portion of a file: URI, or PATH unchanged."
  [path]
  (cond (str/starts-with? path "file://") (subs path 7)
        (str/starts-with? path "file:") (subs path 5)
        :else path))

(defn- basename
  [path]
  (if-let [i (str/last-index-of path "/")]
    (subs path (inc i))
    path))

(defn- url-display-name
  [url]
  (let [^URI uri (URI/create url)
        path (.getPath uri)
        base (when-not (str/blank? path) (basename path))]
    (if (str/blank? base) (.getHost uri) base)))

(defn display-name
  "Human display name for a source URI/path."
  [source]
  (if (url? source)
    (url-display-name source)
    (basename (file-uri-path source))))

(defn file-uri
  "Canonical file: URI for SOURCE-PATH."
  [source-path]
  (str "file:" (fs/canonical (file-uri-path source-path))))

(defn source-uri
  "Normalized source URI for SOURCE-REF. URLs stay unchanged; files become
  canonical file: URIs."
  [source-ref]
  (if (url? source-ref) source-ref (file-uri source-ref)))

(defn- valid-readable? [path] (.canRead (File. ^String path)))

(defn- normalize-source-path
  "Expand a leading `~/` using CONTEXT env, and strip `file:` prefixes."
  [context source-path]
  (-> source-path
      file-uri-path
      (config/expand-home (or (:env context) {}))))

(defn- pdf-source?
  [source-path]
  (str/ends-with? (str/lower-case (str source-path)) ".pdf"))

(defn- local-source-kind
  [source-path]
  (cond (pdf-source? source-path) :pdf-file
        (file-type/text-file? source-path) :text-file))

(defn- validate-source-file!
  [source-path]
  (when-not (fs/exists? source-path)
    (throw (ex-info (str "Source does not exist: " source-path)
                    {:type :validation, :path source-path})))
  (when-not (valid-readable? source-path)
    (throw (ex-info (str "Source is not readable: " source-path)
                    {:type :validation, :path source-path})))
  (when (fs/directory? source-path)
    (throw (ex-info (str "Source is a directory: " source-path)
                    {:type :validation, :path source-path}))))

(defn validate-source!
  [source-path]
  (validate-source-file! source-path)
  (when-not (local-source-kind source-path)
    (throw (ex-info (str "Source is not a supported text or PDF file: "
                         source-path)
                    {:type :validation, :path source-path}))))

(defn- pdftotext-argv
  [context source-path]
  (into (vec (get-in context [:config :tools :pdftotext] ["pdftotext"]))
        ["-layout" source-path "-"]))

(defn- run-pdftotext
  [context source-path]
  (let [argv (pdftotext-argv context source-path)
        {:keys [exit out err error]} (process/run argv {})]
    (when-not (= 0 exit)
      (throw (ex-info (str "pdftotext failed for PDF source: "
                           source-path
                           (if (= :missing-binary error)
                             " (missing binary)"
                             (str " (exit " exit ")"))
                           (when-not (str/blank? err) (str " " err)))
                      {:type :tool,
                       :path source-path,
                       :command argv,
                       :exit exit,
                       :error error,
                       :stderr err})))
    (when (str/blank? out)
      (throw (ex-info (str "PDF has no extractable text; OCR not supported yet"
                           " for "
                           source-path)
                      {:type :validation, :path source-path})))
    out))

(defn- build-file-source
  [source-ref canonical-path kind content]
  {:input source-ref,
   :kind kind,
   :path canonical-path,
   :uri (file-uri canonical-path),
   :display-name (display-name canonical-path),
   :content content,
   :fingerprint {:sha256 (sha256 content),
                 :mtime (.toEpochMilli ^java.time.Instant
                                       (fs/file-mtime canonical-path))}})

(defn prepare-file-source
  "Return a canonical source record for a local file."
  [context source-path _opts]
  (let [expanded (normalize-source-path context source-path)
        path (fs/canonical expanded)]
    (validate-source-file! path)
    (case (local-source-kind path)
      :pdf-file (build-file-source source-path
                                   path
                                   :pdf-file
                                   (run-pdftotext context path))
      :text-file
        (build-file-source source-path path :text-file (fs/read-text path))
      (throw (ex-info (str "Source is not a supported text file: " path)
                      {:type :validation, :path path})))))

(defn- first-header
  [^HttpHeaders headers name]
  (let [^Optional value (.firstValue headers name)]
    (when (.isPresent value) (.get value))))

(defn- charset-from-content-type
  [content-type]
  (let [charset-name (some->> (str/split (or content-type "") #";")
                              (map str/trim)
                              (keep (fn [part]
                                      (when (str/starts-with? (str/lower-case
                                                                part)
                                                              "charset=")
                                        (subs part 8))))
                              first)]
    (if (str/blank? charset-name)
      StandardCharsets/UTF_8
      (try (Charset/forName charset-name)
           (catch Exception _ StandardCharsets/UTF_8)))))

(defn fetch-url
  "Fetch URL using the JDK HTTP client. Returns status, headers, byte body,
  and final URL after normal redirects."
  [url opts]
  (let [timeout-ms (long (or (:fetch-timeout-ms opts) default-fetch-timeout-ms))
        timeout (Duration/ofMillis timeout-ms)
        ^HttpClient client (-> (HttpClient/newBuilder)
                               (.connectTimeout timeout)
                               (.followRedirects HttpClient$Redirect/NORMAL)
                               .build)
        ^HttpRequest request (-> (HttpRequest/newBuilder (URI/create url))
                                 (.timeout timeout)
                                 (.header "User-Agent" "denote-mono-llm-wiki")
                                 .GET
                                 .build)
        ^HttpResponse response
          (.send client request (HttpResponse$BodyHandlers/ofByteArray))
        ^HttpHeaders headers (.headers response)]
    {:status (.statusCode response),
     :content-type (first-header headers "Content-Type"),
     :etag (first-header headers "ETag"),
     :last-modified (first-header headers "Last-Modified"),
     :final-url (str (.uri response)),
     :body ^bytes (.body response)}))

(def ^:private common-entities
  {"amp" "&", "lt" "<", "gt" ">", "quot" "\"", "apos" "'", "nbsp" " "})

(defn- decode-entity
  [match]
  (let [match (if (vector? match) (first match) match)
        entity (subs match 1 (dec (count match)))]
    (cond (contains? common-entities entity) (common-entities entity)
          (str/starts-with? entity "#x")
            (try (String. ^chars
                          (Character/toChars (Integer/parseInt (subs entity 2)
                                                               16)))
                 (catch Exception _ (str "&" entity ";")))
          (str/starts-with? entity "#")
            (try (String. ^chars
                          (Character/toChars (Integer/parseInt (subs entity
                                                                     1))))
                 (catch Exception _ (str "&" entity ";")))
          :else (str "&" entity ";"))))

(defn- collapse-text
  [text]
  (->> (str/split-lines text)
       (map #(str/replace % #"[\t\x0B\f\r ]+" " "))
       (map str/trim)
       (remove str/blank?)
       (str/join "\n")))

(defn html->text
  "Small dependency-free HTML to plain-text extractor for source ingestion."
  [html]
  (->
    html
    (str/replace #"(?is)<script\b[^>]*>.*?</script>" " ")
    (str/replace #"(?is)<style\b[^>]*>.*?</style>" " ")
    (str/replace #"(?is)<noscript\b[^>]*>.*?</noscript>" " ")
    (str/replace
      #"(?i)<\s*(br|p|div|section|article|header|footer|li|ul|ol|h[1-6]|tr|table|blockquote)\b[^>]*>"
      "\n")
    (str/replace
      #"(?i)</\s*(p|div|section|article|header|footer|li|ul|ol|h[1-6]|tr|table|blockquote)\s*>"
      "\n")
    (str/replace #"<[^>]+>" " ")
    (str/replace #"&(#x[0-9A-Fa-f]+|#\d+|[A-Za-z]+);" decode-entity)
    collapse-text))

(defn- html-content-type?
  [content-type]
  (str/includes? (str/lower-case (or content-type "")) "text/html"))

(defn- supported-text-content-type?
  [content-type]
  (let [content-type (str/lower-case (or content-type "text/plain"))]
    (or (str/starts-with? content-type "text/")
        (str/includes? content-type "markdown")
        (str/includes? content-type "json")
        (str/includes? content-type "xml"))))

(defn- fetch-url-or-throw
  [source-url opts]
  (try (fetch-url source-url opts)
       (catch InterruptedException e
         (.interrupt (Thread/currentThread))
         (throw (ex-info (str "URL fetch interrupted: " source-url)
                         {:type :validation, :url source-url}
                         e)))
       (catch Exception e
         (throw (ex-info (str "URL fetch failed: "
                              source-url
                              (when-let [message (ex-message e)]
                                (str " (" message ")")))
                         {:type :validation, :url source-url}
                         e)))))

(defn- validate-url-response!
  [source-url {:keys [status content-type]}]
  (when-not (<= 200 status 299)
    (throw (ex-info (str "URL fetch failed with HTTP status " status
                         ": " source-url)
                    {:type :validation, :url source-url, :status status})))
  (when-not (supported-text-content-type? content-type)
    (throw
      (ex-info
        (str "URL content type is not supported: " (or content-type "unknown"))
        {:type :validation, :url source-url, :content-type content-type}))))

(defn- response->text
  [source-url {:keys [content-type body]}]
  (let [charset (charset-from-content-type content-type)
        decoded (String. ^bytes body ^Charset charset)
        content (if (html-content-type? content-type)
                  (html->text decoded)
                  (str/trim decoded))]
    (when (str/blank? content)
      (throw (ex-info (str "URL produced no extractable text: " source-url)
                      {:type :validation, :url source-url})))
    content))

(defn- response-fingerprint
  [content {:keys [etag last-modified final-url]}]
  (cond-> {:sha256 (sha256 content)}
    etag (assoc :etag etag)
    last-modified (assoc :last-modified last-modified)
    final-url (assoc :final-url final-url)))

(defn prepare-url-source
  "Fetch and extract an HTTP/HTTPS URL into a source record."
  [context source-url opts]
  (let [response (fetch-url-or-throw source-url
                                     (merge (get-in context [:config :llm-wiki])
                                            opts))]
    (validate-url-response! source-url response)
    (let [content (response->text source-url response)]
      {:input source-url,
       :kind :url,
       :uri source-url,
       :display-name (display-name source-url),
       :content content,
       :fingerprint (response-fingerprint content response)})))

(defn prepare-source
  "Return a canonical source record for SOURCE.

  SOURCE may be a local text/PDF file path (including `~/` or `file:`) or
  an HTTP/HTTPS URL."
  [context source opts]
  (if (url? source)
    (prepare-url-source context source opts)
    (prepare-file-source context source opts)))
