# raggedlib

Ask questions about your Calibre ebook library. The answers cite the passages they came from: the page for PDFs, the section for ebooks. Everything runs locally on your GPU.

```
 frontend (Bun · React 19 · Blueprint 6)  :3000  ── /api proxy ──▶  backend (Kotlin · Spring Boot 4.1 · Spring AI 2.0 · JDK 25)  :8080
                                                                        │  calibredb / metadata.db (read-only)
                                                                        │  pdftotext · ebook-convert
                                                                        ├─▶ Postgres 18 + pgvector  :55432   (books, chunks, HNSW + full-text)
                                                                        ├─▶ Ollama (GPU)            :11434   qwen3-embedding:0.6b · qwen3.5:9b
                                                                        └─▶ llama.cpp (GPU)         :8091    bge-reranker-v2-m3 (/v1/rerank)
```

## Run it

```bash
docker compose up -d --build        # first run pulls ~7 GB of models
open http://localhost:3000          # Indexing tab → Start
```

The library is mounted **read-only** from `~/Documents/CalibreLibrary`. Set `CALIBRE_LIBRARY` to use another path, and `CHAT_MODEL` or `EMBED_MODEL` to swap models (see `.env.example`). Changing the embedding model also means changing `vector(1024)` in the migration and re-indexing.

### Develop on the host

```bash
docker compose up -d postgres ollama models reranker
(cd backend && mvn spring-boot:run)          # :8080
(cd frontend && bun install && bun run dev)  # :3000 with HMR
```

The host backend and the containerized backend can share the same database, because file paths are stored relative to the library root. Stop the `backend` and `frontend` containers first, or they will occupy the ports.

## Use it from agents (MCP)

