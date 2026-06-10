# denote-mono implementation spec

Status: planning spec  
Target: Clojure CLI Polylith monorepo  
Primary source packages studied: `denote`, `denote-silo`, `denote-sequence`  
Reference layout studied: `/Users/nejo/src/unravel-team/tau/tau.root/`

## 1. Purpose

`denote-mono` brings Denote's filename system out of Emacs and into a fast, scriptable CLI. It should preserve Denote's main value: predictable, descriptive file names that can be listed, filtered, renamed, opened, and composed with Unix tools.

The first useful version should support:

1. Denote filename parsing, formatting, and validation.
2. Single-file and batch renaming with dry-run safety.
3. Multiple silos: named or configured note roots isolated from each other.
4. Sequence/Folgezettel signatures: parent, child, sibling, reparent, convert, hierarchy list.
5. Fast list/search/open workflows powered by `fd`, `rg`, `fzf`, and `$EDITOR`, with pure Clojure fallback where practical.

## 2. Source evidence summary

### Denote core

- Filename regex constants live in `denote/denote.root/denote.el:906-919`:
  - date identifier: `YYYYMMDDTHHMMSS`
  - non-date identifier marker: `@@`
  - signature marker: `==`
  - title marker: `--`
  - keywords marker: `__`
- Component extraction functions are at `denote.el:2671-2727`:
  - `denote-retrieve-filename-identifier`
  - `denote-retrieve-filename-keywords`
  - `denote-retrieve-filename-signature`
  - `denote-retrieve-filename-title`
- Filename validity is checked by stripping known components and verifying remaining text is empty or extension-only (`denote-file-has-denoted-filename-p`, `denote.el:1238-1264`).
- Formatting is centralized in `denote-format-file-name` (`denote.el:2992-3047`). It respects configurable component order and omits empty components.
- Sluggification rules are defined at `denote.el:1049-1177` and tested in `tests/denote-test.el:98-147`.
- File-type/front-matter support is defined by `denote-file-types` (`denote.el:2319-2470`) with Org, Markdown/YAML, Markdown/TOML, and text defaults.
- Rename engine is `denote--rename-file` (`denote.el:4353-4422`) behind `denote-rename-file` and batch Dired commands (`denote.el:4461-4873`).
- Directory listing/search primitives are `denote-directory-files`, `denote-sort-files`, `denote-grep`, query filters, and backlinks (`denote.el:1348-1390`, `1762-1816`, `5882-6244`).

### Denote silo

- `denote-silo` is intentionally thin (`denote-silo/denote-silo.root/denote-silo.el:40-149`).
- It stores `denote-silo-directories`, prompts for one, validates membership, then delegates to Denote with `denote-directory` temporarily rebound.
- CLI equivalent: treat silo selection as config-driven root resolution. Once root resolved, all core commands operate on that root.

### Denote sequence

- Supported schemes are `numeric`, `alphanumeric`, and `alphanumeric-delimited` (`denote-sequence.el:66-104`).
- Sequence is stored in Denote signature component (`==...`). `denote-sequence-file-p` checks if signature is valid sequence (`denote-sequence.el:299-304`).
- Validation/splitting/joining/conversion are pure string operations (`denote-sequence.el:115-435`).
- New parent/child/sibling generation is `denote-sequence-get-new` plus helpers (`denote-sequence.el:785-877`).
- Reparent and convert mutate filenames via Denote rename (`denote-sequence.el:1393-1523`).
- Tests give exact expected sequence behavior across schemes (`denote-sequence-test.el:46-505`).

### Tau Polylith layout reference

Use `tau.root` shape:

- `workspace.edn` declares top namespace, interface namespace, project aliases.
- Root `deps.edn` has `:dev`, `:test`, `:poly`, `:build` aliases.
- Modules live under `components/*`, `bases/*`, `projects/*`, each with own `deps.edn`, `src`, and `test` paths.
- Components expose `...interface` namespaces and hide internals in `...core` namespaces.
- `bases/cli` composes component interfaces.
- `projects/<cli>` produces runnable artifact with `:uberjar` alias.
- Make gates: `make format`, `make check`, `make test`, `make build`.

## 3. Naming system specification

### 3.1 Components

A Denote-style filename has optional components plus extension:

```text
[identifier][signature][title][keywords][extension]
```

Default order:

```text
identifier signature title keywords
```

