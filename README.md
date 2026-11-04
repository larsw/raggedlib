# raggedlib

Ask questions about your Calibre ebook library. The answers cite the passages they came from: the page for PDFs, the section for ebooks. Everything runs locally on your GPU.

```
 frontend (Bun · React 19 · Blueprint 6)  :3000  ── /api proxy ──▶  backend (Kotlin · Spring Boot 4.1 · Spring AI 2.0 · JDK 25)  :8080
                                                                        │  calibredb / metadata.db (read-only)
                                                                        │  pdftotext · ebook-convert · tesseract (OCR)
                                                                        ├─▶ Postgres 18 + pgvector  :55432   (books, chunks, HNSW + full-text)
                                                                        ├─▶ Ollama (GPU)            :11434   qwen3-embedding:0.6b · qwen3.5:9b
                                                                        └─▶ llama.cpp (GPU)         :8091    bge-reranker-v2-m3 (/v1/rerank)
```

## Run it

```bash
docker compose up -d --build        # first run pulls ~7 GB of models
open http://localhost:3000          # Indexing tab → Start
```

The library is mounted **read-only** from `~/Documents/CalibreLibrary`. Set `CALIBRE_LIBRARY` to use another path, and `CHAT_MODEL` or `EMBED_MODEL` to swap models (see `.env.example`). Changing the embedding model also means changing `vector(1024)` in the migration and re-indexing. Scanned PDFs are OCR'd in English; set `OCR_LANGUAGES` to read others too, e.g. `OCR_LANGUAGES=eng+nor+deu` (Tesseract's language codes), and rebuild the backend, which installs them.

### Multiple libraries

raggedlib can index and search several Calibre libraries at once, as their union by default; a chooser in the navbar
narrows the UI (and search/ask) to one. In docker-compose, add a second library by uncommenting its volume line in
`docker-compose.yml` and setting `CALIBRE_LIBRARY_1` (and optionally `CALIBRE_LIBRARY_1_NAME`) in `.env`; a third
library repeats the pattern at index 2, and so on — the same indexed-environment-variable style as `MCP_API_KEY`
(see below). Developing on the host needs no Docker volumes at all: set
`RAGGEDLIB_LIBRARIES_0_PATH=/path/to/library/one` and `RAGGEDLIB_LIBRARIES_1_PATH=/path/to/library/two` (plus
`_0_NAME` / `_1_NAME`) directly. Each library is synced, ingested and deduplicated independently, but a sync, an
indexing run or a sweep for duplicate text across libraries always covers every configured library; the chooser only
changes what's displayed. A library already indexed under the single-library `CALIBRE_LIBRARY`/`RAGGEDLIB_LIBRARY_PATH`
setting keeps its books when you add more libraries, as long as its path doesn't change.

### Develop on the host

```bash
docker compose up -d postgres ollama models reranker
(cd backend && mvn spring-boot:run)          # :8080
(cd frontend && bun install && bun run dev)  # :3000 with HMR
```

The host backend and the containerized backend can share the same database, because file paths are stored relative to the library root. Stop the `backend` and `frontend` containers first, or they will occupy the ports.

## Use it from agents (MCP)

