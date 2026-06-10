(ns denote-mono.silo.core
  "Silo resolution and isolation. A silo is an isolated Denote root; every
  command operates inside exactly one resolved silo unless explicitly told
  otherwise."
  (:require [denote-mono.config.interface :as config]
            [denote-mono.filesystem.interface :as fs]))

(defn all-silos
  "Map of silo name to {:name NAME :path EXPANDED-PATH}."
  [cfg env]
  (into {}
        (map (fn [[name {:keys [path]}]]
               [name {:name name, :path (config/expand-home path env)}]))
        (:silos cfg)))

(defn path-for
  [cfg silo-name env]
  (get-in (all-silos cfg env) [silo-name :path]))

(defn in-silo?
  "True when PATH canonically resolves inside SILO's root.
  [ref:silo_path_containment]"
  [path silo]
  (fs/inside-root? (:path silo) path))

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
