# 11. Compute the version at build time and embed it as a resource

Date: 2026-06-10

## Status

Accepted

## Context

`denote --version` needs to print something truthful in three execution
modes: the uberjar, the native binary, and running from source. The
workspace already had a versioning scheme inherited from its reference
repository: major and minor live in the project's `:uberjar` alias, and
the patch number is the git revision count at build time, so versions
advance automatically with history. But that version existed only in the
jar's file name; nothing at runtime knew it. Hardcoding a version constant
in source would drift from the build, and native-image adds a twist: it
does not embed classpath resources unless they are explicitly registered.

## Decision

We will compute the version once, in `build.clj`, and embed it where every
artifact can read it. Before assembling the uberjar, the build writes the
computed version (for example `v0.2.29`) to `denote_mono/version.txt` in
the class directory, together with a
`META-INF/native-image/.../resource-config.json` that registers the file
for native-image inclusion — native-image discovers that config inside the
jar automatically, so `make native` needs no extra flags. At runtime
`--version` (and a `version` command) reads the resource and prints
`denote dev` when it is absent, which is exactly the running-from-source
case.

## Consequences

Positive: jar and native binary report the same build-stamped version with
one source of truth; releases bump a single number in the project
`deps.edn` (minor was bumped to 0.2 with this change) while patch versions
track commits automatically.

Negative: the version is only meaningful for built artifacts — source runs
say `dev`, and the patch number depends on clone depth (a shallow clone
would under-count revisions).

Neutral: shipping the resource-config inside the jar's `META-INF` is the
general pattern for any future resources the native binary needs.
