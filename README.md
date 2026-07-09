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

This file is the user manual. Development documentation lives at the
end, and architecture decisions in [docs/adr](docs/adr/README.md).

## Installation

### Install script (macOS/Linux)

```sh
curl -fsSL https://raw.githubusercontent.com/unravel-team/denote-mono/main/install.sh | sh
```

The script downloads the latest release asset matching your OS and CPU,
verifies it against `checksums.txt` when available, and installs
`denote` to `~/.local/bin`. Release automation publishes archives named
`denote-mono-vX.Y.Z-{linux-{arm64,x64},macos-arm64}.tar.gz`. There is no
macos-x64 archive — GraalVM stopped shipping macOS Intel builds after
JDK 21 — so Intel Macs build from source instead.

Override the version or destination like this:

```sh
curl -fsSL https://raw.githubusercontent.com/unravel-team/denote-mono/main/install.sh \
  | VERSION=vX.Y.Z BINDIR=/usr/local/bin sh
```

### Homebrew (macOS/Linux) — future

A Homebrew tap is planned but not set up yet — use the install script
above instead. Once the tap exists, installation will be:

```sh
brew install unravel-team/tap/denote-mono
```

The tap formula will point at the same GitHub Release archives and
checksums as the install script.

### Manual GitHub Releases download

