# 8. Keep the CLI base thin with data-returning handlers and stable exit codes

Date: 2026-06-10

## Status

Accepted

## Context

In Polylith the base is the delivery mechanism, and it is the easiest place
for domain logic to leak into, where it becomes untestable (tangled with
printing and `System/exit`) and unreusable (a future server or TUI base
could not share it). The CLI is also a scripting surface: pipelines branch
on exit codes, so failure modes need to be distinguishable and stable
across releases. Early review of the first command handlers found exactly
this drift starting — silo health checks done with raw Java interop in the
base, silo resolution rules duplicated between commands, and JSON shaping
of domain records living next to argument parsing.

## Decision

We will hold the base to a narrow contract. Every handler returns
`{:exit CODE :out STRING}`; printing and `System/exit` exist only in
`-main`, and tests drive `run` directly with an injected environment and
working directory. Exit codes form a published table (0 success, 1
failure, 2 usage, 3 validation, 4 collision, 5 external tool, 6 no match);
components signal failures as `ex-info` data with a `:type` key and the
base maps types to codes in one place. Domain knowledge stays in
components: silo health lives in the silo interface, note-record wire
shaping in search, free-text query matching in the search filters. The
base parses arguments, builds a context, calls interfaces, and renders.

## Consequences

Positive: the entire command surface is tested as pure function calls (no
process spawning, no stdout capture); scripts get a stable error contract;
moving logic down into components repeatedly paid off when later commands
(seq tree, backlinks) reused it.

Negative: the discipline needs active enforcement — two simplification
passes specifically demoted logic that had crept into the base, and the
`:type` keyword vocabulary between components and base is a convention,
not a checked contract.

Neutral: handlers compose config loading, silo resolution, and component
calls per invocation; there is no long-lived application state.
