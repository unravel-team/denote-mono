# 16. Signal remediation hints as ex-data and render them in the CLI

Date: 2026-07-09

[<- Prev](0015-dispatch-first-run-commands-before-configuration-loading.md) | [Next ->](0017-structure-user-docs-as-a-readme-index-over-self-contained-guides.md)

## Status

Accepted

## Context

Component errors reach the user verbatim: `run` catches `ex-info`,
maps `:type` to an exit code (ADR 8), and prints the message. The
messages were factual but dead-ended — "No silo selected and no
default configured" told a new user what was wrong but not what to
type next. The obvious fix, putting `denote init` or `--silo NAME`
into the component's message, would teach components the CLI's flag
spellings and command names, which belong to the base. A precedent
already existed on the other side: the silo-resolution error carries
`:silos` in its ex-data and the CLI's catch appends "Configured
silos: ..." from it.

## Decision

We will extend that pattern instead of inventing a new one. Components
state facts and attach machine-readable keys to their ex-data — the
silo-selection failure adds `:hint :silo-selection`, config parse
failures add `:path` — and the single catch site in `run` translates
those keys into remediation text that names commands and flags ("Run
'denote init' to create a config, or pass --silo NAME / --root
PATH."). Purely presentational messages with no component fact behind
them (the empty `silo list` notice) live directly in the handler.

## Consequences

Positive: error UX improves without components acquiring CLI
vocabulary; a future server or TUI base can render the same hints in
its own idiom; all remediation strings sit in one catch site where
they are easy to audit.

Negative: the hint vocabulary (`:hint` values, expected ex-data keys)
is a convention between layers, not a checked contract — the same
standing caveat ADR 8 records for `:type`; the catch site grows a
branch per hint kind.

Neutral: config parse errors are wrapped at the component boundary
(`load-config` rethrows reader exceptions as `ex-info` naming the
file) so the raw-exception path stays unreachable for expected
failures.
