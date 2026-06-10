(ns denote-mono.cli.core
  "Thin CLI entry point composing denote-mono component interfaces."
  (:gen-class))

(def exit-codes
  {:success 0,
   :failure 1,
   :usage 2,
   :validation 3,
   :collision 4,
   :tool 5,
   :no-match 6})

(def help-text
  "denote - Denote-style note management from the command line

Usage: denote COMMAND [OPTIONS]

Commands:
  help      Show this help text.

Run `denote COMMAND --help` for command-specific options.")

(defn run
  "Dispatch ARGS and return {:exit CODE :out STRING}.
  Pure apart from what command handlers do; printing happens in -main."
  [args]
  (let [[command] args]
    (cond (or (nil? command)
              (= command "help")
              (= command "--help")
              (= command "-h"))
            {:exit (exit-codes :success), :out help-text}
          :else {:exit (exit-codes :usage),
                 :out (str "Unknown command: " command "\n\n" help-text)})))

(defn -main
  [& args]
  (let [{:keys [exit out]} (run args)]
    (if (zero? exit) (println out) (binding [*out* *err*] (println out)))
    (System/exit exit)))
