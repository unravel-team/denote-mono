# 14. Accept context options before or after the subcommand

Date: 2026-07-09

[<- Prev](0013-run-the-llm-wiki-agent-on-dscloj-react.md) | [Next ->](0015-dispatch-first-run-commands-before-configuration-loading.md)

## Status

Accepted

## Context

`--silo`, `--root`, and `--config` were global options, parsed with
`:in-order true` before command dispatch, so `denote --silo work find`
worked but `denote find --silo work` failed with an unknown-option
error. Users do not think in two grammars: by the time they have typed
the command they know which silo they meant, and trailing flags are the
muscle memory every other CLI trains. At the same time each command
parses its own option table, and those tables are the single source
for per-command `--help` and the generated shell completions (ADR 9),
so any fix had to go through the tables rather than around them.

## Decision

We will define the three context options once and append them to the
option table of every command that resolves a silo context (find, grep,
backlinks, links, new, rename, seq, llm-wiki). Handlers parse their
arguments first and then build the context from the merge of
global-position and subcommand-position values, with the subcommand
position winning when both are given. `global-options` is derived from
the shared `context-options` definition so the two spellings cannot
drift. Commands for which a silo makes no sense (init, config, doctor)
carry only `--config`, with a comment stating why.

## Consequences

Positive: both positions work everywhere and mean the same thing;
per-command `--help` and bash/zsh/fish completions picked the options
up with no completion-generator changes; commands that previously
parsed no options at all (grep, links, backlinks) gained a proper
parse step and now reject typos instead of ignoring them.

Negative: every context-using table carries three extra rows, and the
precedence rule (subcommand wins) is one more fact to remember; the
handlers all had to switch from receiving a pre-built context to
building one after parsing, which made the dispatch table in `run`
more uniform but touched every handler signature.

Neutral: `rename` accepts `--silo` for consistency but still resolves
its silo by file containment; the option only scopes the backlink
guard there.
