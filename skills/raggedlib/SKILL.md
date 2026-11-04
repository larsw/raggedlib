---
name: raggedlib
description: >-
  Search and cite the user's own ebook library (Calibre, indexed by raggedlib) through its MCP tools:
  search_library, ask_library, list_books, list_tags, library_status. Use this skill whenever the user asks
  what their books, ebook library, Calibre library or "my library" say about something, wants passages, quotes
  or page references from books or documents they own, wants an answer grounded in (or checked against) their
  books, asks which of their books cover a topic, or asks what is indexed in raggedlib — even if they don't name
  raggedlib or MCP. Also use it to set up or troubleshoot the raggedlib MCP connection in an agent harness.
---

# raggedlib: the user's ebook library over MCP

raggedlib indexes the user's Calibre library on their own machine and serves five **read-only** tools over MCP.
The library is mostly technical books, but can hold anything Calibre does: papers, documentation, legal
documents, books in other languages, scans read by OCR. Use the tools to ground answers in what the user actually
owns, with citations they can look up.

The harness may show the tools with a prefix, e.g. `mcp__raggedlib__search_library`. If no raggedlib tools are
available at all, see "When the tools aren't connected" below.

## The tools

| Tool | Use it for | Cost |
|---|---|---|
| `search_library` | Finding passages: the default for almost everything | ~1 s, no LLM |
| `ask_library` | A cited answer written by the user's local LLM | 20–90 s, cut off at ~150 s |
| `list_books` | Book ids by title/author substring, tag, publisher or status | instant |
| `list_tags` | Calibre tags with book counts, most used first | instant |
| `library_status` | How many books are indexed, whether indexing is running | instant |

### search_library — start here

Arguments: `query` (required), `topK` (1–50, default 8), `bookIds`, `tags`, `publishers`, `rerank` (default
true), `correctSpelling` (default true).

Returns `{query, correctedQuery, passages: [{ref, bookId, title, authors, page, section, score, scoreKind, text}],
note}`: whole passages (~1,500 characters) with a `page` (PDFs) or a `section` (ebooks) to cite.

Prefer it over `ask_library` when you will write the answer yourself: you get the full passages fast, you can
search from several angles, and you keep control of what is claimed and cited.

- **Query**: a natural question or the key terms both work (vector + full-text search, then a cross-encoder).
  Distinctive terms matter more than common ones, and so does vocabulary: phrase the query the way the source
  would phrase the answer. A question about what a court decided finds the court's reasoning when worded like a
  judgment ("retten finner …"), but the indictment when worded like the charges. Full-text syntax works too:
  `"exact phrase"`, `-exclude`, `OR`.
- **Empty result = the books don't cover it.** With reranking on, passages below a relevance cutoff are dropped,
  so `passages: []` is an answer, not an error. Before telling the user, try once or twice with other wording
  (synonyms, the specific term of art, fewer words). `rerank: false` shows the nearest raw matches when you want
  to see what is close.
- **Scores**: `scoreKind: "RERANK"` is a 0–1 relevance comparable across searches. Passages under 0.10 are
  already dropped; ones near 1 address the query directly, while the low tenths are often only tangential. A high
  score means the passage is about the query's topic, not that it answers the user's actual question: a passage
  describing *an* operator scores high for "writing a Kubernetes operator" without helping write one. `"FUSED"`
  means the reranker was unavailable: the order is still useful, but there is no cutoff, so judge relevance
  yourself and don't present weak matches as answers.
- **Spelling correction** replaces words the library lacks with the closest frequent word it has, and
  `correctedQuery`/`note` say so. The library's vocabulary is mostly English, so for a query in another language
  ("holde" became "holds"), or one full of names, identifiers or jargon, pass `correctSpelling: false` from the
  start. Otherwise, when a correction changed a word you meant, repeat with it off.
- **Breadth**: results favour variety, about 3 passages per book, and fill up from the best book only when
  others have nothing relevant; so many hits from one book mean it is the one covering the topic. For a survey
  across many books raise `topK` (up to 50); to read deeper in one book, pass its `bookIds`. If the `note` says
  the output was trimmed, narrow with `bookIds` or `tags`.
- **No page or neighbour lookup.** There is no tool to fetch the next passage or a page range; searches return
  passages, nothing else. To read on, search again with `bookIds` and words you expect in the continuation. If a
  passage stops mid-sentence (PDFs in two columns sometimes come out that way) and a search or two don't bring
  the rest, point the user to the page rather than searching on.

### ask_library — only when the local model's answer is wanted

Arguments: `question` (required, self-contained: no memory between calls), `topK`, `bookIds`, `tags`,
`publishers`, `model`, `correctSpelling`. Returns `answer` with `[n]` citations, `sources` (400-character excerpts; `ref` matches `[n]`),
and the `searchQuery` it retrieved with.

