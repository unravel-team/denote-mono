# 2. Structure the codebase as a Polylith monorepo

Date: 2026-06-10

## Status

Accepted

## Context

denote-mono ports the Emacs Denote ecosystem (denote, denote-silo,
denote-sequence) to a Clojure CLI. The Emacs source is one large file per
package with free interdependence between concerns: filename parsing,
sluggification, front matter, file walking, renaming, and search all live
together and call each other directly. A straight port would reproduce that
coupling, and the project needed clear seams so that pure
Denote-compatibility logic (slugs, filename grammar, sequences) could be
tested exhaustively without touching the filesystem, while the impure parts
(walking directories, renaming files, spawning editors) stayed replaceable.
The team also had a working reference for this style in another internal
repository (the Tau workspace), which lowered the cost of adopting it.

## Decision

We will organize the repository as a Polylith workspace: one component per
concern under `components/` (slug, filename, file_type, front_matter,
filesystem, process, config, silo, editor, search, rename, note, sequence),
a single thin `bases/cli` for argument handling, and `projects/denote-cli`
as the deployable artifact. Each component exposes only its
`denote-mono.<name>.interface` namespace; implementation lives in `core`
namespaces that no other brick may require. The workspace mirrors the Tau
repository's conventions: `workspace.edn`, root `deps.edn` aliases
(`:dev`, `:test`, `:poly`, `:build`), a `Makefile` with `format`, `check`,
`test`, and `build` gates, and an `:uberjar` alias carrying `:main` and
version fields.

## Consequences

Positive: the pure compatibility layer (slug, filename, sequence,
front_matter) has no filesystem dependencies and is tested with fast unit
tests; cross-component reuse is forced through interfaces, which kept later
refactors local; new commands compose existing interfaces instead of
reaching into internals.

Negative: every component carries boilerplate (its own `deps.edn`,
interface namespace that mostly delegates, test path registration in the
root `deps.edn`), and adding a component touches three or four files.

Neutral: Clojure namespace symbols use hyphens while component directories
use underscores (`file_type` on disk, `denote-mono.file-type` in code);
this is standard Clojure convention but a recurring source of small
mistakes for newcomers.
