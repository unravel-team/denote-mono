(ns denote-mono.config.core
  "Configuration loading, merging, and validation for denote-mono."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def default-config
  {:default-silo nil,
   :silos {},
   :filename {:components-order [:identifier :signature :title :keywords],
              :sort-keywords? true,
              :identifier-delimiter-always? false,
              :file-type :org},
   :files {:excluded-directories-regex nil,
           :excluded-files-regex nil,
           :follow-symlinks? true,
           :skip-backups? true},
   :front-matter {:present-even-if-empty [:title :keywords :date :identifier],
                  :date-format nil,
                  :rename-mode :sync},
   :links {:rename-id-guard :denote-compatible},
   :sequence {:scheme :numeric},
   :tools {:fd ["fd"], :rg ["rg"], :fzf ["fzf"], :editor nil}})

(defn config-path
  "Path of the user config file: $XDG_CONFIG_HOME/denote-mono/config.edn,
  falling back to ~/.config/denote-mono/config.edn."
  [env]
  (let [base (or (get env "XDG_CONFIG_HOME") (str (get env "HOME") "/.config"))]
    (str base "/denote-mono/config.edn")))

(defn expand-home
  [path env]
  (if (str/starts-with? path "~/") (str (get env "HOME") (subs path 1)) path))

(defn- deep-merge
  [a b]
  (if (and (map? a) (map? b)) (merge-with deep-merge a b) b))

(defn load-config
  "Read the EDN config at :path (default: config-path of :env) and
  deep-merge it over the defaults. A missing file yields the defaults."
  [{:keys [path env]}]
  (let [path (or path (config-path env))
        f (io/file path)]
    (if (.isFile f)
      (deep-merge default-config (edn/read-string (slurp f)))
      default-config)))

(defn merge-cli
  "Deep-merge CLI-OPTS (already shaped like config) over CONFIG."
  [config cli-opts]
  (deep-merge config cli-opts))

(defn validate
  "Validate CONFIG, returning it unchanged or throwing ex-info with
  {:type :validation}."
  [config]
  (let [silos (:silos config)
        default-silo (:default-silo config)]
    (when-not (map? silos)
      (throw (ex-info ":silos must be a map" {:type :validation})))
    (doseq [[name silo] silos]
      (when-not (string? (:path silo))
        (throw (ex-info (str "Silo " name " needs a string :path")
                        {:type :validation, :silo name}))))
    (when (and default-silo (not (contains? silos default-silo)))
      (throw (ex-info
               (str ":default-silo " default-silo " is not a configured silo")
               {:type :validation, :silos (keys silos)})))
    config))
