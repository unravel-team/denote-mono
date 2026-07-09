# Usage

The full guide to every day-to-day command: creating, finding,
searching, linking, renaming, sequencing, and managing silos. The LLM
wiki has [its own guide](llm-wiki.md).

Every command accepts the global options `--silo NAME`, `--root PATH`,
and `--config PATH` — before or after the command name, so
`denote find --silo work` and `denote --silo work find` are equivalent
(the value after the command wins when both are given) — and answers
`--help` with its own usage and option list:

```sh
denote find --help
denote seq --help
```

`denote help config|silos|links|sequences` prints a short explainer on
each topic; `denote help COMMAND` is the same as `COMMAND --help`.

## Create notes

```sh
denote new --title "My first note" --keyword demo --keyword clojure
denote new --title "Second note" --type markdown-yaml
denote new --title "Draft" --dry-run        # print the path, create nothing
```

Identifiers are timestamps, unique per silo; front matter is generated to
match the file type (`org`, `markdown-yaml`, `markdown-toml`, `text`).

## Find notes

```sh
denote find                       # everything in the silo
denote find first                 # free-text match on the filename
denote find --keyword clojure --sort modified
denote find --json | jq .         # JSON-lines for scripting
denote find --print0 | xargs -0 ls -l
denote find --print0 | xargs -0 denote llm-wiki ingest
```

Filters: `--match REGEX --keyword KW --signature SIG --title REGEX
--id ID`; sort keys: `identifier title keywords signature modified
random`. Result paths are absolute by default, which other
commands can consume from any directory. On a terminal with fzf installed
the results open in fzf: Enter opens the selection in `$EDITOR`, Ctrl-P
prints it. Piped or `--json/--edn/--print0` output is always plain.

## Search note contents

```sh
denote grep "regex"               # rg-accelerated when rg is installed
```

Prints `path:line:text` matches; on a terminal the same fzf selection
applies (Enter opens the matched files, Ctrl-P prints the lines).

## Links between notes

Notes link to each other by identifier — `[[denote:ID][text]]` in org,
`[text](denote:ID)` in markdown:

```sh
denote links FILE_OR_ID           # outgoing links of a note
denote backlinks FILE_OR_ID       # notes pointing at it
```

## Rename

`rename` works on **any** file, inside a silo or not — it denote-ifies
the name in place and keeps front matter in sync:

```sh
denote rename note.txt --title "Scratch"            # => <ID>--scratch.txt
denote rename FILE --title "Renamed" --dry-run      # plan first
denote rename FILE --keyword ""                     # empty string removes
denote rename FILE --front-matter none              # filename only
```

Renaming a note's identifier refuses to break existing backlinks unless
you pass `--break-links` (the guard applies only inside a configured
silo).

## Folgezettel sequences

Sequence signatures (`1`, `1=1`, `1=2=1`, ...) order notes into a tree:
children refine their parents.

```sh
denote seq new parent --title "Project X"           # => 1
denote seq new child 1 --title "Design"             # => 1=1
denote seq new sibling 1=1 --title "Planning"       # => 1=2
denote seq next child 1                             # print the next free slot
denote seq list [SEQ] [--depth N]                   # flat listing
denote seq tree [SEQ] [--depth N]                   # indented tree
denote seq reparent FILE TARGET-SEQ [--recursive]   # move a subtree
denote seq convert FILE... --to alphanumeric        # change scheme
denote seq as-parent FILE                           # give a note a new top-level seq
```

Schemes: `numeric` (default), `alphanumeric`, `alphanumeric-delimited`.
Batch mutations print their plan and require `--yes`.

## Silos

```sh
denote silo list                  # name and path of every silo
denote silo path [NAME]           # print one path (default silo if omitted)
denote silo doctor                # check that the directories exist
```

Silos are configured in `config.edn` — see [configuration](configuration.md)
for how they are defined and selected.
