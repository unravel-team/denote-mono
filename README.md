# denote-mono

A Clojure CLI port of the Emacs [Denote](https://protesilaos.com/emacs/denote)
note-taking system: predictable, descriptive file names that can be listed,
filtered, renamed, created, and composed with Unix tools.

Built as a [Polylith](https://polylith.gitbook.io/) workspace. See
`docs/implementation-spec.md` for the full design.

## Quick start

```sh
make test          # run the test suite
make build         # build projects/denote-cli/target/denote-cli-*.jar
java -jar projects/denote-cli/target/denote-cli-*-standalone.jar help
```

Configuration lives at `$XDG_CONFIG_HOME/denote-mono/config.edn`:

```clojure
{:default-silo :notes
 :silos {:notes {:path "~/Documents/notes"}
         :work {:path "~/work/notes"}}}
```

## Commands

```text
denote list | find | open | grep | backlinks | links
denote new | rename | rename-many
denote seq validate|next|new|list|convert|reparent|as-parent
denote silo list|path|doctor
```

Every mutation supports `--dry-run`; batch mutations require `--yes`.

## Workspace layout

- `components/` — `slug`, `filename`, `file_type`, `front_matter`,
  `filesystem`, `process`, `config`, `silo`, `editor`, `search`, `rename`,
  `note`, `sequence`. Each exposes only its `interface` namespace.
- `bases/cli` — thin command-line dispatch.
- `projects/denote-cli` — the runnable artifact.

Compatibility with Emacs Denote is pinned by tests ported from
`denote-test.el` and `denote-sequence-test.el`; intentional CLI divergences
are documented in the spec (section 16).