Configurable order must be supported, matching Denote's `denote-file-name-components-order`. Parser must not assume default order; it must identify marker-delimited components anywhere in basename. Formatter must normalize configured order like Denote's `seq-union`: use requested components first, ignore duplicates, and append any missing standard components so each of `:identifier`, `:signature`, `:title`, `:keywords` appears at most once.

| Component | Marker | Example | Notes |
|---|---:|---|---|
| date identifier | none if leading | `20231128T055311` | `YYYYMMDDTHHMMSS`; no `@@` when leading unless delimiter-always config is true |
| non-date identifier | `@@` | `@@abc123` | marker always required |
| signature | `==` | `==1=2` | used by sequence; any core-valid signature slug |
| title | `--` | `--some-title` | hyphen slug |
| keywords | `__` | `__clojure_notes` | underscore-separated list |
| extension | `.` | `.org`, `.md`, `.org.gpg` | preserve original extension on rename |

Rules:

- At least one non-extension component is required.
- Hidden dotfiles are never valid Denote filenames.
- Date identifier at beginning is parsed without `@@`.
- Date identifier elsewhere must use `@@` and can be in any component order.
- Leading date identifier is formatted without `@@` only when `:identifier-delimiter-always?` is false; when true, format as `@@YYYYMMDDTHHMMSS`.
- Non-date identifier must use `@@`.
- Empty components are omitted from formatted filename.
- Preserve file extension exactly by default, including encryption suffixes `.gpg` and `.age`.
- Filename validity is independent of supported content type: unknown extensions may still be valid Denote names, but front-matter operations only apply to supported text types.

Examples expected from Denote tests:

```text
--some-test__one_two.org
@@0123456--some-test__one_two.org
20231128T055311--some-test__one_two.org
20231128T055311.org
20231128T055311==sig--some-test__one_two.org
__denote_testing@@20240610T194654--this-is-a-test-reordered.org
```

### 3.2 Slug rules

Default slug policy must match Denote, with config hook for alternate policies later.

Title:

- Downcase.
- Remove punctuation including brackets, braces, symbols, quotes, slash/equal signs.
- Convert spaces and underscores to hyphens.
- Collapse repeated hyphens.
- Trim leading/trailing hyphens.

Signature:

- Downcase.
- Remove punctuation, including hyphen and slash, but keep/normalize equals separator behavior.
- Convert spaces and underscores to equals.
- Collapse repeated equals.
- Trim leading/trailing equals.

Keyword:

- Downcase.
- Remove punctuation, whitespace, underscores, equals, and hyphens.
- Multiple keywords join with `_`.
- Optional sorting enabled by default, matching Denote's `denote-sort-keywords`.

Identifier:

- Keep mostly as provided.
- Remove `query-filenames:`, `query-contents:`, square brackets, and parentheses.
- Remove dots.
- Collapse repeated token characters where Denote does so.
- Trim trailing token characters (`=`, `@`, `_`, `-`) after cleanup.

### 3.3 Parser output

Core parser returns structured map:

```clojure
{:path "/notes/20231128T055311==1--title__kw.org"
 :directory "/notes/"
 :basename "20231128T055311==1--title__kw.org"
 :stem "20231128T055311==1--title__kw"
 :extension ".org"
 :extension/base ".org"
 :encryption-suffix nil
 :identifier "20231128T055311"
 :identifier/date? true
 :signature "1"
 :title "title"
 :keywords ["kw"]
 :components-order [:identifier :signature :title :keywords]
 :valid-denote-name? true}
```

Extension examples:

```clojure
(extension "a.org")     ;=> ".org"
(extension "a.org.gpg") ;=> ".org.gpg"
(base-extension "a.org.gpg") ;=> ".org"
(encryption-suffix "a.org.gpg") ;=> ".gpg"
```

## 4. Front matter and file types

### 4.1 Supported types v1

| Type | Extension | Front matter | Default date format | Link retrieval |
|---|---|---|---|---|
| `:org` | `.org` | `#+title`, `#+date`, `#+filetags`, `#+identifier`, optional `#+signature` | Org inactive timestamp, e.g. `[2024-01-01 Mon 12:00]` | `[denote:ID]` |
| `:markdown-yaml` | `.md` | YAML block with `title`, `date`, `tags`, `identifier`, optional `signature` | RFC3339 | `(denote:ID)` |
| `:markdown-toml` | `.md` | TOML block with same keys | RFC3339 | `(denote:ID)` |
| `:text` | `.txt` | plain key-value header | ISO date (`YYYY-MM-DD`) | `[denote:ID]` |
| `:unknown` | any | filename-only rename, no front matter mutation | n/a | n/a |

