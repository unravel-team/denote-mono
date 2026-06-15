# 10. Distribute as a GraalVM native image with build-time Clojure initialization

Date: 2026-06-10

[<- Prev](0009-generate-shell-completions-from-the-live-command-tables.md) | [Next ->](0011-compute-the-version-at-build-time-and-embed-it-as-a-resource.md)

## Status

Accepted

## Context

A note-taking CLI is invoked dozens of times a day from shell pipelines and
completion functions, where startup latency dominates the experience. The
uberjar pays roughly 750 ms of JVM and Clojure bootstrap per invocation —
acceptable for a long-running process, hostile for `denote list | fzf`
loops. The JVM also has to be present on the user's machine. Clojure on
GraalVM native-image is a well-trodden path but has known requirements:
Clojure namespaces must be initialized at image build time, classpath
resources are not embedded unless registered, and a misconfigured build
silently produces a binary that fails at startup trying to load
`clojure/core` from a classpath that no longer exists.

## Decision

We will ship a native binary as the primary distribution, built by
`make native` on top of the existing uberjar. The build adds
`com.github.clj-easy/graal-build-time` to the project dependencies and
passes `--features=clj_easy.graal_build_time.InitClojureClasses` plus
`--no-fallback` to `native-image`; the feature flag is mandatory because
graal-build-time does not self-register through jar metadata. The Makefile
resolves `native-image` from `PATH` or a user-local GraalVM under
`~/.local/share/graalvm`, keeping the toolchain install sudo-free. The
uberjar remains a fully supported artifact for users without GraalVM.

## Consequences

Positive: startup drops from ~750 ms to ~10 ms and the result is one
self-contained 17 MB executable with no JVM dependency; subprocess-based
features (rg, fzf, $EDITOR) work unchanged under native-image.

Negative: builds need GraalVM (~1 minute, ~1.5 GB RAM) and any future use
of runtime reflection, dynamic `requiring-resolve`, or unregistered
resources will fail only at native build or native run time, not under the
JVM test suite.

Neutral: the `--features` flag lives in the Makefile as the single place
native build policy is encoded; the first build without it produced a
binary that compiled cleanly and crashed at startup, which is the failure
mode to remember.
