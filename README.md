# raggedlib

Ask questions about your Calibre ebook library. The answers cite the passages they came from: the page for PDFs, the section for ebooks. Everything runs locally on your GPU.

```
 frontend (Bun · React 19 · Blueprint 6)  :3000  ── /api proxy ──▶  backend (Kotlin · Spring Boot 4.1 · Spring AI 2.0 · JDK 25)  :8080
                                                                        │  calibredb / metadata.db (read-only)
                                                                        │  pdftotext · ebook-convert
                                                                        ├─▶ Postgres 18 + pgvector  :55432   (books, chunks, HNSW + full-text)
                                                                        └─▶ Ollama (GPU)            :11434   qwen3-embedding:0.6b · qwen3.5:9b
```

## Run it

```bash
docker compose up -d --build        # first run pulls ~6 GB of models
open http://localhost:3000          # Indexing tab → Start
```

The library is mounted **read-only** from `~/Documents/CalibreLibrary`. Set `CALIBRE_LIBRARY` to use another path, and `CHAT_MODEL` or `EMBED_MODEL` to swap models (see `.env.example`). Changing the embedding model also means changing `vector(1024)` in the migration and re-indexing.

### Develop on the host

```bash
docker compose up -d postgres ollama models
(cd backend && mvn spring-boot:run)          # :8080
(cd frontend && bun install && bun run dev)  # :3000 with HMR
```

The host backend and the containerized backend can share the same database, because file paths are stored relative to the library root. Stop the `backend` and `frontend` containers first, or they will occupy the ports.

## How it works

- **Sync** (`POST /api/library/sync`): reads metadata with `calibredb list`. If calibredb refuses, it falls back to reading `metadata.db` read-only. That happens when the Calibre GUI is open, and also inside the container, where the mount is read-only. Books whose calibre `last_modified` changed are queued for re-indexing.
- **Extraction**: one format is chosen per book, in the order EPUB > AZW3 > MOBI > FB2 > PDF > LIT > CHM > DJVU. PDFs go through `pdftotext`, which keeps page numbers. Everything else goes through `ebook-convert` to markdown, which gives section headings. The text is then cleaned up: headers and footers, TOC and index pages, and hyphenation. It is split into chunks of about 1,500 characters that overlap and respect paragraph boundaries.
- **Indexing**: runs in the background and resumes after a restart. 3 extraction workers feed one GPU embedder, which sends batches of 32 to Ollama. Each book is written in one transaction. Expect about 25 chunks/s on the RTX 3500 Ada, which is about 5–6 hours for the whole library. Progress, ETA and errors are on the Indexing tab.
- **Retrieval**: pgvector cosine search (HNSW) plus Postgres full-text search, fused with weighted reciprocal rank fusion. Each vector hit counts in proportion to its cosine similarity, from 0 at 0.40 to full at 0.70. That keeps a weak vector side, where the embedding misreads a query like "return oriented programming gadgets" as being about OOP, from outvoting strong keyword matches. Full-text search first requires every term, honoring websearch syntax: quotes, `-term`, `or`. If that finds too little, a fallback looks for passages holding enough of the query's *distinctive* terms, ranked by IDF-weighted term coverage. In a programming library "program" and "return" say little and "gadget" says a lot, so matching only the common words doesn't count (`KeywordFallback.kt`). Results are capped at 3 passages per book for variety, and can be limited to particular books or tags.
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
- **Slow generation even on the GPU.** On a laptop, check `powerprofilesctl get`. The `power-saver` profile keeps the
  GPU in low-clock power states.

## Known limitations

- Scanned PDFs with no text layer are marked SKIPPED. There is no OCR.
- Code blocks in PDFs come out as single lines.
- Editing a book's metadata in Calibre re-embeds the whole book.