Date formatting must support a global override equivalent to Denote's `denote-date-format`; otherwise use per-type defaults above. Front-matter component presence must be configurable like Denote's `denote-front-matter-components-present-even-if-empty-value`; default keeps title, keywords, date, and identifier lines even when empty, but not signature.

Markdown detection must inspect content because both YAML and TOML use `.md`. If content does not disambiguate, use configured file type when it is one of matching `.md` types; otherwise use the first matching Markdown type. For rename, destructive rewrites still require the selected `--front-matter` mode below.

File-type registry must include link fields, not only metadata fields: `:link-retrieval-format`, `:link-format`, and `:link-in-context-regexp` equivalents. Backlinks and link search must use those patterns.

### 4.2 Rename front matter policy

CLI rename policy:

- Default: `--front-matter sync` for supported text files with existing front matter; `none` for unsupported/unknown files.
- `--front-matter sync`: Denote-like mode. Within an existing front-matter template, add missing component lines, remove lines for empty components when not configured present-even-if-empty, and modify changed lines. If front matter is missing, prompt before prepending unless `--yes`.
- `--front-matter update-existing`: safer mode. Rewrite only known lines already present; never add/remove front-matter lines.
- `--front-matter add`: prepend complete front matter when missing and type is supported; if present, behave like `sync`.
- `--front-matter none`: never touch contents.
- `--dry-run`: show planned path/content changes.
- `--yes`: skip prompts.

Front matter updates must be line-preserving: only known key lines change/add/remove; unrelated content stays byte-for-byte where possible. For binary or unknown files, never read contents unless user explicitly targets a supported text type.

## 5. Silos

A silo is isolated Denote root. Search, list, IDs, backlinks, sequence generation, and batch rename operate only inside selected silo unless user asks for `--all-silos`.

### 5.1 Config

Config file candidate: `$XDG_CONFIG_HOME/denote-mono/config.edn`.

```clojure
{:default-silo :notes
 :silos {:notes {:path "~/Documents/notes"}
         :work {:path "~/work/notes"}
         :research {:path "~/research"}}
 :filename {:components-order [:identifier :signature :title :keywords]
            :sort-keywords? true
            :identifier-delimiter-always? false
            :file-type :org}
 :files {:excluded-directories-regex nil
         :excluded-files-regex nil
         :follow-symlinks? true
         :skip-backups? true}
 :front-matter {:present-even-if-empty [:title :keywords :date :identifier]
                :date-format nil
                :rename-mode :sync}
 :links {:rename-id-guard :denote-compatible}
 :sequence {:scheme :numeric}
 :tools {:fd ["fd"]
         :rg ["rg"]
         :fzf ["fzf"]
         :editor ["$EDITOR"]}}
```

### 5.2 Silo resolution

Command root resolution order:

1. `--silo NAME`.
2. `--root PATH`.
3. Current directory if it is inside a configured silo.
4. `:default-silo`.
5. Error with list of configured silos.

[tag:silo_path_containment] Resolve configured roots, current directory, source paths, and destination paths to canonical/real paths before search or mutation. Reject any target outside selected silo root. If symlink following is enabled, real target must still be inside selected root unless a future explicit `--allow-external-symlink` flag exists. Apply same containment before batch collision checks.

CLI commands:

```text
denote silo list
denote silo path [NAME]
denote silo doctor
denote --silo work list
denote --silo research rename FILE ...
```

## 6. Sequences

Sequence is encoded in signature component. Therefore sequence commands must call core parser/formatter, not duplicate filename logic.

### 6.1 Schemes

Numeric:

```text
1
1=1
1=1=2
1=40=2=20
```

Alphanumeric:

```text
1
1a
1a2
1a2b
```

- Alternates numeric and alphabetic segments.
- Ambiguous single-level sequence `"1"` uses selected/default scheme for scheme inference, matching Denote.
- `a` = 1, `z` = 26, `za` follows `z`, matching Denote's increment behavior.
- Conversion is deliberately nonstandard base-26. Required fixtures: `1=1=1=1=1=1=1 -> 1a1a1a1` (`:alphanumeric`), `1=1=1=1=1=1=1 -> 1=a1a=1a1` (`:alphanumeric-delimited`), and partial increments `z -> za`, `za -> zb`, `zza -> zzb`.

Alphanumeric-delimited:

```text
1
1=a
1=a1b
1=a1b=2a1
```

