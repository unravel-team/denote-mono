---
name: llm-wiki-capture
description: Capture knowledge into denote-mono's `denote llm-wiki`. Use when the user explicitly asks to save, capture, ingest, remember, extend, or structure information in their llm-wiki, including requests like "capture your recent explanation in the llm-wiki", "add this to my llm-wiki", "start a top-level topic/client/project in my llm-wiki", or "capture this under an existing llm-wiki structure note".
---

# llm-wiki Capture

Use `denote llm-wiki ingest` as the write path. Do not edit wiki notes, `index.md`, `log.md`, or `wiki-schema.md` directly unless debugging this repo.

## Core model

- `llm-wiki` distills immutable source files into Denote wiki notes.
- Every created/updated note gets `## Sources` links to those source files.
- Keep capture sources in a durable directory, not `/tmp`, so later `denote llm-wiki lint` does not report `source-missing-on-disk`.
- Express requested structure in the capture source. The llm-wiki agent will list/search/read existing pages, then create/update notes using safe tools that assign filenames, IDs, and sequences.

## Default workflow

1. Identify the material to capture from the current conversation. If "recent explanation" is clear, use the last relevant assistant explanation. If scope is ambiguous, ask for clarification.
2. Preserve facts, code, URLs, dates, names, caveats, and uncertainty. Do not invent new facts to make the capture look complete.
3. Build a source packet with:
   - capture title
   - origin: `pi-coding-agent conversation`
   - user intent
   - desired wiki topology, if any
   - captured material
4. Write the source packet to a persistent source directory, preferably `~/.agents/denote-mono/llm-wiki-captures/`.
5. Run ingestion:
   ```sh
   denote llm-wiki ingest /path/to/capture.md
   ```
   Put global options before `llm-wiki`, e.g. `denote --silo wiki llm-wiki ingest ...` or `denote --config ~/.config/denote/config.edn llm-wiki ingest ...`.
6. Run a cheap mechanical check when time permits:
   ```sh
   denote llm-wiki lint --fix
   ```
7. Report source path, created/updated paths from command output, and any follow-up needed.

## Helper script

Use the bundled helper when available:

```sh
python .agents/skills/llm-wiki-capture/scripts/capture_to_llm_wiki.py \
  --title "Client Nvidia notes" \
  --topic "Client: Nvidia" \
  --intent "Create or update a top-level client structure note, then file these facts under it" <<'EOF'
Captured material here.
EOF
```

Useful options:

- `--silo NAME`, `--root PATH`, `--config PATH`: pass denote global options.
- `--model MODEL`, `--max-rounds N`, `--fresh`: pass llm-wiki ingest options.
- `--source-dir PATH`: choose durable source directory.
- `--no-ingest`: only write the source packet.
- `--lint`: run `denote llm-wiki lint --fix` after successful ingest.
- `--denote-cmd CMD`: use a non-default binary, e.g. `./projects/denote-cli/target/denote`.

## Source packet patterns

### Capture recent explanation

```markdown
# Conversation capture: <title>

- Origin: pi-coding-agent conversation
- Intent: Capture the assistant explanation as durable wiki material.

## Desired wiki topology

- Update an existing relevant page if one exists.
- Otherwise create a focused page under the closest matching topic.

## Captured material

<Faithful transcript/explanation>
```

### Start a top-level client/project topic

```markdown
# Conversation capture: Client Nvidia

- Origin: pi-coding-agent conversation
- Intent: Start or extend a client topic in the llm-wiki.

## Desired wiki topology

- If no suitable page exists, create a new top-level structure note titled `Client: Nvidia`.
- Capture the facts below as child notes or sections under that client structure.
- Prefer updating existing Nvidia/client pages over creating duplicates.
- Cross-link related client, project, meeting, and technical pages with `denote:` links.

## Captured material

<facts to ingest>
```

### File under a known existing note

If user gives a note ID, title, or sequence, include it explicitly:

```markdown
## Desired wiki topology

- Preferred parent: `Client: Nvidia` (`denote:20260617T120000`, sequence `4`).
- File this capture under that structure unless a more specific existing child page is clearly better.
```

## Safety and privacy

- Warn before capturing secrets, credentials, private client data, or sensitive personal data.
- The source file and wiki notes persist on disk; deletion later requires manual cleanup.
- If the API key is missing, tell the user to configure `:llm :api-key-env` or set the default `OPENROUTER_API_KEY`.
- If ingest stops at max rounds, do not retry with `--fresh`; rerun the same command so llm-wiki continues from the logged handoff.
