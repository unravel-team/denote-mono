# Configuration

How to set up the config file — with `denote init` or by hand — plus the
LLM provider settings, how a silo is chosen for each command, and where
to look when something is off.

The fastest way to a working setup is `denote init`: it writes
`$XDG_CONFIG_HOME/denote-mono/config.edn` (defaults to
`~/.config/denote-mono/config.edn`), creates your notes directory, and
documents every other setting as commented-out defaults inside the file.
On a terminal it prompts for the details; in scripts pass them as flags:

```sh
denote init --path ~/Documents/notes --name notes
denote init --path ~/Documents/notes --llm-wiki-path ~/Documents/llm-wiki
denote init --path ~/Documents/notes --print   # preview without writing
denote init --path ~/Documents/notes --force   # overwrite an existing config
```

Or write the file yourself. A minimal config names your note
directories ("silos"):

```clojure
{:default-silo :notes
 :silos {:notes {:path "~/Documents/notes"}
         :work {:path "~/work/notes"}}}
```

To use the [LLM wiki](llm-wiki.md), flag one or more silos with
`:llm-wiki true`, pick a default, and (optionally) tune the `:llm`
provider map:

```clojure
{:default-silo :notes
 :default-llm-wiki-silo :wiki
 :silos {:notes {:path "~/Documents/notes"}
         :work {:path "~/work/notes"}
         :wiki {:path "~/Documents/llm-wiki" :llm-wiki true}}
 ;; optional; these are the defaults
 :llm {:provider :openrouter
       :model "moonshotai/kimi-k2.6"
       :api-key-env "OPENROUTER_API_KEY"
       :api-base nil
       :max-rounds 100
       :timeout-ms 300000}}
```

Everything else has sensible defaults: filename component order, keyword
sorting, default file type, exclusion regexes, front-matter behavior,
sequence scheme, and external tool argv vectors. Run `denote config
show` to see the effective config, defaults included (`denote config
path` prints the file's location).

Silo selection for a command: `--silo NAME`, then `--root PATH`, then the
silo containing the current directory, then `:default-silo`. The
`llm-wiki` commands resolve the same way but only among silos flagged
`:llm-wiki true`, falling back to `:default-llm-wiki-silo`.

If something doesn't work, run `denote doctor`: it checks the config
file, silo directories, external tools, editor, and LLM credentials;
`denote doctor --fix` creates missing silo directories.
