(ns denote-mono.config.core
  "Configuration loading, merging, and validation for denote-mono."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def default-config
  {:default-silo nil,
   :default-llm-wiki-silo nil,
   :silos {},
   :llm {:provider :openrouter,
         :model "moonshotai/kimi-k2.6",
         :api-key-env "OPENROUTER_API_KEY",
         :api-base nil,
         :max-rounds 100,
         :max-tokens 8192,
         :timeout-ms 300000},
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
   :tools {:fd ["fd"],
           :rg ["rg"],
           :fzf ["fzf"],
           :pdftotext ["pdftotext"],
           :editor nil}})

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
  deep-merge it over the defaults. A missing file yields the defaults; a
  file that fails to parse throws ex-info {:type :validation} naming it."
  [{:keys [path env]}]
  (let [path (or path (config-path env))
        f (io/file path)]
    (if (.isFile f)
      (deep-merge default-config
                  (try (edn/read-string (slurp f))
                       (catch Exception e
                         (throw (ex-info (str "Could not parse config file "
                                                path
                                              ": " (ex-message e))
                                         {:type :validation, :path path}
                                         e)))))
      default-config)))

(defn merge-cli
  "Deep-merge CLI-OPTS (already shaped like config) over CONFIG."
  [config cli-opts]
  (deep-merge config cli-opts))

(defn- render-config-text
  "Annotated config.edn body: the ACTIVE map as real EDN followed by every
  default as a commented-out EDN line, so all knobs are discoverable by
  opening the file. edn/read-string of the whole text yields ACTIVE."
  [active]
  (str ";; denote-mono configuration (EDN).\n"
       ";; Copy a commented default below into this map (dropping the"
       " \";;\")\n"
       ";; and edit it to override.\n"
       "{"
       (str/join ",\n " (for [[k v] active] (str (pr-str k) " " (pr-str v))))
       "}\n\n;; Defaults:\n"
       (str/join "\n"
                 (for [[k v] (dissoc default-config
                               :default-silo
                               :default-llm-wiki-silo
                               :silos)]
                   (str ";; " (pr-str k) " " (pr-str v))))
       "\n"))

(defn init-plan
  "Pure plan for first-run initialization (ADR 6: plan-then-apply).
  Takes {:config-path :silo-name :silo-path :llm-wiki-path :env} and
  returns {:config-path :config-text :dirs}. Silo paths are stored in the
  config text exactly as given; :dirs carries them home-expanded so apply
  can create them."
  [{:keys [silo-name silo-path llm-wiki-path env], path :config-path}]
  (let [silo-key (keyword silo-name)
        active
          (cond-> {:default-silo silo-key, :silos {silo-key {:path silo-path}}}
            llm-wiki-path (-> (assoc-in [:silos :wiki]
                                        {:path llm-wiki-path, :llm-wiki true})
                              (assoc :default-llm-wiki-silo :wiki)))]
    {:config-path (or path (config-path env)),
     :config-text (render-config-text active),
     :dirs (mapv #(expand-home % env)
             (cond-> [silo-path] llm-wiki-path (conj llm-wiki-path)))}))

(defn init-apply!
  "Apply an init PLAN: create the silo directories (mkdir -p semantics)
  and write the config file, creating its parent directories. Returns the
  plan."
  [{:keys [config-path config-text dirs], :as plan}]
  (doseq [dir dirs] (.mkdirs (io/file dir)))
  (io/make-parents config-path)
  (spit config-path config-text)
  plan)

(defn validate
  "Validate CONFIG, returning it unchanged or throwing ex-info with
  {:type :validation}."
  [config]
  (let [silos (:silos config)
        default-silo (:default-silo config)
        default-wiki (:default-llm-wiki-silo config)]
    (when-not (map? silos)
      (throw (ex-info ":silos must be a map" {:type :validation})))
    (doseq [[name silo] silos]
      (when-not (string? (:path silo))
        (throw (ex-info (str "Silo " name " needs a string :path")
                        {:type :validation, :silo name})))
      (when (and (contains? silo :llm-wiki) (not (boolean? (:llm-wiki silo))))
        (throw (ex-info (str "Silo " name " :llm-wiki must be a boolean")
                        {:type :validation, :silo name}))))
    (when (and default-silo (not (contains? silos default-silo)))
      (throw (ex-info
               (str ":default-silo " default-silo " is not a configured silo")
               {:type :validation, :silos (keys silos)})))
    (when default-wiki
      (when-not (contains? silos default-wiki)
        (throw (ex-info (str ":default-llm-wiki-silo "
                             default-wiki
                             " is not a configured silo")
                        {:type :validation, :silos (keys silos)})))
      (when-not (true? (get-in silos [default-wiki :llm-wiki]))
        (throw (ex-info (str ":default-llm-wiki-silo " default-wiki
                             " is not an llm-wiki silo; flag it with"
                               " :llm-wiki true")
                        {:type :validation, :silo default-wiki}))))
    config))
