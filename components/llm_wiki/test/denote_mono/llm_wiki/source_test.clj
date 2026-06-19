(ns denote-mono.llm-wiki.source-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [denote-mono.config.interface :as config]
            [denote-mono.filesystem.interface :as fs]
            [denote-mono.llm-wiki.source :as source])
  (:import (com.sun.net.httpserver HttpHandler HttpServer)
           (java.io File)
           (java.net InetSocketAddress)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir
  []
  (str (Files/createTempDirectory "denote-llm-wiki-source-test"
                                  (make-array FileAttribute 0))))

(defn- make-context
  ([home] (make-context home {}))
  ([home config-overrides]
   {:env {"HOME" home},
    :config (merge-with merge (config/default-config) config-overrides)}))

(defn- sh-quote [s] (str "'" (str/replace s "'" "'\"'\"'") "'"))

(defn- fake-pdftotext!
  [dir text]
  (let [path (str dir "/fake-pdftotext")
        file (File. path)]
    (spit path (str "#!/bin/sh\nprintf '%s' " (sh-quote text) "\n"))
    (.setExecutable file true)
    path))

(defn- pdf-context
  [home extracted]
  (make-context home {:tools {:pdftotext [(fake-pdftotext! home extracted)]}}))

(defn- ex-from [f] (try (f) nil (catch Exception e e)))

(defn- with-http-server
  [routes f]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (doseq [[path {:keys [status headers body]}] routes]
      (.createContext
        server
        path
        (reify
          HttpHandler
            (handle [_ exchange]
              (doseq [[k v] headers] (.add (.getResponseHeaders exchange) k v))
              (let [bytes (.getBytes ^String body StandardCharsets/UTF_8)]
                (.sendResponseHeaders exchange (long status) (alength bytes))
                (with-open [out (.getResponseBody exchange)]
                  (.write out bytes)))))))
    (.start server)
    (try (f (str "http://127.0.0.1:" (.getPort (.getAddress server))))
         (finally (.stop server 0)))))

(deftest prepare-text-file-source-test
  (let [dir (temp-dir)
        path (str dir "/notes.md")
        content "Alpha source text.\n"]
    (spit path content)
    (let [prepared (source/prepare-source (make-context dir) path {})
          abs (fs/canonical path)]
      (is (= path (:input prepared)))
      (is (= :text-file (:kind prepared)))
      (is (= (str "file:" abs) (:uri prepared)))
      (is (= "notes.md" (:display-name prepared)))
      (is (= content (:content prepared)))
      (is (= (source/sha256 content) (get-in prepared [:fingerprint :sha256])))
      (is (integer? (get-in prepared [:fingerprint :mtime]))))))

(deftest prepare-text-file-expands-home-test
  (let [home (temp-dir)
        dir (str home "/Documents")
        _ (.mkdirs (java.io.File. dir))
        path (str dir "/quoted.txt")]
    (spit path "quoted home path")
    (testing "quoted ~/ paths expand from the command context env"
      (let [prepared (source/prepare-source (make-context home)
                                            "~/Documents/quoted.txt"
                                            {})]
        (is (= "~/Documents/quoted.txt" (:input prepared)))
        (is (= (str "file:" (fs/canonical path)) (:uri prepared)))))))

(deftest prepare-pdf-source-test
  (let [dir (temp-dir)
        pdf (str dir "/paper.pdf")
        raw-pdf "%PDF-1.4 fake bytes"
        extracted "PDF extracted text about alpha.\n"]
    (spit pdf raw-pdf)
    (let [prepared (source/prepare-source (pdf-context dir extracted) pdf {})
          abs (fs/canonical pdf)]
      (is (= pdf (:input prepared)))
      (is (= :pdf-file (:kind prepared)))
      (is (= abs (:path prepared)))
      (is (= (str "file:" abs) (:uri prepared)))
      (is (= "paper.pdf" (:display-name prepared)))
      (is (= extracted (:content prepared)))
      (testing "PDF source-hash is over extracted text, not raw PDF bytes"
        (is (= (source/sha256 extracted)
               (get-in prepared [:fingerprint :sha256])))
        (is (not= (source/sha256 raw-pdf)
                  (get-in prepared [:fingerprint :sha256]))))
      (is (integer? (get-in prepared [:fingerprint :mtime]))))))

