# 3. Port Emacs Denote semantics exactly, pinned by ported tests

Date: 2026-06-10

[<- Prev](0002-structure-the-codebase-as-a-polylith-monorepo.md) | [Next ->](0004-enforce-silo-isolation-with-canonical-path-containment.md)

## Status

Accepted

## Context

The value of this CLI is interoperability: users keep editing the same
notes from Emacs, so a file created or renamed by denote-mono must be
byte-identical to what Emacs Denote would produce. Denote's behavior has
sharp, sometimes surprising edges. Sluggification trims trailing token
characters differently for titles, component markers can appear in any
order in a filename, sequence letter arithmetic is deliberately nonstandard
(`z` increments to `za`, the value of `za` is 27, `zzzzz` is 130), and the
alphanumeric-delimited scheme preserves a known upstream FIXME. It is
tempting to "fix" these while porting, and equally easy to break them
silently with a plausible-looking reimplementation.

## Decision

We will treat the Emacs test suites as the compatibility contract. Tests
from `denote-test.el` (sluggification, filename retrieval and formatting,
extension handling, front-matter rendering) and `denote-sequence-test.el`
(scheme validation, splitting and joining, increments, conversion, and the
exhaustive next parent/child/sibling fixtures) are ported
assertion-for-assertion into the corresponding component test suites. Where
the source behavior is odd, we reproduce the oddity rather than improving
it. Deliberate divergences are confined to CLI ergonomics and safety
(explicit front-matter modes, collision preflight, confirmation gates) and
are documented in the implementation spec's compatibility section rather
than scattered through the code.

## Consequences

Positive: refactors of the pure components are safe. Both simplification
passes over the parser and the sequence engine were validated entirely by
these fixtures, and the full sequence port passed every exhaustive fixture
on its first run. Files round-trip cleanly between Emacs and the CLI.

Negative: we inherit upstream quirks permanently, including behavior the
upstream author has marked FIXME; improving them now requires coordinated
change with Emacs Denote, not local judgment.

Neutral: a few platform translations were still needed under the same
fixtures (Java strings are UTF-16 so code points must be iterated
explicitly; Java regex syntax differs from Emacs), which is exactly the
class of bug the ported tests caught.
