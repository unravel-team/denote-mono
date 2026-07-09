# 17. Structure user docs as a README index over self-contained guides

Date: 2026-07-09

[<- Prev](0016-signal-remediation-hints-as-ex-data-and-render-them-in-the-cli.md)

## Status

Accepted

## Context

The README had grown into a ~470-line monolith serving four different
readers at once: someone deciding whether to try the tool, a new user
setting it up, a daily user looking up a flag, and a contributor
building from source. The onboarding path — the reason colleagues
bounce off a tool — was buried behind installation variants and a full
command reference. A single file also resists per-audience upkeep:
every addition made the wall longer for everyone.

## Decision

We will keep the README as a landing page and move the substance into
self-contained guides under `docs/`: quickstart (a verified 5-minute
path), installation, configuration, usage, llm-wiki, and development.
The README keeps the pitch, a documentation index, one headline
example block per area, and the full command reference as the cheat
sheet. Each fact lives in exactly one place (the make-targets block is
deliberately duplicated in README and development guide); guides link
to each other rather than restating; command examples in the guides
are verified against live runs before they are committed; relative
links are checked mechanically when the docs change.

## Consequences

Positive: a new user has one obvious path (README pitch → quickstart →
topic guide) and each guide can grow without lengthening the others;
the quickstart's every command block is known to work because it was
executed, not transcribed.

Negative: cross-file links are a new maintenance surface (the link
check is scripted but must be run), and the README's command reference
can drift from the actual CLI surface since, unlike `--help` and
completions (ADR 9), it is hand-written prose.

Neutral: the ADR log keeps its own index and is linked from the
documentation index like any other guide.