(deftest prepare-pdf-expands-home-test
  (let [home (temp-dir)
        dir (str home "/Documents")
        _ (.mkdirs (File. dir))
        pdf (str dir "/quoted.pdf")]
    (spit pdf "%PDF")
    (let [prepared (source/prepare-source (pdf-context home
                                                       "Quoted PDF text.\n")
                                          "~/Documents/quoted.pdf"
                                          {})]
      (is (= "~/Documents/quoted.pdf" (:input prepared)))
      (is (= :pdf-file (:kind prepared)))
      (is (= (str "file:" (fs/canonical pdf)) (:uri prepared))))))

(deftest prepare-pdf-missing-tool-test
  (let [dir (temp-dir)
        pdf (str dir "/paper.pdf")]
    (spit pdf "%PDF")
    (let [ex (ex-from #(source/prepare-source
                         (make-context dir
                                       {:tools {:pdftotext
                                                ["missing-pdftotext-xyz"]}})
                         pdf
                         {}))]
      (is (= :tool (:type (ex-data ex))))
      (is (str/includes? (ex-message ex) "pdftotext")))))

(deftest prepare-pdf-blank-text-test
  (let [dir (temp-dir)
        pdf (str dir "/scanned.pdf")]
    (spit pdf "%PDF")
    (let [ex (ex-from
               #(source/prepare-source (pdf-context dir "   \n\t") pdf {}))]
      (is (= :validation (:type (ex-data ex))))
      (is (str/includes? (ex-message ex) "PDF has no extractable text")))))

(deftest prepare-url-source-test
  (with-http-server
    {"/article" {:status 200,
                 :headers {"Content-Type" "text/html; charset=UTF-8",
                           "ETag" "\"v1\"",
                           "Last-Modified" "Tue, 18 Jun 2024 10:00:00 GMT"},
                 :body (str
                         "<html><head><title>Ignored</title>"
                           "<style>.x{}</style><script>alert(1)</script></head>"
                         "<body><h1>Alpha &amp; Beta</h1>"
                           "<p>Parnas &#x3b3; criteria.</p></body></html>")}}
    (fn [base-url]
      (let [url (str base-url "/article")
            prepared (source/prepare-source (make-context (temp-dir)) url {})]
        (is (= url (:input prepared)))
        (is (= :url (:kind prepared)))
        (is (= url (:uri prepared)))
        (is (= "article" (:display-name prepared)))
        (is (str/includes? (:content prepared) "Alpha & Beta"))
        (is (str/includes? (:content prepared) "Parnas γ criteria."))
        (is (not (str/includes? (:content prepared) "alert")))
        (is (= (source/sha256 (:content prepared))
               (get-in prepared [:fingerprint :sha256])))
        (is (= "\"v1\"" (get-in prepared [:fingerprint :etag])))
        (is (= "Tue, 18 Jun 2024 10:00:00 GMT"
               (get-in prepared [:fingerprint :last-modified])))
        (is (= url (get-in prepared [:fingerprint :final-url])))))))

(deftest prepare-url-non-2xx-test
  (with-http-server
    {"/missing"
     {:status 404, :headers {"Content-Type" "text/plain"}, :body "no"}}
    (fn [base-url]
      (let [ex (try (source/prepare-source (make-context (temp-dir))
                                           (str base-url "/missing")
                                           {})
                    nil
                    (catch Exception e e))]
        (is (= :validation (:type (ex-data ex))))
        (is (str/includes? (ex-message ex) "HTTP status 404"))))))

(deftest prepare-url-fetch-error-test
  (let [ex (try
             (source/prepare-source (make-context (temp-dir)) "http:///bad" {})
             nil
             (catch Exception e e))]
    (is (= :validation (:type (ex-data ex))))
    (is (str/includes? (ex-message ex) "URL fetch failed"))))
