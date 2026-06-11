# Denote CLI UX overhaul (user feedback round 1)

User feedback after real usage. Seven changes, grouped into five commits.

## Decisions (confirmed with user)

- Backlink guard applies only when the renamed file sits inside a configured
  silo; outside, rename freely.
- `rename` denote-ifies any file (Emacs `denote-rename-file` parity).
- fzf keys: **Enter opens** selection in `$EDITOR`, **Ctrl-P prints** paths.
  No TTY (or explicit `--json/--edn/--print0`) → plain output, no selector.

## Commits

1. `feat(cli)!: fold list into find` — `find [QUERY]` gains list's filters
   (`--match --keyword --signature --title --id`), `--sort`, and output
   modes (`--json --edn --print0`). `list` command removed.
2. `feat(cli)!: fzf selection by default` — `find` and `grep` run the
   configured selector when stdout is a TTY and the tool is installed.
   Enter opens, Ctrl-P prints (fzf invoked with `--multi --expect=ctrl-p`;
   non-fzf selectors get no extra flags and default to open). `open`
   command, `--fzf`, and `--open` removed. Harness gains `:tty?` so tests
   inject interactivity.
3. `feat(cli)!: rename works on any file` — no silo resolution required;
   silo determined by path containment; backlink guard only inside a silo.
   `rename-many` removed.
4. `feat(cli)!: seq surface cleanup` — `seq validate` removed;
   `seq list [SEQ] --depth N` / `seq tree [SEQ] --depth N` take a
   positional sequence instead of `--prefix`.
5. `docs(readme): new CLI surface` — strongly recommend fzf; update
   command reference, smoke test, help text references.

Help text, command-spec (completions), and tests updated in each commit.

## Test strategy

- `:tty? true` in the test harness simulates an interactive terminal.
- A `fake-fzf` executable script (name contains "fzf" → gets
  `--expect=ctrl-p`) emulates key reporting: prints the key line then the
  selection.
- Cancellation = selector exits non-zero → exit 6. Missing selector →
  graceful fallback to plain output.
