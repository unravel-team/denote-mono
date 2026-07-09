# LLM wiki

Let an LLM distill your raw sources — files, URLs, PDFs — into an
interlinked wiki of Denote notes: how ingest, query, and lint work, and
how re-runs resume cheaply instead of starting over.

`denote llm-wiki` implements [Karpathy's LLM-wiki
pattern](https://gist.github.com/karpathy/442a6bf555914893e9891c11519de94f):
an LLM incrementally maintains a wiki of interlinked Denote notes
distilled from your raw sources. Sources stay immutable; the wiki
compounds. Every wiki note is a real Denote file with a sequence,
keywords, dense `denote:` cross-links, and a `## Sources` section linking
back to the source files or URLs it came from. Three machine-maintained
files live at the silo root: `index.md` (generated catalog — never edit), `log.md`
(append-only history), and `wiki-schema.md` (the conventions fed to the
model).

To set up a wiki silo and the `:llm` provider map, see
[configuration](configuration.md).

```sh
export ANTHROPIC_API_KEY=...      # or configure :llm in config.edn

# index text files, HTTP/HTTPS URLs, and text-extractable PDFs;
# PDF ingest requires pdftotext (Poppler), and scanned PDFs need OCR first
denote llm-wiki ingest ~/notes/lecture-transcript.txt
denote llm-wiki ingest http://sunnyday.mit.edu/16.355/parnas-criteria.html
denote llm-wiki ingest ~/notes/paper.pdf
denote llm-wiki ingest chapter-1.md chapter-2.md chapter-3.md

# pipe a note query into a batch ingest
denote find --print0 | xargs -0 denote llm-wiki ingest

# ask questions; --save files good answers back as wiki notes
denote llm-wiki query "How does X relate to Y?"
denote llm-wiki query "How does X relate to Y?" --save

# deterministic health checks; --fix repairs the fixable,
# --deep adds an advisory LLM audit
denote llm-wiki lint
denote llm-wiki lint --fix
denote llm-wiki lint --deep
```

Several sources ingest sequentially, each through its own conversation;
all files/URLs are prepared up front, so a typo or failed fetch costs no
API calls. With more than one source the report labels each block with its source, and
the run exits non-zero if any source was left incomplete — re-run the
command and the finished sources are cheap no-ops while the unfinished
ones resume. `ingest` narrates its progress to stderr as the model works
(`round 2: creating note: ...`) whenever stderr is a terminal — also in
the middle of a pipeline such as `xargs`, where stdin is redirected;
stdout stays clean for the final report. `--model` and `--max-rounds`
override the configured values per run.

Ingestion is resumable. Every ingest writes a `status:` line to `log.md`
(`complete`, or `incomplete (max-rounds after N)`); when a run exhausts
its round budget, the model is asked for a one-line *handoff note*
describing the remaining work, which is logged too. Re-running the same
`ingest` command then continues instead of starting over: the prompt
tells the model which pages already exist from that source and what the
handoff note said. Pass `--fresh` to ignore the history and ingest from
scratch.

Ingestion is also idempotent: each entry records a hash of the prepared
source text; local files also record mtime for legacy compatibility. A
source whose latest entry is `complete` and whose hash is unchanged is
skipped without any LLM call. Re-running a whole batch therefore only
costs API calls for the sources that are new, changed, or unfinished.
(Entries written before mtimes were recorded skip when the file provably
predates the entry's day.) `--fresh` overrides the skip too. The model works through a constrained tool loop: it can list, read,
search, create, and update wiki notes, but filenames, identifiers, and
sequence numbers are always assigned by denote itself, and
`index.md`/`log.md` are off-limits to it (ADR 12).
