# 5. Execute external tools as argv vectors with graceful fallbacks

Date: 2026-06-10

## Status

Accepted

## Context

The CLI deliberately embraces Unix tools: `rg` for content search, `fzf`
for interactive selection, `$EDITOR`/`$VISUAL` for opening notes. These
values come from user configuration and environment variables, and note
titles or queries flow into the same invocations. Anything assembled as a
shell string is one quoting mistake away from injection, and environment
editors routinely contain arguments (`EDITOR="emacsclient -n"`), so naive
splitting or naive non-splitting both produce wrong behavior. At the same
time, none of these tools may be a hard dependency — the CLI must work on
a bare system.

## Decision

We will isolate all subprocess work in a `process` component that only
accepts argv vectors and executes them through `ProcessBuilder`, never
through a shell. Configured tools are vectors in config (`:rg ["rg"]`,
`:fzf ["fzf"]`); environment editor strings are split by a small
shellwords parser that honors quotes, then appended file arguments stay
discrete argv elements. Every adapter degrades: a missing binary is data
(`{:error :missing-binary}`, exit 127), `grep` falls back to a pure
Clojure line scan, `fzf` absence falls back to printing all matches, and a
non-zero selector exit is treated as a cancelled selection rather than an
error. Interactive selectors are fed candidates on stdin and read on
stdout, which works because fzf draws its interface on `/dev/tty`.

## Consequences

Positive: shell injection is structurally impossible from this codebase;
behavior is testable headlessly by substituting deterministic selectors
(`head -1` stands in for fzf in tests); the tool set is user-swappable to
any argv-compatible alternative.

Negative: argv-only execution forecloses shell conveniences (pipelines,
globbing) inside configured tool commands, and users with shell-string
habits must adapt their config to vector form.

Neutral: exit codes from external tools map to the CLI's own exit-code
table (external failure is exit 5), so scripts see a stable interface
regardless of which backend ran.
