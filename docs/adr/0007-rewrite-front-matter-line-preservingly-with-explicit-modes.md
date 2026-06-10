# 7. Rewrite front matter line-preservingly with explicit modes

Date: 2026-06-10

## Status

Accepted

## Context

Renaming a note changes metadata that is duplicated in its front matter
(title, keywords, identifier, signature), so rename must rewrite file
contents, not just the file name. This is the most dangerous operation in
the system: the body of a note is user data, and a careless rewrite (parse
the file, re-serialize it) would normalize whitespace, reorder lines, or
destroy content outside the header. Emacs Denote handles the risk with
interactive prompts — add missing lines? rewrite this block? — which a
non-interactive CLI cannot ask. The four supported file types (Org,
Markdown/YAML, Markdown/TOML, plain text) also disagree about syntax,
delimiters, and how values are quoted.

## Decision

We will rewrite front matter as a planned, line-preserving operation with
the policy chosen up front instead of prompted. `front-matter/plan-rewrite`
compares the file's existing lines against freshly formatted component
lines and emits per-line actions (add, remove, modify) with anchors;
`apply-rewrite` executes those actions and leaves every unrelated line
byte-for-byte untouched. The mode is explicit: `sync` reproduces Denote's
add/remove/modify behavior inside an existing header, `update-existing`
only modifies lines already present, `add` prepends a complete header when
none exists, and `none` never touches contents. Templates and value
formatting are ported byte-for-byte from `denote-file-types` so generated
headers match Emacs output exactly.

## Consequences

Positive: note bodies cannot be damaged by rename — only recognized
component lines change, which made the integration tests simple to state
(body text asserted byte-identical); scripts choose their risk level
explicitly via `--front-matter MODE` rather than inheriting hidden
defaults.

Negative: line-preserving editing is more code than parse-and-reserialize,
and the add-anchor logic (where to insert a missing line relative to
existing ones) approximates Denote's ordering rather than reproducing it in
every exotic header layout.

Neutral: `sync` remains the default to match Denote's spirit; the safer
`update-existing` exists for users who treat headers as hand-curated.