- Same logical segments as alphanumeric.
- Insert `=` after first level and after every third later level.
- Must preserve Denote's current acceptance/rejection cases, including known source FIXME behavior.
- Mixed-scheme collections are a known upstream weak spot; generation must filter by selected scheme before computing largest sibling/child, and tests must pin behavior with mixed files.

### 6.2 Pure sequence API

`components/sequence` public API:

```clojure
(valid? sequence)                         ; any supported scheme
(valid-for-scheme? scheme sequence)
(scheme-of sequence default-scheme)
(split sequence)                          ; => ["1" "a" "2"]
(join scheme parts)                       ; => string
(depth sequence)
(increment-part part)
(decrement-part part)
(convert sequence target-scheme)
(next-parent existing-sequences scheme)
(next-child existing-sequences parent scheme)
(next-sibling existing-sequences sibling scheme)
(relatives files sequence relation scheme) ; :parent :all-parents :children :all-children :siblings
```

### 6.3 Sequence command surface

```text
denote seq validate SEQUENCE [--scheme numeric|alphanumeric|alphanumeric-delimited]
denote seq next parent [--silo NAME]
denote seq next child SEQUENCE [--silo NAME]
denote seq next sibling SEQUENCE [--silo NAME]
denote seq new parent --title TITLE [--keywords ...]
denote seq new child SEQUENCE --title TITLE
denote seq new sibling SEQUENCE --title TITLE
denote seq reparent FILE TARGET-SEQUENCE [--recursive]
denote seq as-parent FILE
denote seq convert FILE... --to SCHEME [--dry-run]
denote seq list [--prefix SEQUENCE] [--depth N]
denote seq tree [--prefix SEQUENCE] [--depth N]
denote seq open next-sibling FILE
denote seq open prev-sibling FILE
```

Reparent rules:

- Current file gets next child of target sequence.
- Recursive mode rewrites all descendants by replacing old root prefix with new root prefix.
- Must detect collisions before any rename.
- Must dry-run by default unless `--yes` for recursive mutation.

`as-parent` rules:

- Abort if file already has a valid sequence signature, matching `denote-sequence-rename-as-parent`.
- Otherwise assign next top-level parent sequence and preserve other filename metadata.

Convert rules:

- Convert only files with valid sequence signature; skip files without sequence.
- Do not reparent or duplicate-check semantically, matching Denote's explicit behavior.
- CLI additionally detects filesystem destination collisions before mutation; this is a safety divergence.

## 7. Search/list/open workflows

CLI should embrace external tools while keeping composable stdout.

### 7.1 Listing

```text
denote list [--silo NAME] [--all-silos]
            [--match REGEX] [--keyword KW] [--signature SIG]
            [--title REGEX] [--id ID]
            [--sort identifier|title|keywords|signature|modified|random]
            [--json] [--print0]
```

Default output: relative paths from silo root. `--json` emits parser maps.

Implementation:

- Prefer `fd --type f` if present; do not pass `--hidden` by default because `fd` already hides dotfiles. Apply Denote filters in Clojure after tool output.
- Always ignore dot directories like Denote does.
- Follow configured symlink policy; default should match Denote's recursive walker (`directory-files-recursively` follows symlinks) while preserving [ref:silo_path_containment].
- Include readable regular files only.
- Skip Emacs/OS backup files by default.
- Apply `:excluded-directories-regex` during walk and `:excluded-files-regex` after candidate collection.
- `denote list` should include valid Denote filenames without identifiers; ID-specific lookup and `--id` filter require identifiers.
- Apply Denote parser filters in Clojure.
- Fall back to Java NIO walk if `fd` missing.

### 7.2 Find and open

```text
denote find [QUERY] [--fzf] [--open]
denote open [QUERY]
denote edit FILE_OR_ID
```

Workflow:

1. Build candidate file list from silo(s).
2. If `fzf` available and stdout is TTY, present formatted rows.
3. User selects one or many files.
4. Open with `$EDITOR` (`VISUAL`, then `EDITOR`, then platform fallback).
5. Non-interactive mode prints path(s).

### 7.3 Content search

```text
denote grep QUERY [--silo NAME] [--type text-only] [--open]
denote backlinks FILE_OR_ID
denote links FILE_OR_ID
```

Implementation:

- Use `rg` for contents when present.
- Backlink matching should come from file-type registry link patterns (`:link-in-context-regexp` equivalent), not only the raw date-ID regex.
- Rename ID-change guard defaults to Denote-compatible narrow check for `[denote:ID]` in text files; optional `--guard-links all` can use all registry patterns for stricter safety.
- For unsupported binary files, include filename search but skip content grep.

