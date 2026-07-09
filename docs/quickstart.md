# Quickstart

Five minutes from nothing to a working note system. Every command block
below is copy-pasteable.

## 1. Install

```sh
curl -fsSL https://raw.githubusercontent.com/unravel-team/denote-mono/main/install.sh | sh
denote --version
```

The script installs to `~/.local/bin` and offers to install the
optional helper tools (`fzf`, `rg`, `fd`) at the end. Other install
methods are in the [installation guide](installation.md).

## 2. Set up

```sh
denote init
```

On a terminal it asks for your notes directory and silo name (just
press Enter for the defaults); in scripts pass them as flags:

```sh
denote init --path ~/Documents/notes --name notes
```

This writes `~/.config/denote-mono/config.edn`, creates the notes
directory, and documents every other setting as commented-out defaults
inside the file. Check the result any time:

```sh
denote doctor          # config, silos, tools, editor — all ok?
denote config show     # the effective settings, defaults included
```

## 3. Create notes

```sh
denote new --title "My first note" --keyword demo
denote new --title "Second note" --keyword demo --keyword project
denote new --title "Meeting notes" --keyword project
```

Each command prints the file it created. All metadata lives in the
filename — `20260709T154312--my-first-note__demo.org` — so there is no
database, and any Unix tool can work with your notes.

## 4. Find notes

```sh
denote find                     # everything
denote find first               # free-text match on the filename
denote find --keyword project   # filter by keyword
```

With `fzf` installed and a terminal attached, results open in an
interactive picker: **Enter** opens the selection in `$EDITOR`,
**Ctrl-P** prints it. Piped output is always plain paths.

Search *contents* instead of filenames with:

```sh
denote grep "regex"
```

## 5. Link notes together

Notes link by identifier (the timestamp part of the filename) —
`[[denote:ID][text]]` in org files, `[text](denote:ID)` in markdown.
Open a note and add a link, e.g.:

```text
Link to [[denote:20260709T154312][my first note]].
```

Then follow the graph in both directions:

```sh
denote links FILE_OR_ID         # where does this note point?
denote backlinks FILE_OR_ID     # who points at this note?
```

## 6. Order notes into a tree (optional)

Folgezettel sequences give notes a position in a hierarchy: `1` is a
parent, `1=1` its first child, `1=2` the next sibling.

```sh
denote seq new parent --title "Project X"        # => 1
denote seq new child 1 --title "Design"          # => 1=1
denote seq new sibling 1=1 --title "Planning"    # => 1=2
denote seq tree
```

```text
1  20260709T154431==1--project-x.org
  1=1  20260709T154440==1=1--design.org
  1=2  20260709T154449==1=2--planning.org
```

## Where to go next

- `denote help config|silos|links|sequences` — topic help in the
  terminal; `denote COMMAND --help` for any command.
- Several note directories ("silos")? Add them to the config and pick
  one per command with `--silo NAME` — see `denote help silos`.
- Let an LLM distill your raw sources into an interlinked wiki:
  the [LLM wiki guide](llm-wiki.md).
- The full guides: [usage](usage.md) for every command,
  [configuration](configuration.md) for the config file, and the
  [README](../README.md) for the documentation index and command
  reference.
