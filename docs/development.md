# Development

The make targets, the Polylith workspace layout, and the Emacs Denote
compatibility contract.

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

Architecture decisions are recorded in [adr](adr/README.md).
