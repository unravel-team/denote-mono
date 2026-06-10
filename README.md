# denote-mono

A Clojure CLI port of the Emacs [Denote](https://protesilaos.com/emacs/denote)
note-taking system. It preserves Denote's core idea — predictable, descriptive
file names like `20240101T000000==1--my-note__topic.org` — and makes them
usable from the shell: list, filter, search, create, rename, and organize
notes into Folgezettel hierarchies, all composable with Unix pipelines.

The repository is a [Polylith](https://polylith.gitbook.io/) workspace.
The full design, including compatibility guarantees with Emacs Denote and
intentional CLI divergences, lives in `docs/implementation-spec.md`.

## Prerequisites

Required to build and test:

| Tool | Why | Install (macOS) |
|---|---|---|
| Java 11+ (JDK) | runtime and uberjar build | `brew install openjdk` |
| [Clojure CLI](https://clojure.org/guides/install_clojure) | dependency resolution, tests, build | `brew install clojure/tools/clojure` |
| GNU make | task runner | preinstalled / `brew install make` |

Required only for `make check` (linting and formatting gates):

| Tool | Why |
|---|---|
| [clj-kondo](https://github.com/clj-kondo/clj-kondo) | static analysis |
| [zprint](https://github.com/kkinnear/zprint) | formatting (`~/.zprint.edn` must contain `{:search-config? true}`) |
| [tagref](https://github.com/stepchowfun/tagref) | cross-file invariant tags |

Optional at runtime (the CLI degrades gracefully without them):

- `fzf` — interactive selection for `find --fzf` and `open`
- `rg` (ripgrep) — accelerates `denote grep`
- `$VISUAL` / `$EDITOR` — used by `denote open` (falls back to `vi`)

## Install

```sh
git clone <this-repo>
cd denote-mono

make test    # run the full test suite (116 tests)
make build   # runs checks, then builds the uberjar
```

The build produces `projects/denote-cli/target/denote-cli-vX.Y.Z-standalone.jar`.
Give it a convenient entry point:

```sh
# in your shell profile
alias denote='java -jar /path/to/denote-mono/projects/denote-cli/target/denote-cli-v0.1.26-standalone.jar'
```

Alternatively, run straight from source without building a jar:

```sh
clojure -M:dev -m denote-mono.cli.core help
```

## Configure

Create `$XDG_CONFIG_HOME/denote-mono/config.edn` (defaults to
`~/.config/denote-mono/config.edn`). A minimal config names your note
directories ("silos"):

```clojure
{:default-silo :notes
 :silos {:notes {:path "~/Documents/notes"}
         :work {:path "~/work/notes"}}}
```

Everything else has sensible defaults. The full set of options (filename
component order, keyword sorting, file type, exclusion regexes, front-matter
behavior, sequence scheme, external tool argv vectors) is documented in
spec section 5.1. Any command also accepts `--config PATH`, `--silo NAME`,
and `--root PATH` to override resolution.

Silo selection order: `--silo`, then `--root`, then the silo containing the
current directory, then `:default-silo`.

## Try it out

A self-contained smoke test you can paste into a terminal (uses a throwaway
silo in `/tmp`, no config file needed thanks to `--config`):

```sh
mkdir -p /tmp/denote-demo/notes
echo '{:default-silo :demo :silos {:demo {:path "/tmp/denote-demo/notes"}}}' \
  > /tmp/denote-demo/config.edn
alias d='denote --config /tmp/denote-demo/config.edn'

# create notes
d new --title "My first note" --keyword demo --keyword clojure
d new --title "Second note" --keyword demo --type markdown-yaml

# list and filter
d list
d list --keyword clojure
d list --json | head -1

# search file names and contents
d find first
d grep "title"

# build a Folgezettel hierarchy
d seq new parent --title "Project X"
d seq new child 1 --title "Design"
d seq new child 1=1 --title "API sketch"
d seq tree
# 1  ...==1--project-x.org
#   1=1  ...==1=1--design.org
#     1=1=1  ...==1=1=1--api-sketch.org

# rename safely: dry-run first, front matter stays in sync
d rename /tmp/denote-demo/notes/<file>.org --title "Renamed" --dry-run
d rename /tmp/denote-demo/notes/<file>.org --title "Renamed"

# batch operations require --yes after showing the plan
d rename-many --add-keyword reviewed --yes /tmp/denote-demo/notes/*.org

# links between notes (org: [[denote:ID][text]], md: [text](denote:ID))
d links <FILE_OR_ID>
d backlinks <FILE_OR_ID>

# silo health
d silo list
d silo doctor
```

## Command reference

```text
denote [--silo NAME] [--root PATH] [--config PATH] COMMAND [OPTIONS]

list             List notes (--match --keyword --signature --title --id;
                 --sort identifier|title|keywords|signature|modified|random;
                 output: --json --edn --print0)
find [QUERY]     Substring-filter notes; --open opens, --fzf selects
open [QUERY]     Open matches in $EDITOR (fzf narrows when on a terminal)
grep QUERY       Regex search of note contents (rg when available)
backlinks ID|F   Notes linking to the given note
links FILE_OR_ID Outgoing denote: links of a note
new              Create a note (--title --keyword... --signature --id
                 --date --type --subdir --dry-run --reuse-empty)
rename FILE      Change name components (--title --keyword --signature
                 --id --date; empty string removes a component;
                 --front-matter sync|update-existing|add|none;
                 --break-links overrides the backlink guard; --dry-run)
rename-many F... Batch rename (--add-keyword --remove-keyword
                 --replace-keywords A,B --from-front-matter; --yes to apply)
seq validate SEQ [--scheme numeric|alphanumeric|alphanumeric-delimited]
seq next parent|child SEQ|sibling SEQ
seq new parent|child SEQ|sibling SEQ [new options]
seq list|tree [--prefix SEQ] [--depth N]
seq convert FILE... --to SCHEME [--dry-run --yes]
seq reparent FILE TARGET-SEQ [--recursive --dry-run --yes]
seq as-parent FILE
silo list|path [NAME]|doctor
```

Exit codes: `0` success, `1` failure, `2` usage, `3` validation,
`4` collision, `5` external tool failure, `6` no match.

Safety defaults: every mutation supports `--dry-run`; batch mutations print
their plan and require `--yes`; destination collisions are detected before
anything moves; renaming a note's identifier refuses to break existing
backlinks unless `--break-links` is passed; all paths are canonicalized and
must stay inside the selected silo.

## Development

```text
make test       # unit tests via cognitect test-runner (all bricks)
make check      # tagref + clj-kondo + zprint
make format     # zprint, in place
make build      # check + uberjar
make repl       # REPL with all components on the classpath
make test-poly  # tests via the Polylith tool (per-brick isolation)
```

Run a single component's tests:

```sh
clojure -M:dev:test -d components/sequence/test
```

Workspace layout:

- `components/` — one directory per component, each with `deps.edn`,
  `src/denote_mono/<name>/{interface,core}.clj`, and `test/`. Components:
  `slug`, `filename`, `file_type`, `front_matter`, `filesystem`, `process`,
  `config`, `silo`, `editor`, `search`, `rename`, `note`, `sequence`.
  Cross-component calls go through `interface` namespaces only.
- `bases/cli` — argument parsing and rendering; all domain logic lives in
  components.
- `projects/denote-cli` — the deployable artifact (`:uberjar` alias).

Compatibility with Emacs Denote is pinned by tests ported from
`denote-test.el` and `denote-sequence-test.el` (sluggification, filename
grammar, front-matter formats, sequence arithmetic). If you change behavior
in `slug`, `filename`, `file_type`, `front_matter`, or `sequence`, those
fixtures are the contract.