## 8. New note and rename workflows

### 8.1 New note

```text
denote new
  [--title TITLE]
  [--keyword KW --keyword KW]
  [--signature SIG]
  [--id ID]
  [--date "2024-12-09 10:55:50"]
  [--type org|markdown-yaml|markdown-toml|text]
  [--subdir PATH]
  [--template NAME_OR_PATH]
  [--dry-run]
```

Creation contract:

- If ID omitted, generate date identifier from supplied date or current time using `YYYYMMDDTHHMMSS`.
- Generated ID must be unique in selected silo; on collision, increment seconds until unused, matching Denote's date-ID uniqueness behavior.
- Target directory must be selected silo root or an existing/creatable subdirectory inside it after canonical containment check.
- File type defaults to configured `:file-type`; if nil/unknown, use first registry entry (`:org`).
- New supported text files receive front matter plus optional template content.
- Existing non-empty destination aborts. Existing empty destination may be reused only with explicit `--reuse-empty` or confirmation.
- Templates v1 may be literal strings or file paths; function templates are Emacs-specific and out of scope for CLI v1.
- `seq new ...` is a thin wrapper over this command that pre-fills `--signature` with computed sequence.

### 8.2 Single rename

```text
denote rename FILE
  [--title TITLE]
  [--keyword KW --keyword KW]
  [--signature SIG]
  [--id ID]
  [--date "2024-12-09 10:55:50"]
  [--keep-missing]
  [--front-matter sync|update-existing|add|none]
  [--dry-run]
  [--yes]
```

Defaults:

- If option omitted, keep current component.
- If option provided as empty string, remove that component.
- If ID absent and config says generate on rename, use supplied `--date`, else file modification time, else current time.
- `--date` affects generated identifier and date front matter; if explicit `--id` is supplied, ID determines date-equivalent front matter unless user overrides date formatting policy.
- Preserve extension.
- Refuse if destination exists.
- Refuse ID changes when backlinks exist unless `--break-links` is explicitly passed.
- Omitted CLI options are `keep-current`; explicit empty string removes component.

### 8.3 Batch rename

```text
denote rename-many FILE...
denote rename-many --from-stdin
denote rename-many --add-keyword KW FILE...
denote rename-many --remove-keyword KW FILE...
denote rename-many --replace-keywords KW,KW FILE...
denote rename-many --from-front-matter FILE...
```

`--from-front-matter` rules:

- Use title/keywords/signature/identifier lines from front matter when present.
- Changing front-matter `date` alone does not change filename ID; identifier line controls ID, matching Denote.
- If keywords are out of order and keyword sorting is enabled, plan a content rewrite in `sync` mode.

Batch safety:

- Build full operation plan first.
- Detect duplicate destinations and source/destination cycles.
- Print table of old -> new paths.
- Apply only after confirmation or `--yes`.
- If any rename fails, stop and report completed vs pending. Later version can add rollback journal.

### 8.4 Plan data shape

```clojure
{:source #object[java.nio.file.Path]
 :destination #object[java.nio.file.Path]
 :old {:title "old" :keywords ["a"] ...}
 :new {:title "new" :keywords ["a" "b"] ...}
 :content-change {:mode :front-matter/update
                  :lines [{:key :title :old "#+title: old" :new "#+title: new"}]}
 :warnings []}
```

## 9. Proposed Polylith workspace

### 9.1 Topology

```text
denote-mono/
  workspace.edn
  deps.edn
  build.clj
  Makefile
  .clj-kondo/config.edn
  .zprint.edn
  development/src/user.clj

  components/
    filename/
      deps.edn
      src/denote_mono/filename/{core,interface}.clj
      test/denote_mono/filename/{core,interface}_test.clj
    slug/
    file_type/
    front_matter/
    filesystem/
    silo/
    sequence/
    rename/
    search/
    config/
    process/
    editor/

  bases/
    cli/
      deps.edn
      src/denote_mono/cli/core.clj
      test/denote_mono/cli/core_test.clj

  projects/
    denote-cli/
      deps.edn
      config.edn
      test/denote_mono/denote_cli/project_test.clj
      target/
```

Use Clojure namespace symbols with hyphens and filesystem paths with underscores, per Clojure conventions:

```clojure
(ns denote-mono.file-type.interface)
;; file path: components/file_type/src/denote_mono/file_type/interface.clj
;; local dependency symbol example: denote-mono/file-type {:local/root "components/file_type"}
```

