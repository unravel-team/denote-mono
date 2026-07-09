# denote

A command-line note manager, ported from the Emacs
[Denote](https://protesilaos.com/emacs/denote) package. It keeps every
note in a plain file with a predictable, descriptive name:

```text
20240101T000000==1=2--my-note__topic_project.org
\_____________/  \_/  \_____/  \___________/
   identifier    seq    title     keywords
```

Because all metadata lives in the filename, your notes need no database
and compose with ordinary Unix tools: list, filter, search, create,
rename, organize into Folgezettel hierarchies, and maintain an
LLM-curated wiki — all from the shell.

New here? The **[5-minute quickstart](docs/quickstart.md)** takes you
from install to linked, searchable notes.

## Documentation

- [Quickstart](docs/quickstart.md) — five minutes to linked, searchable notes.
- [Installation](docs/installation.md) — release downloads, building from source, runtime tools, shell completions.
- [Configuration](docs/configuration.md) — `denote init`, the config file, LLM provider settings, silo selection.
- [Usage](docs/usage.md) — every command in depth, with examples.
- [LLM wiki](docs/llm-wiki.md) — an LLM distills your sources into an interlinked wiki.
- [Development](docs/development.md) — make targets, Polylith layout, Emacs Denote compatibility.
- [Architecture decisions](docs/adr/README.md) — the ADR log.

## Installation

```sh
curl -fsSL https://raw.githubusercontent.com/unravel-team/denote-mono/main/install.sh | sh
```

The script installs `denote` to `~/.local/bin` and offers to install the
optional helper tools. Every other method — manual download with checksum
verification, native binary, uberjar — is in [docs/installation.md](docs/installation.md).

## Configuration

```sh
denote init --path ~/Documents/notes --name notes
```

`denote init` writes `~/.config/denote-mono/config.edn`, creates the
notes directory, and documents every other setting as commented-out
defaults. The full config, the LLM provider map, and silo selection are
in [docs/configuration.md](docs/configuration.md).

## Usage

```sh
denote new --title "My first note" --keyword demo --keyword clojure
denote find --keyword clojure --sort modified
denote grep "regex"
denote seq tree
denote llm-wiki ingest ~/notes/paper.pdf
denote llm-wiki query "How does X relate to Y?"
```

Every command is covered in [docs/usage.md](docs/usage.md); the LLM wiki
has [its own guide](docs/llm-wiki.md).

## Command reference

```text
denote [--silo NAME] [--root PATH] [--config PATH] COMMAND [OPTIONS]
denote --version | version    Print the tool version
denote COMMAND --help         Per-command usage and options

init             First-run setup: config file + notes directory
                 (--path --name --llm-wiki-path --print --force)
find [QUERY]     Find notes (--match --keyword --signature --title --id;
                 --sort identifier|title|keywords|signature|modified|random;
                 output: --json --edn --print0)
grep QUERY       Regex search of note contents
backlinks ID|F   Notes linking to the given note
links FILE_OR_ID Outgoing denote: links of a note
new              Create a note (--title --keyword... --signature --id
                 --date --type --subdir --dry-run --reuse-empty)
rename FILE      Rename any file into Denote form (--title --keyword
                 --signature --id --date; empty string removes a component;
                 --front-matter sync|update-existing|add|none;
                 --break-links overrides the backlink guard; --dry-run)
seq next parent|child SEQ|sibling SEQ
seq new parent|child SEQ|sibling SEQ [new options]
seq list|tree [SEQ] [--depth N]
seq convert FILE... --to SCHEME [--dry-run --yes]
seq reparent FILE TARGET-SEQ [--recursive --dry-run --yes]
seq as-parent FILE
llm-wiki ingest SOURCE... Distill file or URL sources into the LLM wiki;
                          re-running continues an interrupted ingest
                          (--fresh starts over)
llm-wiki query QUESTION   Answer from the wiki (--save files it back)
llm-wiki lint             Wiki health checks (--fix --deep)
silo list|path [NAME]|doctor
config show|path          Effective config (--json) / config file location
doctor                    Health-check config, silos, tools, editor (--fix)
completions bash|zsh|fish
help [TOPIC]              Topics: config, silos, links, sequences
```

Global options `--silo`, `--root`, and `--config` are accepted before
or after the command; the value after the command wins.

Exit codes: `0` success, `1` failure, `2` usage, `3` validation,
`4` collision, `5` external tool failure, `6` no match.

Safety defaults: every mutation supports `--dry-run`; batch mutations
print their plan and require `--yes`; destination collisions are detected
before anything moves; identifier renames are guarded against breaking
backlinks; paths resolved through a silo are canonicalized and must stay
inside it.

## Try it out

The [quickstart](docs/quickstart.md) walks through the full flow. For a
self-contained smoke test that touches nothing in your home directory,
point `--config` at a throwaway silo in `/tmp`:

```sh
denote init --config /tmp/denote-demo/config.edn --path /tmp/denote-demo/notes
alias d='denote --config /tmp/denote-demo/config.edn'

d new --title "My first note" --keyword demo --keyword clojure
d find
d find --keyword clojure
d grep "title"
d seq new parent --title "Project X"
d seq new child 1 --title "Design"
d seq tree
d rename /tmp/denote-demo/notes/<file>.org --title "Renamed" --dry-run
d doctor
```

## Development

```text
make test       # unit tests via cognitect test-runner (all bricks)
make check      # tagref + clj-kondo + zprint
make format     # zprint, in place
make build      # check + uberjar
make native     # uberjar + GraalVM native binary
make repl       # REPL with all components on the classpath
make test-poly  # tests via the Polylith tool (per-brick isolation)
```

Workspace layout, component list, and the Emacs Denote compatibility
contract are in [docs/development.md](docs/development.md).
