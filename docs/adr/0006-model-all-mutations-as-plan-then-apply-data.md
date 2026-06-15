# 6. Model all mutations as plan-then-apply data

Date: 2026-06-10

[<- Prev](0005-execute-external-tools-as-argv-vectors-with-graceful-fallbacks.md) | [Next ->](0007-rewrite-front-matter-line-preservingly-with-explicit-modes.md)

## Status

Accepted

## Context

Emacs Denote can afford to mutate eagerly because every rename runs under a
user's eyes with interactive prompts at each step. The CLI runs in scripts
and batch loops where a half-applied operation is silent data damage: a
batch rename can produce duplicate destinations, a destination can collide
with an existing file, two operations can form source/destination cycles,
and a front-matter rewrite can mangle a file the user never previewed.
Interactive prompting per file is not an option in pipelines, but applying
blindly is not acceptable either.

## Decision

We will split every mutation into a pure planning step and an explicit
apply step. `rename/plan-rename` and `rename/plan-batch` return data — 
source, destination, old and new components, a content-change plan, and
warnings — without touching disk. Validation runs over plans: destination
collisions, duplicate destinations across a batch, and
destination-equals-another-source cycles are detected before anything
moves. Application (`apply-plan`, `apply-batch`) executes plans and stops
at the first failure, reporting applied, failed, and pending entries. The
CLI exposes this contract uniformly: `--dry-run` prints the plan for any
mutating command, batch commands print the plan and refuse to run without
`--yes`, and note creation follows the same plan/create split.

## Consequences

Positive: dry-run is free and always faithful, because it prints the exact
data that apply would consume; collision and cycle checks have a single
home; tests assert on plans without filesystem fixtures for the planning
logic.

Negative: there is no rollback journal yet, so a mid-batch failure still
leaves earlier renames applied (the stop-and-report behavior bounds, but
does not undo, partial application); plans that hold filesystem facts can
go stale between plan and apply in concurrent use.

Neutral: front-matter changes ride inside the same plan as a
`:content-change` payload, so file renames and content rewrites are
previewed and applied as one unit.