The backend serves an [MCP](https://modelcontextprotocol.io) endpoint at `http://localhost:8080/mcp` (streamable HTTP, stateless) with five read-only tools:

| Tool | What it does |
|---|---|
| `search_library` | Reranked passage search (about 1 s, no LLM). Takes `query`, and optionally `topK`, `bookIds`, `tags`, `rerank` and `correctSpelling`. An empty result means the books don't cover it. |
| `ask_library` | A cited answer from the local chat model (20–90 s; cut off at 150 s), with its sources as short excerpts. Takes `question`, and optionally `topK`, `bookIds`, `tags`, `model` and `correctSpelling`. |
| `list_books` | Find books by title/author substring, tag or status, to get ids for `bookIds` |
| `list_tags` | Calibre tags by number of books |
| `library_status` | How many books are indexed and searchable |

Every MCP request needs an API key, sent as `Authorization: Bearer <key>` or `X-API-Key: <key>`. With no key configured, the endpoint refuses everything. The rest of the API (`/api`, used by the web UI) doesn't need a key; like the UI, it's published on localhost only.

```bash
KEY=$(openssl rand -hex 32)
touch .env && sed -i '/^MCP_API_KEY=/d' .env && echo "MCP_API_KEY=$KEY" >> .env   # docker compose reads .env
docker compose up -d --build backend
claude mcp add --transport http raggedlib http://localhost:8080/mcp --header "Authorization: Bearer $KEY"
```

`ask_library` runs for up to 150 s, and the stateless transport sends no progress updates meanwhile. Give it a tool timeout of at least 180 s: many clients default to 60 s. In Claude Code, set `MCP_TOOL_TIMEOUT=180000`.

Other harnesses take the same URL and header, for example as JSON: `{"type": "http", "url": "http://localhost:8080/mcp", "headers": {"Authorization": "Bearer <key>"}}`.

Keys are configured in `raggedlib.mcp.api-keys`, as a list of `{name, key}`. The name only identifies the client in logs. A key can be stored as `sha256:<hex>` instead of in plain text; `printf %s "$KEY" | sha256sum` gives the hex. Clients still send the plain key. In docker-compose, `MCP_API_KEY` (and `MCP_API_KEY_NAME`) fill entry 0. For more keys, add `RAGGEDLIB_MCP_API_KEYS_1_NAME` / `RAGGEDLIB_MCP_API_KEYS_1_KEY` and so on to the backend's environment.

## How it works

- **Sync** (`POST /api/library/sync`): reads metadata with `calibredb list`. If calibredb refuses, it falls back to reading `metadata.db` read-only. That happens when the Calibre GUI is open, and also inside the container, where the mount is read-only. Books whose calibre `last_modified` changed are queued for re-indexing.
- **Extraction**: one format is chosen per book, in the order EPUB > AZW3 > MOBI > FB2 > PDF > LIT > CHM > DJVU. PDFs go through `pdftotext`, which keeps page numbers. Everything else goes through `ebook-convert` to markdown, which gives section headings. The text is then cleaned up: headers and footers, TOC and index pages, and hyphenation. It is split into chunks of about 1,500 characters that overlap and respect paragraph boundaries. PDFs carry no heading markup, so their sections come from heading-like lines; table-of-contents pages near the front (many entries, dot leaders, "Chapter N" mentions) are recognised and contribute none, so a book's last TOC entry no longer labels its whole front matter (`Structure.kt`).
- **Duplicates**: a Calibre library can hold one book twice, under the same or another title. Before a book is embedded, its passages are looked up (by md5) in the other indexed books; if 90% or more are, word for word, in one at least as long, it is marked DUPLICATE of that book and gets no passages of its own (`Duplicates.kt`). The check also runs the other way after each book is indexed, so it doesn't matter which copy comes first. Searches restricted to the duplicate, or to a tag only it has, use the kept copy; if that copy leaves the library, the duplicate is indexed again. Repeated passages that aren't whole-book copies (an introduction printed twice in a PDF, front matter shared by volumes) are collapsed in search results when they share 60% of their word 5-grams (`NearDuplicates.kt`).
- **Re-indexing** keeps a book's passages searchable until the new ones replace them (a re-index that fails keeps them too), and reuses the embedding of every passage whose title, section and text are unchanged and that came from the current embedding model (`raggedlib.embedding.legacy-model` names the model of passages stored before that was recorded). `POST /api/ingest/reprocess` re-extracts already indexed books this way, e.g. after an extraction fix.
- **Indexing**: runs in the background and resumes after a restart. 3 extraction workers feed one GPU embedder, which sends batches of 32 to Ollama. Each book is written in one transaction. Expect about 25 chunks/s on the RTX 3500 Ada, which is about 5–6 hours for the whole library. Progress, ETA and errors are on the Indexing tab.
- **Retrieval**: pgvector cosine search (HNSW) plus Postgres full-text search, fused with weighted reciprocal rank fusion. Each vector hit counts in proportion to its cosine similarity, from 0 at 0.40 to full at 0.70. That keeps a weak vector side, where the embedding misreads a query like "return oriented programming gadgets" as being about OOP, from outvoting strong keyword matches. Full-text search first requires every term, honoring websearch syntax: quotes, `-term`, `or`. If that finds too little, a fallback looks for passages holding enough of the query's *distinctive* terms, ranked by IDF-weighted term coverage. In a programming library "program" and "return" say little and "gadget" says a lot, so matching only the common words doesn't count (`KeywordFallback.kt`). The best 30 fused passages (or `topK`, if larger) are then re-scored by a cross-encoder, bge-reranker-v2-m3 served by llama.cpp, which reads query and passage together. That improves the order and gives an absolute relevance score: passages below 0.10 are dropped, so a query nothing in the library matches ("who won the 1998 world cup") comes back empty instead of with the least-bad passages. It costs about 1 s per search, can be switched off per request (the sort button next to the scope controls), and if the reranker is down, saturated (all 4 slots busy) or slow, search falls back to the fused order; after a failure it is skipped for 15 s rather than every search waiting out the timeout. Results are capped at 3 passages per book for variety, and can be limited to particular books or tags.
- **Spelling correction**: before searching, misspelled query words are replaced from the library's own vocabulary (about 190k words that occur in 3+ passages, built with `ts_stat`). A word that occurs nowhere in the library is corrected to the closest word in 20+ passages: one edit away (a missing, extra, wrong or swapped letter), or two for words of 9+ letters. A word the library does contain, rarely, is corrected only to a one-edit neighbour 200× as frequent, which catches the classic typos books contain too ("recieve", 24 passages, vs "receive", 11,000) and leaves real words alone ("minced" stays, though "minded" is far more common). Corrections keep the first letter (or swap the first two), where typos are rare and names differ ("Niri" isn't "Siri"). Words under 5 letters, camelCase or ALL-CAPS words, and anything glued to digits or `_ . - / : @ #` (identifiers, flags, file names, `-exclusions`) are left alone. The corrected query is used for every stage (keyword search, embedding, reranker). The Search tab says "Showing results for …" with a link to search for the words as typed, the Ask tab shows "searched for …" with "ask as typed", and the API returns `correctedQuery` (`correct: false` turns it off). Unknown names close to a frequent word can still be "corrected", which is what those links are for. The vocabulary is rebuilt in the background (about 3 min) at startup when missing or stale, after each indexing run, and after a sync removed books (`Spelling.kt`, `SpellingCorrector.kt`, `SpellingVocabulary.kt`).
- **Chat**: a follow-up question is first rewritten into a standalone query. The answer streams over SSE with `[n]` citations. API contract: [`docs/API.md`](docs/API.md).

## Layout

```
backend/    Spring Boot app (dev.raggedlib.{calibre,extract,ingest,search,chat,library,system})
frontend/   Bun server (server.ts: static + /api proxy) and React app (src/{api,views,components,hooks,lib})
docs/API.md HTTP contract
```

## Troubleshooting

- **Chat is slow, or `ollama ps` shows `100% CPU`.** The Ollama container hands the GPU over through CDI
  (`devices: nvidia.com/gpu=all`), so it can't silently lose the GPU after a host `systemctl daemon-reload`,
  which used to show up as "Failed to initialize NVML: Unknown Error". If CDI isn't set up on your host, run
  `sudo nvidia-ctk cdi generate --output=/etc/cdi/nvidia.yaml` once.
- **Reranker unreachable** (yellow status dot, "Reranker UNREACHABLE" in its tooltip). Search still works in the fused
  order. Check `docker compose logs reranker`; on first start it downloads the model (~640 MB) before it turns healthy.
- **Slow generation even on the GPU.** On a laptop, check `powerprofilesctl get`. The `power-saver` profile keeps the
  GPU in low-clock power states.

## Known limitations

- Scanned PDFs with no text layer are marked SKIPPED. There is no OCR.
- Code blocks in PDFs come out as single lines.
- Editing a book's title in Calibre re-embeds the whole book (the title is part of every passage's embedding input). Other metadata edits re-extract it but keep the embeddings of unchanged passages.
- PDF sections come from a heuristic ("Chapter 3 …", "3.2 …" lines), so PDFs whose chapters are laid out differently get no section labels.
