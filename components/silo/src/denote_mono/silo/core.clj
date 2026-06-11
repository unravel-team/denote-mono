(ns denote-mono.silo.core
  "Silo resolution and isolation. A silo is an isolated Denote root; every
  command operates inside exactly one resolved silo unless explicitly told
  otherwise."
  (:require [denote-mono.config.interface :as config]
            [denote-mono.filesystem.interface :as fs]))

(defn all-silos
  "Map of silo name to {:name NAME :path EXPANDED-PATH :llm-wiki BOOL}."
  [cfg env]
  (into {}
        (map (fn [[name {:keys [path llm-wiki]}]]
               [name
                {:name name,
                 :path (config/expand-home path env),
                 :llm-wiki (boolean llm-wiki)}]))
        (:silos cfg)))

(defn path-for
  [cfg silo-name env]
  (get-in (all-silos cfg env) [silo-name :path]))

(defn in-silo?
  "True when PATH canonically resolves inside SILO's root.
  [ref:silo_path_containment]"
  [path silo]
  (fs/inside-root? (:path silo) path))

(defn doctor
  "Health-check every configured silo. Returns a seq of
  {:name NAME :path PATH :ok? BOOL :reason STRING-OR-NIL}."
  [cfg env]
  (for [[silo-name {:keys [path]}] (sort-by key (all-silos cfg env))]
    (if (fs/directory? path)
      {:name silo-name, :path path, :ok? true, :reason nil}
      {:name silo-name, :path path, :ok? false, :reason "missing directory"})))

(defn- silo-name-key
  [silo-name]
  (if (string? silo-name) (keyword silo-name) silo-name))

(defn resolve-silo
  "Resolve the silo a command operates in. Order: explicit :silo name,
  explicit :root path, current directory containment, configured
  :default-silo. Throws ex-info {:type :validation} listing configured
  silos when nothing matches."
  [cfg {:keys [silo root]} cwd env]
  (let [silos (all-silos cfg env)]
    (cond silo (or (get silos (silo-name-key silo))
                   (throw (ex-info (str "Unknown silo: " silo)
                                   {:type :validation, :silos (keys silos)})))
          root {:name nil, :path (fs/canonical (config/expand-home root env))}
          :else
            (or (when cwd (some (fn [[_ s]] (when (in-silo? cwd s) s)) silos))
                (get silos (:default-silo cfg))
                (throw (ex-info "No silo selected and no default configured"
                                {:type :validation, :silos (keys silos)}))))))

(defn resolve-llm-wiki-silo
  "Resolve the llm-wiki silo a `denote llm-wiki` command operates in.
  Order mirrors resolve-silo but only silos flagged :llm-wiki qualify:
  explicit :silo name (must be flagged), explicit :root path (taken as an
  ad-hoc llm-wiki root), current directory containment among flagged
  silos, configured :default-llm-wiki-silo. Throws ex-info
  {:type :validation} listing the configured llm-wiki silos."
  [cfg {:keys [silo root]} cwd env]
  (let [silos (all-silos cfg env)
        wiki-silos (into {} (filter (fn [[_ s]] (:llm-wiki s))) silos)]
    (cond silo (let [found (get silos (silo-name-key silo))]
                 (cond (nil? found) (throw (ex-info (str "Unknown silo: " silo)
                                                    {:type :validation,
                                                     :silos (keys wiki-silos)}))
                       (not (:llm-wiki found))
                         (throw (ex-info (str
                                           "Silo " silo
                                           " is not an llm-wiki silo; flag it"
                                             " with :llm-wiki true")
                                         {:type :validation,
                                          :silos (keys wiki-silos)}))
                       :else found))
          root {:name nil,
                :path (fs/canonical (config/expand-home root env)),
                :llm-wiki true}
          :else (or (when cwd
                      (some (fn [[_ s]] (when (in-silo? cwd s) s)) wiki-silos))
                    (get wiki-silos (:default-llm-wiki-silo cfg))
                    (throw
                      (ex-info
                        "No llm-wiki silo selected and no default configured"
                        {:type :validation, :silos (keys wiki-silos)}))))))