Project `:uberjar` alias must include Tau-style fields: `:main denote-mono.cli.core`, `:major-version`, `:minor-version`, and optional `:java-opts`.

### 9.2 `workspace.edn`

```clojure
{:top-namespace "denote-mono"
 :interface-ns "interface"
 :default-profile-name "default"
 :compact-views #{}
 :vcs {:name "git" :auto-add false}
 :tag-patterns {:stable "stable-*"
                :release "v[0-9]*"}
 :projects {"development" {:alias "dev"}
            "denote-cli" {:alias "cli"}}}
```

### 9.3 Root deps aliases

Mirror Tau:

- `:dev`: all components + bases + `development/src`.
- `:test`: all component/base/project test dirs and Cognitect test runner.
- `:poly`: Polylith CLI.
- `:build`: `tools.build` + `build.clj`.
- optional `:repl`, `:cider`, `:clj-kondo` support.

Project `projects/denote-cli/deps.edn` should declare all component/base local roots, `org.clojure/clojure`, and:

```clojure
:aliases {:uberjar {:main denote-mono.cli.core
                    :major-version 0
                    :minor-version 1}}
```

External dependencies v1:

- `org.clojure/clojure 1.12.x`
- `org.clojure/tools.cli` for CLI option parsing, or Babashka CLI if choosing bb-first later.
- `org.clojure/tools.build`
- `cognitect-labs/test-runner`
- Maybe `cheshire`/`jsonista` for JSON output; EDN can be core.

Avoid shell dependencies as library deps; external tools are invoked through `process` component.

## 10. Component responsibilities

### 10.1 `slug`

Pure string normalization.

Public API:

```clojure
(slug-title s opts)
(slug-signature s opts)
(slug-keyword s opts)
(slug-identifier s opts)
(slug-component component s opts)
(slug-keywords keywords opts)
```

Tests port from `denote-test.el:98-147`.

### 10.2 `filename`

Pure parser/formatter/validator.

Depends on: `slug`.

Public API:

```clojure
(date-identifier? s)
(parse path opts)
(valid-denote-filename? path opts)
(format-filename {:keys [directory identifier signature title keywords extension]} opts)
(extract path component opts)
(extension path)
(base-extension path)
(encryption-suffix path)
```

Acceptance fixtures from `denote-test.el:300-390`.

### 10.3 `file-type`

Detect supported file type from path and content sample.

Depends on: none or `front-matter` if needed.

Public API:

```clojure
(detect path content opts)
(supported-extension? path opts)
(text-file? path opts)
(extension-for type opts)
```

### 10.4 `front-matter`

Format, parse, and rewrite metadata lines.

Depends on: `file-type` maybe only via protocol/schema.

Public API:

```clojure
(format type metadata opts)
(parse type content opts)
(has-front-matter? type content opts)
(plan-rewrite type content old-meta new-meta opts)
(apply-rewrite content rewrite-plan)
```

Design: type registry is data map modeled after `denote-file-types`, not macros.

### 10.5 `filesystem`

Path walking, file reads/writes, atomic-ish rename operations.

Public API:

```clojure
(list-files roots opts)
(read-text path opts)
(write-text path content opts)
(rename-file source dest opts)
(file-mtime path)
(writable? path)
(canonical path)
(inside-root? root path)
(backup-file? path)
```

Should hide Java NIO, symlink, dot-directory, backup-file, exclusion-regex, readable-regular-file, and external `fd` fallback details from higher layers. All mutation entrypoints must enforce [ref:silo_path_containment].

### 10.6 `silo`

Resolve configured roots and enforce isolation.

Depends on: `config`, `filesystem`.

Public API:

```clojure
(load-silos config)
(resolve-silo config cli-opts cwd)
(all-silos config)
(path-for config silo-name)
(in-silo? path silo)
```

### 10.7 `sequence`

Pure sequence operations plus file-derived helpers.

Depends on: `filename` for extracting signature from files.

Public API described in section 6.2.

Tests port from `denote-sequence-test.el`.

### 10.8 `rename`

Build and apply rename plans.

Depends on: `filename`, `front-matter`, `file-type`, `filesystem`, `sequence` only for sequence commands if needed.

Public API:

```clojure
(plan-rename file changes context opts)
(plan-batch files batch-changes context opts)
(validate-plan plan opts)
(apply-plan plan opts)
```

No direct CLI parsing here. Return data, not printed strings.

