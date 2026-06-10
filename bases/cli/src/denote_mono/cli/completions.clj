(ns denote-mono.cli.completions
  "Shell completion script generation. The scripts are rendered from the
  same command/option tables the CLI dispatches on, so they cannot drift
  from the real surface. Print with `denote completions bash|zsh|fish`."
  (:require [clojure.string :as str]))

(defn- option-flags
  "Long flags (\"--match\" ...) of a tools.cli option-specs vector."
  [option-specs]
  (keep (fn [[_ long-spec]] (when long-spec (first (str/split long-spec #" "))))
        option-specs))

(defn- sanitize
  "Make a description safe inside single quotes and zsh [brackets]."
  [description]
  (-> (or description "")
      (str/replace #"['\"]" "")
      (str/replace "[" "(")
      (str/replace "]" ")")))

(defn- option-entries
  "Seq of [flag description] for a tools.cli option-specs vector."
  [option-specs]
  (keep (fn [[_ long-spec description]]
          (when long-spec
            [(first (str/split long-spec #" "))
             (sanitize (when (string? description) description))]))
        option-specs))

;;;; bash

(defn- bash-case-arm
  [{:keys [name options subcommands]}]
  (let [words (concat subcommands (option-flags options))]
    (str "    " name ") words=\"" (str/join " " words) "\" ;;\n")))

(defn bash-script
  [commands global-options]
  (str "# bash completion for denote. Load with:\n"
       "#   source <(denote completions bash)\n" "_denote() {\n"
       "  local cur cmd words i\n" "  cur=\"${COMP_WORDS[COMP_CWORD]}\"\n"
       "  cmd=\"\"\n" "  for ((i=1; i < COMP_CWORD; i++)); do\n"
       "    case \"${COMP_WORDS[i]}\" in\n" "      -*) ;;\n"
       "      *) cmd=\"${COMP_WORDS[i]}\"; break ;;\n" "    esac\n"
       "  done\n" "  if [[ -z \"$cmd\" ]]; then\n"
       "    COMPREPLY=( $(compgen -W \""
         (str/join " "
                   (concat (map :name commands) (option-flags global-options)))
       "\" -- \"$cur\") )\n" "    return\n"
       "  fi\n" "  words=\"\"\n"
       "  case \"$cmd\" in\n" (apply str (map bash-case-arm commands))
       "  esac\n" "  if [[ -n \"$words\" ]]; then\n"
       "    COMPREPLY=( $(compgen -W \"$words\" -- \"$cur\") )\n" "  fi\n"
       "}\n" "complete -o default -F _denote denote\n"))

;;;; zsh

(defn- zsh-command-entry
  [{:keys [name description]}]
  (str "    '" name ":" (sanitize description) "'\n"))

(defn- zsh-case-arm
  [{:keys [name options subcommands]}]
  (let [argument-specs
          (concat (when (seq subcommands)
                    [(str "'1:subcommand:(" (str/join " " subcommands) ")'")])
                  (for [[flag description] (option-entries options)]
                    (str "'"
                         flag
                         "["
                         (if (str/blank? description) flag description)
                         "]'")))]
    (str "        "
         name
         ") _arguments "
         (str/join " " argument-specs)
         " '*:file:_files' ;;\n")))

(defn zsh-script
  [commands global-options]
  (str
    "#compdef denote\n"
    "# zsh completion for denote. Install by writing this file to a\n"
    "# directory on your $fpath as _denote, e.g.:\n"
    "#   denote completions zsh > ~/.zsh/completions/_denote\n"
    "_denote() {\n"
    "  local curcontext=\"$curcontext\" state line\n"
    "  local -a _denote_commands\n"
    "  _denote_commands=(\n"
    (apply str (map zsh-command-entry commands))
    "  )\n"
    "  _arguments -C \\\n"
    (apply str
      (for [[flag description] (option-entries global-options)]
        (str "    '" flag "[" description "]' \\\n")))
    "    '1:command:->command' '*::arg:->args'\n"
    "  case $state in\n"
    "    command) _describe -t commands 'denote command' _denote_commands ;;\n"
    "    args)\n"
    "      case $words[1] in\n"
    (apply str (map zsh-case-arm commands))
    "        *) _files ;;\n"
    "      esac ;;\n" "  esac\n"
    "}\n" "_denote \"$@\"\n"))

;;;; fish

(defn- fish-command-lines
  [{:keys [name description options subcommands]}]
  (let [seen (str "'__fish_seen_subcommand_from " name "'")]
    (concat [(str "complete -c denote -n __fish_use_subcommand -a "
                  name
                  " -d '"
                  (sanitize description)
                  "'")]
            (when (seq subcommands)
              [(str "complete -c denote -n "
                    seen
                    " -a '"
                    (str/join " " subcommands)
                    "'")])
            (for [[flag description] (option-entries options)]
              (str "complete -c denote -n "
                   seen
                   " -l "
                   (subs flag 2)
                   (when-not (str/blank? description)
                     (str " -d '" description "'")))))))

(defn fish-script
  [commands global-options]
  (str
    "# fish completion for denote. Install with:\n"
    "#   denote completions fish > ~/.config/fish/completions/denote.fish\n"
    (str/join
      "\n"
      (concat
        (for [[flag description] (option-entries global-options)]
          (str "complete -c denote -l " (subs flag 2) " -d '" description "'"))
        (mapcat fish-command-lines commands)))
    "\n"))

(defn script
  "Completion script for SHELL (\"bash\", \"zsh\", or \"fish\"), or nil for
  an unknown shell."
  [shell commands global-options]
  (case shell
    "bash" (bash-script commands global-options)
    "zsh" (zsh-script commands global-options)
    "fish" (fish-script commands global-options)
    nil))
