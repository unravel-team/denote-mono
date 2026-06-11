# Progress

- [x] 1. fold list into find — 4102bba2
- [x] 2. fzf by default; Enter opens, Ctrl-P prints; drop open/--fzf/--open;
      process/select removed (CLI parses the --expect key line itself) — 5a7f9dd9
- [x] 3. rename any file; backlink guard scoped to silo containment;
      rename-many + rename/plan-batch removed — f09f357a
- [x] 4. seq cleanup: validate dropped, positional SEQ for list/tree — b0ee0b48
- [x] 5. README update — b47f286d

Gates: make format/check/test green at every commit (112 tests / 567
assertions at the end). Live smoke test from source verified find,
rename-outside-silo, seq tree SEQ, completions.

Notes:
- TTY detection consults Console.isTerminal reflectively (JDK 22+);
  verify GraalVM native build folds the constant getMethod call.
- docs/implementation-spec.md §6 command table now drifts from the CLI
  (list/open/rename-many/seq validate removed); not updated — user asked
  for README only.