Download a native archive from
[GitHub Releases](https://github.com/unravel-team/denote-mono/releases/latest),
then install the binary inside it:

```sh
asset=denote-mono-vX.Y.Z-macos-arm64.tar.gz
tar -xzf "$asset"
install -m 755 "${asset%.tar.gz}/denote" ~/.local/bin/denote
denote --version
```

Release assets include `checksums.txt` for SHA-256 verification. Filter it
to the one archive you downloaded:

```sh
awk -v asset="$asset" '$2 == asset {print}' checksums.txt | shasum -a 256 -c -   # macOS
awk -v asset="$asset" '$2 == asset {print}' checksums.txt | sha256sum -c -       # Linux
```

macOS release binaries are not signed or notarized yet, so Gatekeeper may
show a warning until Apple Developer ID signing is added.

### Build from source

Requires Java 11+, the
[Clojure CLI](https://clojure.org/guides/install_clojure), and GNU make:

```sh
git clone https://github.com/unravel-team/denote-mono.git
cd denote-mono
make test
```

### Build a native binary from source

A self-contained ~20 MB executable with ~10 ms startup. You need GraalVM
with `native-image`:

```sh
brew install --cask graalvm-jdk    # or unpack a GraalVM under ~/.local/share/graalvm

make native
install -m 755 projects/denote-cli/target/denote ~/.local/bin/denote
denote --version
```

`make native` finds `native-image` on your `PATH` or under
`~/.local/share/graalvm`; override with `make native NATIVE_IMAGE=...`.

### Uberjar (alternative)

```sh
make build
alias denote='java -jar /path/to/denote-mono/projects/denote-cli/target/denote-cli-vX.Y.Z-standalone.jar'
```

### Runtime tools

Some features use external tools when available; PDF ingest requires one:

- `fzf` — **strongly recommended** (`brew install fzf`). Makes `find` and
  `grep` interactive on a terminal: fzf narrows the results, **Enter
  opens the selection in your editor, Ctrl-P prints it** for piping.
- `rg` (ripgrep) — accelerates `denote grep`.
- `fd` — accelerates the note listing behind `denote find`, `links`,
  and `backlinks`.
- `pdftotext` (Poppler) — required for PDF sources in `denote llm-wiki`
  ingest.
- `$VISUAL` / `$EDITOR` — the editor used to open notes (falls back to
  `vi`).

The install script checks for these at the end of a run and, when a
package manager is available (`brew`, `apt-get`, `dnf`, or `pacman`),
offers to install the missing ones (`Run brew install fzf? (y/n)`). Set
`NONINTERACTIVE=1` to skip the prompts; it then just prints the install
commands.

### Shell completions

Generated from the same command tables the CLI parses with, so they
never drift:

```sh
source <(denote completions bash)                          # bash, in ~/.bashrc
denote completions zsh > ~/.zsh/completions/_denote        # zsh, on your $fpath
denote completions fish > ~/.config/fish/completions/denote.fish
```

## Configuration

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

To use the LLM wiki, flag one or more silos with `:llm-wiki true`, pick a
default, and (optionally) tune the `:llm` provider map:

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

## Usage

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

### Create notes

```sh
denote new --title "My first note" --keyword demo --keyword clojure
denote new --title "Second note" --type markdown-yaml
denote new --title "Draft" --dry-run        # print the path, create nothing
```

Identifiers are timestamps, unique per silo; front matter is generated to
match the file type (`org`, `markdown-yaml`, `markdown-toml`, `text`).

### Find notes

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

### Search note contents

```sh
denote grep "regex"               # rg-accelerated when rg is installed
```

Prints `path:line:text` matches; on a terminal the same fzf selection
applies (Enter opens the matched files, Ctrl-P prints the lines).

### Links between notes

Notes link to each other by identifier — `[[denote:ID][text]]` in org,
`[text](denote:ID)` in markdown:

```sh
denote links FILE_OR_ID           # outgoing links of a note
denote backlinks FILE_OR_ID       # notes pointing at it
```

### Rename

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

### Folgezettel sequences

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

### LLM wiki

`denote llm-wiki` implements [Karpathy's LLM-wiki
pattern](https://gist.github.com/karpathy/442a6bf555914893e9891c11519de94f):
an LLM incrementally maintains a wiki of interlinked Denote notes
distilled from your raw sources. Sources stay immutable; the wiki
compounds. Every wiki note is a real Denote file with a sequence,
keywords, dense `denote:` cross-links, and a `## Sources` section linking
back to the source files or URLs it came from. Three machine-maintained
files live at the silo root: `index.md` (generated catalog — never edit), `log.md`
(append-only history), and `wiki-schema.md` (the conventions fed to the
model).

```sh
export ANTHROPIC_API_KEY=...      # or configure :llm in config.edn

# index text files, HTTP/HTTPS URLs, and text-extractable PDFs;
# PDF ingest requires pdftotext (Poppler), and scanned PDFs need OCR first
denote llm-wiki ingest ~/notes/lecture-transcript.txt
denote llm-wiki ingest http://sunnyday.mit.edu/16.355/parnas-criteria.html
denote llm-wiki ingest ~/notes/paper.pdf
denote llm-wiki ingest chapter-1.md chapter-2.md chapter-3.md

# pipe a note query into a batch ingest
denote find --print0 | xargs -0 denote llm-wiki ingest

# ask questions; --save files good answers back as wiki notes
denote llm-wiki query "How does X relate to Y?"
denote llm-wiki query "How does X relate to Y?" --save

# deterministic health checks; --fix repairs the fixable,
# --deep adds an advisory LLM audit
denote llm-wiki lint
denote llm-wiki lint --fix
denote llm-wiki lint --deep
```

Several sources ingest sequentially, each through its own conversation;
all files/URLs are prepared up front, so a typo or failed fetch costs no
API calls. With more than one source the report labels each block with its source, and
the run exits non-zero if any source was left incomplete — re-run the
command and the finished sources are cheap no-ops while the unfinished
ones resume. `ingest` narrates its progress to stderr as the model works
(`round 2: creating note: ...`) whenever stderr is a terminal — also in
the middle of a pipeline such as `xargs`, where stdin is redirected;
stdout stays clean for the final report. `--model` and `--max-rounds`
override the configured values per run.

Ingestion is resumable. Every ingest writes a `status:` line to `log.md`
(`complete`, or `incomplete (max-rounds after N)`); when a run exhausts
its round budget, the model is asked for a one-line *handoff note*
describing the remaining work, which is logged too. Re-running the same
`ingest` command then continues instead of starting over: the prompt
tells the model which pages already exist from that source and what the
handoff note said. Pass `--fresh` to ignore the history and ingest from
scratch.

Ingestion is also idempotent: each entry records a hash of the prepared
source text; local files also record mtime for legacy compatibility. A
source whose latest entry is `complete` and whose hash is unchanged is
skipped without any LLM call. Re-running a whole batch therefore only
costs API calls for the sources that are new, changed, or unfinished.
(Entries written before mtimes were recorded skip when the file provably
predates the entry's day.) `--fresh` overrides the skip too. The model works through a constrained tool loop: it can list, read,
search, create, and update wiki notes, but filenames, identifiers, and
sequence numbers are always assigned by denote itself, and
`index.md`/`log.md` are off-limits to it (ADR 12).

### Silos

```sh
denote silo list                  # name and path of every silo
denote silo path [NAME]           # print one path (default silo if omitted)
denote silo doctor                # check that the directories exist
```

## Command reference

```text
denote [--silo NAME] [--root PATH] [--config PATH] COMMAND [OPTIONS]
denote --version | version    Print the tool version
denote COMMAND --help         Per-command usage and options

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
completions bash|zsh|fish
```

Exit codes: `0` success, `1` failure, `2` usage, `3` validation,
`4` collision, `5` external tool failure, `6` no match.

Safety defaults: every mutation supports `--dry-run`; batch mutations
print their plan and require `--yes`; destination collisions are detected
before anything moves; identifier renames are guarded against breaking
backlinks; paths resolved through a silo are canonicalized and must stay
inside it.

## Try it out

A self-contained smoke test (throwaway silo in `/tmp`, no config file
needed):

```sh
mkdir -p /tmp/denote-demo/notes
echo '{:default-silo :demo :silos {:demo {:path "/tmp/denote-demo/notes"}}}' \
  > /tmp/denote-demo/config.edn
alias d='denote --config /tmp/denote-demo/config.edn'

d new --title "My first note" --keyword demo --keyword clojure
d find
d find --keyword clojure
d grep "title"
d seq new parent --title "Project X"
d seq new child 1 --title "Design"
d seq tree
d rename /tmp/denote-demo/notes/<file>.org --title "Renamed" --dry-run
d silo doctor
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

The repository is a [Polylith](https://polylith.gitbook.io/) workspace:

- `components/` — one directory per component, each with `deps.edn`,
  `src/denote_mono/<name>/{interface,core}.clj`, and `test/`. Components:
  `slug`, `filename`, `file_type`, `front_matter`, `filesystem`,
  `process`, `config`, `silo`, `editor`, `search`, `rename`, `note`,
  `sequence`, `llm` (litellm-clj wrapper + tool loop), `llm_wiki`.
  Cross-component calls go through `interface` namespaces only.
- `bases/cli` — argument parsing and rendering; all domain logic lives in
  components.
- `projects/denote-cli` — the deployable artifact (`:uberjar` alias).

Run a single component's tests:

```sh
clojure -M:dev:test -d components/sequence/test
```

Compatibility with Emacs Denote is pinned by tests ported from
`denote-test.el` and `denote-sequence-test.el` (sluggification, filename
grammar, front-matter formats, sequence arithmetic). If you change
behavior in `slug`, `filename`, `file_type`, `front_matter`, or
`sequence`, those fixtures are the contract.
