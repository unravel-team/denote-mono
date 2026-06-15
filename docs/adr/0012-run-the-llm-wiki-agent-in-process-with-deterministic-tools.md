# 12. Run the llm-wiki agent in-process with deterministic tools

Date: 2026-06-11

[<- Prev](0011-compute-the-version-at-build-time-and-embed-it-as-a-resource.md) | [Next ->](0013-run-the-llm-wiki-agent-on-dscloj-react.md)

## Status

Accepted

Amended by [13. Run the llm-wiki agent on DSCloj ReAct](0013-run-the-llm-wiki-agent-on-dscloj-react.md)

## Context

`denote llm-wiki` implements Karpathy's "LLM wiki" pattern: an LLM
incrementally maintains a silo of interlinked Denote notes distilled from
immutable raw sources (ingest), answers questions from it (query), and the
CLI health-checks it (lint). The LLM has to read and write wiki notes,
which raises two design questions: how the CLI talks to a model, and how
much the model is trusted with.

Shelling out to an external agent CLI would inherit someone else's tool
surface and permissions; letting the model write files directly would put
Denote's filename grammar, sequence assignment, and silo containment at
the mercy of a prompt.

## Decision

LLM access goes through litellm-clj (direct HTTP), isolated in the `llm`
component behind a single seam: a complete-fn `(fn [request] response)`
plus a manual tool-calling loop. The `llm-wiki` component defines the
tools the model may use — `list_notes`, `read_note`, `search_notes`,
`create_note`, `update_note` — and executes them in-process with the same
component functions the rest of the CLI uses. The model never chooses
filenames, identifiers, or sequences: `create_note` runs
`sequence/next-*` and `note/plan-new`, the executor appends the mandatory
`## Sources` section itself, refuses writes to the machine-maintained
files, and enforces silo path containment. Wiki invariants live in the
executor and the deterministic lint, not in prompts. `index.md` is always
regenerated from the notes; the model cannot edit it.

The complete-fn is injectable through the harness, so every test scripts
model turns as data — no HTTP, no API keys in CI.

## Consequences

- A hostile or confused model turn degrades into a tool error fed back to
  the model, not a malformed wiki; the worst it can do is write bad prose.
- Tests pin the whole agentic surface (tool schemas, executor semantics,
  loop mechanics) deterministically.
- Provider choice and keys are config (`:llm`, `:api-key-env`); swapping
  models needs no code.
- The native image now carries hato/cheshire; Jackson must initialize at
  build time and the https URL protocol must be enabled (see the Makefile
  `native` target).
- Model output still needs defensive normalization at the tool boundary
  (e.g. keywords sent as one string instead of an array).
