# 9. Generate shell completions from the live command tables

Date: 2026-06-10

## Status

Accepted

## Context

Distribution polish called for bash, zsh, and fish completions. The obvious
route — three hand-written completion scripts checked into the repository —
rots immediately: every new flag or subcommand must be mirrored in three
dialects nobody tests, and packaging (Homebrew, Nix) prefers binaries that
can emit their own completion files. The CLI already declares its full
surface as data: tools.cli option vectors per command, and a dispatch table
of command names and subcommands.

## Decision

We will generate completions at runtime from the same tables the parser
uses. A `command-spec` table in the base (name, description, option
vector, subcommands) feeds three renderers in
`denote-mono.cli.completions`, and `denote completions bash|zsh|fish`
prints the script. Descriptions are derived from the tools.cli option
descriptions and sanitized per dialect. Tests do not merely grep the
output: they run `bash -n`, `zsh -n`, and (when present) `fish -n` over
the generated scripts so a syntax regression in any dialect fails the
suite.

## Consequences

Positive: completions cannot drift from the real surface — adding an
option updates help text, parsing, and all three shells from one
definition; packagers get the standard "binary emits completions" shape.

Negative: the renderers target the common subset of each shell's
completion system; sophisticated behaviors (completing silo names from
config, file-type-aware suggestions) are not expressed, and the bash
command-detection loop misreads a global option's value as the command in
rare orderings.

Neutral: scripts are generated on demand rather than installed by the
build; users wire them into their shell startup as documented in the
README.
