# 15. Dispatch first-run commands before configuration loading

Date: 2026-07-09

[<- Prev](0014-accept-context-options-before-or-after-the-subcommand.md) | [Next ->](0016-signal-remediation-hints-as-ex-data-and-render-them-in-the-cli.md)

## Status

Accepted

## Context

Every command handler loads and validates the configuration up front
and throws a validation error when it is missing pieces or fails to
parse. That is correct for note operations, but it makes the tool
useless in exactly the state a new user starts from: no config file at
all, or a half-written one. The commands built for that state —
`init`, which creates the config, and `doctor`, which diagnoses it —
would be blocked by the very problem they exist to solve if they went
through the same path.

## Decision

We will dispatch `init` and `doctor` before any configuration loading
in `run`. `init` never reads the config; it only checks whether the
target file exists (refusing to overwrite without `--force`) and
writes a file whose commented-out lines document every default.
`doctor` loads the config itself and converts each failure mode into a
finding rather than an exception: a missing file is a `fail:` line
pointing at `denote init`, a parse or validation error is a `fail:`
line carrying the message, and only when the config loads do the
remaining checks (silos, external tools, editor, LLM key) run. Warns
exit 0; any fail exits 3.

## Consequences

Positive: the tool is usable at zero state — `denote init` works on a
fresh machine and `denote doctor` reports a broken config instead of
crashing with it; doctor doubles as the support script when a
colleague's setup misbehaves.

Negative: `run` now has two classes of command (pre-config and
config-loaded) that must be kept straight when adding commands, and
doctor duplicates a sliver of load logic (the explicit file-existence
check) because `load-config` treats a missing file as defaults rather
than an error.

Neutral: `silo doctor` remains as the silo-only subset for backward
compatibility.