### 10.9 `search`

List/filter/sort/search candidate files.

Depends on: `filename`, `filesystem`, `process`.

Public API:

```clojure
(list-notes context filters opts)
(sort-notes notes sort-key opts)
(grep context query opts)
(backlinks context id opts)
```

### 10.10 `process`

External tool adapter.

Public API:

```clojure
(available? executable)
(run! argv opts) ; argv vector, never shell string
(fd root opts)
(rg root query opts)
(fzf candidates opts)
```

Security contract:

- Execute external tools with argv vectors through Java process APIs, never by concatenating shell strings.
- Tool config values are vectors, e.g. `["fd"]` or `["nvim" "--"]`.
- Treat non-zero exit and missing binary as data errors with stable exit codes.

### 10.11 `editor`

Open files in editor.

Depends on: `process`.

Public API:

```clojure
(editor-command env config)
(open files opts)
```

Editor command contract:

- Prefer `$VISUAL`, then `$EDITOR`, then config `:tools :editor`, then platform fallback.
- If env var contains spaces, parse with a small shellwords parser or require vector config; never pass through shell.
- Append selected file paths as argv elements after command vector.

### 10.12 `config`

Config load/merge/validation.

Public API:

```clojure
(default-config)
(config-path env)
(load-config opts)
(merge-cli config cli-opts)
(validate config)
```

## 11. CLI base

`bases/cli` composes components. It should remain thin:

- Parse command-line args.
- Load config.
- Resolve context/silo.
- Call component interface functions.
- Render output.
- Exit with stable codes.

Suggested command tree:

```text
denote help

denote list [filters]
denote find [query]
denote open [query-or-id]
denote grep QUERY
denote backlinks FILE_OR_ID

denote rename FILE [metadata opts]
denote rename-many FILE... [batch opts]
denote new [metadata opts]

denote silo list|path|doctor

denote seq validate|next|new|reparent|as-parent|convert|list|tree|open

denote doctor
```

Exit codes:

| Code | Meaning |
|---:|---|
| 0 | success |
| 1 | generic failure |
| 2 | invalid CLI usage |
| 3 | validation failed |
| 4 | destination collision |
| 5 | external tool failed |
| 6 | no match |

## 12. Data contracts

### 12.1 Context

```clojure
{:config config
 :silo {:name :notes :path #object[java.nio.file.Path]}
 :cwd #object[java.nio.file.Path]
 :env {"EDITOR" "nvim"}}
```

### 12.2 Note record

```clojure
{:path path
 :relative-path "subdir/20240101T000000--title.org"
 :silo :notes
 :filename parsed-filename
 :file-type :org
 :mtime instant}
```

### 12.3 CLI rendering

- Default command output optimized for shell pipelines: one path per line.
- `--json`: JSON lines for machine use.
- `--edn`: EDN maps for Clojure users.
- `--print0`: NUL-delimited paths.

## 13. Testing strategy

### 13.1 Unit tests

Port Denote tests first:

- Slug tests from `denote-test.el:98-147`.
- Format filename tests from `denote-test.el:300-319`.
- Extension tests from `denote-test.el:321-333`.
- Parser/retrieve tests from `denote-test.el:353-390`.
- File type tests from `denote-test.el:337-471`.
- Sequence validation/generation/conversion tests from `denote-sequence-test.el:46-505`.

Add CLI-specific edge tests:

- Valid filename without identifier, e.g. `--title__kw.org`, is parseable/listable but not ID-addressable.
- Component-order config with duplicates/missing components normalizes like Denote.
- `:identifier-delimiter-always?` formats leading date IDs with `@@`.
- Excluded directory/file regexes, backup-file skip, readable regular files, and symlink containment.
- Ambiguous sequence `"1"` uses selected scheme.
- `seq as-parent` aborts if file already has sequence.
- Alphanumeric conversion fixtures: `1=1=1=1=1=1=1 -> 1a1a1a1`, `1=1=1=1=1=1=1 -> 1=a1a=1a1`, `z -> za`.

### 13.2 Integration tests

Use temp dirs:

- Create silo config with two roots; verify commands do not cross silos.
- Rename single file with dry-run, then apply.
- Batch rename keyword add/remove/replace.
- Rename from front matter, including rule that identifier—not date line—drives filename ID.
- Sequence create child/sibling from existing files.
- Recursive reparent collision detection with canonical destination paths.
- Search/open non-interactive path output.
- External tool argv handling with spaces in `$EDITOR`/`$VISUAL`.