Use it when the user asks for raggedlib's/the local model's answer, or wants the work kept on their machine.
It needs a tool timeout of at least 180 s (Claude Code: `MCP_TOOL_TIMEOUT=180000`); with a shorter one the call
fails although the server is fine. Its sources are excerpts, so to quote or check a claim, follow up with
`search_library`.

### list_books, list_tags, library_status — narrowing and sanity checks

- `list_books(query?, tag?, publisher?, status?, page?, size?)` returns `{total, page, size, books: [{id, title,
  authors, tags, publisher, languages, pubYear, status, chunks, duplicateOf}]}`. `query` is a case-insensitive
  substring of the title or an author; `publisher` is an exact name, as the books show it, and the same names work
  as `publishers` (ANY of them) in `search_library` and `ask_library`. Use the `id`s as `bookIds`. Only `INDEXED` books are searchable; a `DUPLICATE` has the same text as
  `duplicateOf` and is searched through it; `PENDING`/`FAILED`/`SKIPPED` books have no passages yet.
- `list_tags(limit?)` returns `[{name, books}]`: tags to pass as `tags` (a book matches if it has ANY of them).
  Tag names are exact.
- `library_status()` returns book counts by status, `totalPassages` and `indexingRunning`. Call it when results
  are unexpectedly empty, or the user asks what is indexed. If indexing is running, recently added books may not
  be searchable yet.

## A good workflow

1. If the user names a book or author, resolve it with `list_books` first and search with `bookIds`.
2. Search with `search_library`. For a substantial question, run 2–4 searches from different angles (the concept,
   its synonyms, a specific sub-question, the source's own vocabulary) rather than one broad query.
3. When only one book, or none, comes up, a glance at `list_books` (or `list_tags`) tells you whether the library
   holds anything else on the subject, so "only this book covers it" is a finding rather than a guess.
4. Read the passages and answer from them, in the user's language, quoting the source in its own. Say plainly
   when the books don't cover something, or cover only part of it. If you add general knowledge, label it as not
   from the books.
5. Cite every claim taken from a book.

## Citing

Cite with what the passage carries: title (and author when useful), plus `p. N` for PDFs or the section for
ebooks, e.g. *Foundations of Semantic Web Technologies* (Hitzler), p. 212, or *Software Architectures for
Beginners*, § Chapter 5: Tools of the Trade. Never invent a page number: ebook passages have none.

`page` is the page a passage starts on, as the PDF's own page index counted from 1: what a PDF viewer's page box
(and raggedlib's "open PDF at page") goes to. A passage can run onto the next page, so text from late in a
passage may be on N+1; "pp. N–N+1" is safer when you can't tell. In a book with front matter it runs ahead of the printed page number, so when the
user will look pages up in print, say "PDF page N". Quote short excerpts rather than pasting whole passages back.

Some passages come from OCR of scanned PDFs and can contain misread characters; quote them as they are, and
don't treat an odd character as meaningful. Section labels of PDFs are inferred from heading-like lines and are
sometimes wrong (a footnote or a running header); prefer the page number for PDFs, and take a section name from
the passage text when it shows one.

## What the tools can't do

They are read-only. They can't add books, start or stop indexing, re-index, or change metadata; the raggedlib web
UI does that (by default http://localhost:3000, Indexing tab). Books the user owns but hasn't indexed, or whose
indexing was skipped, aren't searchable.

## When the tools aren't connected

If no raggedlib tools are available, the harness hasn't been connected to the server (by default
`http://localhost:8080/mcp`, which needs an API key). `references/setup.md` has the setup for Claude Code, Codex
and other harnesses, and how to check the server.

To query the server without connecting the harness, `scripts/raggedlib_mcp.py` in this skill's directory (Python 3
standard library only) calls the same tools over HTTP. It reads the key from `RAGGEDLIB_MCP_KEY` (and the URL
from `RAGGEDLIB_MCP_URL`). Arguments go as `key=value` (values parsed as JSON when they can be: `topK=5`,
`rerank=false`, `'bookIds=[6,12]'`; quote brackets and spaces, as zsh globs unquoted `[...]`) or as one JSON
object. Rather than the full JSON, scan with `--brief` (one line plus a 200-character preview per passage, one
line per book), then rerun the same search with `--refs 2,5` to print just those passages in full; searches are
deterministic, so the refs match.

```bash
S=<this skill's directory>/scripts/raggedlib_mcp.py
python3 $S --brief search_library query="event sourcing vs CQRS" topK=10
python3 $S --refs 2,5 search_library query="event sourcing vs CQRS" topK=10
python3 $S --brief search_library query="retten er ikke i tvil" 'bookIds=[12]' correctSpelling=false
python3 $S search_library '{"query": "ELK reasoner", "bookIds": [13], "topK": 3}'
python3 $S --brief list_books query=kazakov
python3 $S --list        # tool names and descriptions
```

Never print, echo or log the API key, and don't write it into files the user didn't ask for. If you need the key
and it isn't set, ask the user for it (in the raggedlib repo it is `MCP_API_KEY` in `.env`; load it into the
variable in the same command that uses it, without displaying it).