The backend serves an [MCP](https://modelcontextprotocol.io) endpoint at `http://localhost:8090/mcp` (streamable HTTP, stateless) with five read-only tools:

| Tool | What it does |
|---|---|
| `search_library` | Reranked passage search (about 1 s, no LLM). Takes `query`, and optionally `topK`, `bookIds`, `tags`, `publishers`, `libraryIds`, `rerank` and `correctSpelling`. An empty result means the books don't cover it. |
| `ask_library` | A cited answer from the local chat model (20–90 s; cut off at 150 s), with its sources as short excerpts. Takes `question`, and optionally `topK`, `bookIds`, `tags`, `publishers`, `libraryIds`, `model` and `correctSpelling`. |
| `list_books` | Find books by title/author substring, tag, publisher, status or library, to get ids for `bookIds` |
| `list_tags` | Calibre tags by number of books |
| `list_libraries` | The configured libraries, with how many books each has, to get ids for `libraryIds` |
| `library_status` | How many books are indexed and searchable |

Every MCP request needs an API key, sent as `Authorization: Bearer <key>` or `X-API-Key: <key>`. With no key configured, the endpoint refuses everything. The rest of the API (`/api`, used by the web UI) doesn't need a key; like the UI, it's published on localhost only.

```bash
KEY=$(openssl rand -hex 32)
touch .env && sed -i '/^MCP_API_KEY=/d' .env && echo "MCP_API_KEY=$KEY" >> .env   # docker compose reads .env
docker compose up -d --build backend
claude mcp add --transport http raggedlib http://localhost:8090/mcp --header "Authorization: Bearer $KEY"
```

`ask_library` runs for up to 150 s, and the stateless transport sends no progress updates meanwhile. Give it a tool timeout of at least 180 s: many clients default to 60 s. In Claude Code, set `MCP_TOOL_TIMEOUT=180000`.

Other harnesses take the same URL and header, for example as JSON: `{"type": "http", "url": "http://localhost:8090/mcp", "headers": {"Authorization": "Bearer <key>"}}`.

**Agent skill.** [`skills/raggedlib/`](skills/raggedlib/SKILL.md) is an [Agent Skill](https://agentskills.io) that tells an agent when to reach for these tools and how to use them well: `search_library` first, retrying with other words before concluding the books don't cover something, narrowing to books and tags, reading scores, and citing pages or sections. It also has setup notes for Claude Code, Codex and other harnesses, and `scripts/raggedlib_mcp.py`, which calls the tools over plain HTTP where no MCP client is connected (`RAGGEDLIB_MCP_KEY` holds the key). Install it by linking or copying the folder into the harness's skills directory (`~/.claude/skills/`, `~/.agents/skills/`, …), or with `npx skills add larsw/raggedlib`.

Keys are configured in `raggedlib.mcp.api-keys`, as a list of `{name, key}`. The name only identifies the client in logs. A key can be stored as `sha256:<hex>` instead of in plain text; `printf %s "$KEY" | sha256sum` gives the hex. Clients still send the plain key. In docker-compose, `MCP_API_KEY` (and `MCP_API_KEY_NAME`) fill entry 0. For more keys, add `RAGGEDLIB_MCP_API_KEYS_1_NAME` / `RAGGEDLIB_MCP_API_KEYS_1_KEY` and so on to the backend's environment.

## How it works

- **Sync** (`POST /api/library/sync`): reads metadata with `calibredb list`. If calibredb refuses, it falls back to reading `metadata.db` read-only. That happens when the Calibre GUI is open, and also inside the container, where the mount is read-only. Books whose calibre `last_modified` changed are queued for re-indexing.
- **Extraction**: one format is chosen per book, in the order EPUB > AZW3 > MOBI > FB2 > PDF > LIT > CHM > DJVU. PDFs go through `pdftotext`, which keeps page numbers; pages without a text layer go through OCR (next point). Everything else goes through `ebook-convert` to markdown, which gives section headings. The text is then cleaned up: headers and footers, TOC and index pages, and hyphenation. It is split into chunks of about 1,500 characters that overlap and respect paragraph boundaries. PDFs carry no heading markup, so their sections come from heading-like lines; table-of-contents pages near the front (many entries, dot leaders, "Chapter N" mentions) are recognised and contribute none, so a book's last TOC entry no longer labels its whole front matter (`Structure.kt`).
- **OCR**: a PDF without a text layer (under 80 letters per page) is read with Tesseract. Each page short of text is rendered at 300 dpi with `pdftoppm` and recognised in the book's Calibre languages plus `OCR_LANGUAGES`, and its text takes the page's place, so cleanup, sections and page citations work as for any PDF. In a PDF that has a text layer, only textless pages that are mostly a raster image (scanned pages bound into it) are OCR'd, and kept only if they hold a page of text, not a library stamp or part title. Pages read with a mean word confidence under 50 are dropped: photos and diagrams, and contents pages, whose dot leaders read as noise. Pages from all books share 8 single-threaded Tesseract runs (half the cores, at most 8; `raggedlib.ocr.concurrency`): a 118-page scan at 600 dpi takes about 45 s, and the Indexing tab shows the page count as it goes. The book details say how many pages came from OCR. PDFs skipped as "no text layer" before OCR was available are queued again at startup (`PdfOcr.kt`, `OcrText.kt`). On the two scanned PDFs here (an English book, a Norwegian court ruling) mean word confidence is 94 and the text needs no correcting, though a decorative chapter numeral can come out as a letter of another configured language ("CHAPTER å").
- **Duplicates**: a Calibre library can hold one book twice, under the same or another title. Before a book is embedded, its passages are looked up (by md5) in the other indexed books; if 90% or more are, word for word, in one at least as long, it is marked DUPLICATE of that book and gets no passages of its own (`Duplicates.kt`). The check also runs the other way after each book is indexed, so it doesn't matter which copy comes first. Searches restricted to the duplicate, or to a tag only it has, use the kept copy; if that copy leaves the library, the duplicate is indexed again. Repeated passages that aren't whole-book copies (an introduction printed twice in a PDF, front matter shared by volumes) are collapsed in search results when they share 60% of their word 5-grams (`NearDuplicates.kt`).
- **Re-indexing** keeps a book's passages searchable until the new ones replace them (a re-index that fails keeps them too), and reuses the embedding of every passage whose title, section and text are unchanged and that came from the current embedding model (`raggedlib.embedding.legacy-model` names the model of passages stored before that was recorded). `POST /api/ingest/reprocess` re-extracts already indexed books this way, e.g. after an extraction fix.
- **Indexing**: runs in the background and resumes after a restart. 3 extraction workers feed one GPU embedder, which sends batches of 32 to Ollama. Each book is written in one transaction. Expect about 25 chunks/s on the RTX 3500 Ada, which is about 5–6 hours for the whole library. Progress, ETA and errors are on the Indexing tab.
- **Retrieval**: pgvector cosine search (HNSW) plus Postgres full-text search, fused with weighted reciprocal rank fusion. Each vector hit counts in proportion to its cosine similarity, from 0 at 0.40 to full at 0.70. That keeps a weak vector side, where the embedding misreads a query like "return oriented programming gadgets" as being about OOP, from outvoting strong keyword matches. Full-text search first requires every term, honoring websearch syntax: quotes, `-term`, `or`. If that finds too little, a fallback looks for passages holding enough of the query's *distinctive* terms, ranked by IDF-weighted term coverage. In a programming library "program" and "return" say little and "gadget" says a lot, so matching only the common words doesn't count (`KeywordFallback.kt`). The best 30 fused passages (or `topK`, if larger) are then re-scored by a cross-encoder, bge-reranker-v2-m3 served by llama.cpp, which reads query and passage together. That improves the order and gives an absolute relevance score: passages below 0.10 are dropped, so a query nothing in the library matches ("who won the 1998 world cup") comes back empty instead of with the least-bad passages. It costs about 1 s per search, can be switched off per request (the sort button next to the scope controls), and if the reranker is down, saturated (all 4 slots busy) or slow, search falls back to the fused order; after a failure it is skipped for 15 s rather than every search waiting out the timeout. Results are capped at 3 passages per book for variety, and can be limited to particular books, tags or publishers.
- **Spelling correction**: before searching, misspelled query words are replaced from the library's own vocabulary (about 190k words that occur in 3+ passages, built with `ts_stat`). A word that occurs nowhere in the library is corrected to the closest word in 20+ passages: one edit away (a missing, extra, wrong or swapped letter), or two for words of 9+ letters. A word the library does contain, rarely, is corrected only to a one-edit neighbour 200× as frequent, which catches the classic typos books contain too ("recieve", 24 passages, vs "receive", 11,000) and leaves real words alone ("minced" stays, though "minded" is far more common). Corrections keep the first letter (or swap the first two), where typos are rare and names differ ("Niri" isn't "Siri"). Words under 5 letters, camelCase or ALL-CAPS words, and anything glued to digits or `_ . - / : @ #` (identifiers, flags, file names, `-exclusions`) are left alone. The corrected query is used for every stage (keyword search, embedding, reranker). The Search tab says "Showing results for …" with a link to search for the words as typed, the Ask tab shows "searched for …" with "ask as typed", and the API returns `correctedQuery` (`correct: false` turns it off). Unknown names close to a frequent word can still be "corrected", which is what those links are for. The vocabulary is rebuilt in the background (about 3 min) at startup when missing or stale, after each indexing run, and after a sync removed books (`Spelling.kt`, `SpellingCorrector.kt`, `SpellingVocabulary.kt`).
- **Chat**: a follow-up question is first rewritten into a standalone query. The answer streams over SSE with `[n]` citations. API contract: [`docs/API.md`](docs/API.md).
- **Reading**: ebooks open in a reader drawer over the current tab, built on [foliate-js](https://github.com/johnfactotum/foliate-js) (EPUB, KEPUB, AZW3, AZW, MOBI, PRC, FB2, FBZ, CBZ). PDFs open in the browser's own viewer at the cited page. Ebook passages store no position in the book, only their text and section heading, so **Read** on a source card finds the passage again: the TOC entry matching its heading narrows where to look, then runs of 8 words from its start and end are matched against the book's text with case, accents, punctuation and whitespace folded, and the span between them is highlighted (`locate.ts`). On the two indexed EPUBs it finds every passage, and the whole span for 98%; the rest (code-heavy passages, ones crossing a file boundary) are highlighted from their first or last words. The book details panel's **Read**, its ebook files and its indexed sections open the reader too; the last position per book is remembered. foliate-js is served unbundled from `node_modules` (`/vendor/foliate-js/`), so only the modules a book needs load, and only when the reader opens.

## Layout

```
backend/    Spring Boot app (dev.raggedlib.{calibre,extract,ingest,search,chat,library,system})
frontend/   Bun server (server.ts: static, /api proxy, /vendor/foliate-js) and React app (src/{api,views,components,hooks,lib})
docs/API.md HTTP contract
skills/     Agent Skill for the MCP tools (skills/raggedlib/SKILL.md)
```

## Troubleshooting

- **Chat is slow, or `ollama ps` shows `100% CPU`.** The Ollama and reranker containers get the GPU through CDI
  (a `driver: cdi` device reservation for `nvidia.com/gpu=all`), so they can't silently lose it after a host
  `systemctl daemon-reload`, which used to show up as "Failed to initialize NVML: Unknown Error". If CDI isn't set up
  on your host, run `sudo nvidia-ctk cdi generate --output=/etc/cdi/nvidia.yaml` once.
- **`compose up` fails with `error gathering device information … "nvidia.com/gpu=all": no such file or directory`.**
  Docker older than 28.2 ships with CDI switched off. Run
  `sudo nvidia-ctk runtime configure --runtime=docker --cdi.enabled && sudo systemctl restart docker`.
- **Reranker unreachable** (yellow status dot, "Reranker UNREACHABLE" in its tooltip). Search still works in the fused
  order. Check `docker compose logs reranker`; on first start it downloads the model (~640 MB) before it turns healthy.
- **Slow generation even on the GPU.** On a laptop, check `powerprofilesctl get`. The `power-saver` profile keeps the
  GPU in low-clock power states.

## Known limitations

- OCR covers PDFs only: a scanned DJVU is still SKIPPED, as `ebook-convert` reads only an existing text layer. OCR runs on the CPU; Tesseract misreads handwriting, unusual fonts and poor scans, and a scanned page's footnotes and columns follow Tesseract's reading order.
- Code blocks in PDFs come out as single lines.
- Editing a book's title in Calibre re-embeds the whole book (the title is part of every passage's embedding input). Other metadata edits re-extract it but keep the embeddings of unchanged passages.
- PDF sections come from a heuristic ("Chapter 3 …", "3.2 …" lines), so PDFs whose chapters are laid out differently get no section labels.
- The reader can't open LIT, CHM or DJVU books; the drawer offers their files to download instead. A passage indexed from one of those formats is looked up in another format of the book, where its text can differ more.
