# 13. Run the llm-wiki agent on DSCloj ReAct

Date: 2026-06-15

[<- Prev](0012-run-the-llm-wiki-agent-in-process-with-deterministic-tools.md)

## Status

Accepted

Amends [12. Run the llm-wiki agent in-process with deterministic tools](0012-run-the-llm-wiki-agent-in-process-with-deterministic-tools.md)

## Context

ADR 0012 established that the llm-wiki agent runs in-process: the `llm`
component wrapped litellm-clj behind a complete-fn plus a hand-rolled
tool-calling loop, and the `llm-wiki` component supplied deterministic tools
that own filenames, sequences, the `## Sources` section, and silo
containment. That hand-rolled loop is exactly the agent runtime that DSCloj —
our own DSPy port — now provides as a reusable, tested module (`dscloj.react`),
along with chain-of-thought (`dscloj.cot`) and structured prediction
(`dscloj.predict`). Maintaining a second, bespoke loop inside denote-mono
duplicates that machinery and means our agent code is not exercised by the
library's own test suite. We would rather own one agent runtime, in the
library, and have denote-mono consume it.

## Decision

The `llm` component is reimplemented as a thin adapter over DSCloj. Provider
configuration is registered with DSCloj from denote-mono's `:llm` config;
`run-tool-loop` keeps its existing call shape but converts each OpenAI-style
tool schema plus the shared executor into DSCloj tool maps, runs
`dscloj.react/react`, and maps the result back (`:finished` -> `:done`,
`:max-iters` -> `:max-rounds`). The exhausted-run handoff note is now a
`dscloj.cot/chain-of-thought` call over the run's trajectory instead of a
second pass over a message transcript. DSCloj is pinned by `:git/sha` (it
carries litellm-clj transitively); see ADR 0002 in DSCloj for that choice.

What ADR 0012 decided stays decided: the agent is in-process, the tools are
deterministic and defined by `llm-wiki`, and every invariant lives in the tool
executor, not in prompts. DSCloj's ReAct is itself built on plain
prompt-and-parse rather than native provider tool-calling, so the tool
executor — and the safety model that depends on it — is preserved unchanged.

## Consequences

- denote-mono has one agent runtime, maintained and tested in DSCloj, instead
  of a private copy; improvements to the library flow back to the CLI.
- The test seam moves: instead of injecting a complete-fn through the harness,
  tests stub `litellm.router/completion` with DSCloj-format responses (the
  ReAct text protocol). Fixtures are more verbose but exercise the real
  library end to end.
- DSCloj's ReAct extracts its final answer with chain-of-thought, so loop
  results now also carry reasoning, and `run-tool-loop` returns the agent's
  `:trajectory` (used for handoff/resume) rather than a raw message list.
- The native image gains DSCloj's dependency tree (malli, core.async,
  cheshire, slf4j); slf4j must initialize at build time (see the Makefile
  `native` target), alongside the Jackson init from ADR 0010.
- denote-mono now tracks two unreleased Git dependencies (DSCloj, and
  litellm-clj through it); pin SHAs are bumped deliberately, and should return
  to released `:mvn/version` coordinates once DSCloj cuts one.