### 13.3 Golden tests

Keep fixture table of input metadata -> expected filename and filename -> expected parse map. These become long-term compatibility contract with Denote.

### 13.4 Property tests

- `parse(format(meta))` roundtrips for valid generated metadata.
- `sequence/split` + `sequence/join` roundtrips per scheme.
- Batch rename plan has unique destinations or fails before mutation.

## 14. Build and quality gates

Makefile should match Tau names:

```make
format:  # zprint -lfw source files
check:   # clj-kondo + zprint -c + tagref if installed
test:    # clojure -M:poly test
build:   # clojure -T:build uberjar :project denote-cli
```

`build.clj` can be copied conceptually from Tau and adjusted:

- project name `denote-cli`
- main namespace `denote-mono.cli.core`
- output `projects/denote-cli/target/denote-cli-vX.Y.Z-standalone.jar`

## 15. Implementation phases

### Phase 0: Scaffold

- Create Polylith workspace, root deps, Makefile, build.clj.
- Add empty component/base/project structure.
- Add smoke `denote --help` command.

### Phase 1: Pure Denote compatibility

- Implement `slug`, `filename`, `file-type`.
- Port Denote filename and slug tests.
- No filesystem mutation yet.

### Phase 2: Silo + list/find

- Implement `config`, `silo`, `filesystem`, `process`.
- Implement `list`, `find`, `open` non-mutating commands.
- Integrate `fd`/`fzf` when available.

### Phase 3: Rename engine

- Implement `front-matter` and `rename` plan/apply.
- Add single rename, dry-run, JSON plan output.
- Add batch keyword operations.

### Phase 4: Sequence

- Implement pure sequence API and tests.
- Add sequence list/next/new commands.
- Add reparent/convert with dry-run/collision plan.

### Phase 5: Search/backlinks

- Add `grep` with `rg` and fallback.
- Add backlinks/links.
- Add query filters and sort options.

### Phase 6: Distribution polish

- Uberjar, native-image optional later, shell completions, manpage, Homebrew/Nix package.

## 16. Compatibility decisions

### 16.1 Intentional CLI deltas

These are deliberate differences from Emacs Denote, to make non-interactive filesystem use safer:

- Recursive sequence reparent and batch rename require dry-run/confirmation unless `--yes`.
- Sequence conversion preserves Denote's no-semantic-duplicate-check behavior, but CLI still rejects filesystem destination collisions before mutation.
- Front matter has explicit modes (`sync`, `update-existing`, `add`, `none`) instead of only interactive prompts.
- External tools are accelerators; Clojure fallbacks or clear errors keep behavior predictable.
- Tool/editor commands use argv vectors, not shell strings.
- Silo containment uses canonical path checks, stricter than simple string-prefix checks.

### 16.2 Must match Denote

Must match Denote:

- Filename markers and extraction behavior.
- Default component order.
- Slug defaults.
- Supported file type semantics.
- Sequence scheme validation and expected generation behavior.
- Silo isolation model.

### 16.3 May intentionally differ

May intentionally differ:

- CLI should use stdout/stderr and exit codes instead of Emacs buffers/prompts.
- CLI can produce JSON/EDN outputs.
- CLI can use external tools (`fd`, `rg`, `fzf`) as acceleration/adapters.

## 17. Main risks

1. Parser ambiguity with arbitrary component order. Mitigation: port Denote regex tests and add more reordered filename fixtures.
2. Front matter rewrite damaging files. Mitigation: data-only rewrite plan, dry-run, line-preserving implementation, integration fixtures.
3. Batch rename partial failure. Mitigation: collision/cycle preflight, sorted operation plan, optional journal later.
4. Sequence duplicate/collision behavior. Mitigation: preserve Denote conversion semantics but add filesystem collision checks.
5. External tool availability. Mitigation: every external tool adapter has fallback or clear exit code.
6. Namespace/path confusion (`denote-mono` ns vs `denote_mono` path). Mitigation: follow Clojure hyphen-to-underscore file path convention and copy Tau interface/core style.

## 18. First implementation checklist

- [ ] Create workspace scaffold matching section 9.
- [ ] Add `slug` component and tests.
- [ ] Add `filename` component and golden fixtures.
- [ ] Add `sequence` component and port tests.
- [ ] Add `config` + `silo` components.
- [ ] Add `filesystem` list walker.
- [ ] Add `cli` base with `list`, `parse`, `format`, `seq validate` commands.
- [ ] Add rename plan data model before applying mutations.

