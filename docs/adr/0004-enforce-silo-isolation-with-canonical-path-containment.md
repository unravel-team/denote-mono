# 4. Enforce silo isolation with canonical path containment

Date: 2026-06-10

## Status

Accepted

## Context

A silo is an isolated note root; Emacs denote-silo guarantees isolation
simply by rebinding `denote-directory` around interactive commands, because
a human is watching every operation. A non-interactive CLI that renames and
creates files in batch has a sharper problem: a crafted or accidental path
(`--subdir ../escape`, a symlink pointing outside the root, a `..` segment
in a target) could read or mutate files outside the silo the user selected.
String-prefix checks are not enough. On macOS `/var` is itself a symlink
to `/private/var`, so the same file has two textual prefixes, and a path
that does not exist yet (a rename destination) cannot be resolved with a
plain real-path call.

## Decision

We will canonicalize before comparing, everywhere a path crosses a silo
boundary. The filesystem component owns one `canonical` function that
resolves the longest existing ancestor to its real path and re-appends the
remaining segments, so planned-but-not-yet-created paths canonicalize the
same way as existing ones. Containment (`inside-root?`) compares canonical
paths component-wise. Every mutation entry point (rename planning, note
creation, subdirectory handling) and the directory walker enforce this
invariant; symlinked entries are only accepted when their real target stays
inside the root. The rule is marked in source with the
`silo_path_containment` tagref so `make check` fails if the tag's
definition and references drift apart.

## Consequences

Positive: path traversal and symlink escapes are rejected uniformly, with
one implementation to audit; the macOS `/var` aliasing bug class is solved
once, in `canonical`, rather than per call site.

Negative: canonicalization costs real-path system calls, which forced
follow-up optimization in the directory walker (canonicalize each directory
once, check files only when they are symlinks).

Neutral: deliberately escaping a silo is not supported; a future
`--allow-external-symlink` flag was sketched in the spec but intentionally
not implemented.
