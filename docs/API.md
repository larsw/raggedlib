# raggedlib HTTP API (contract between backend and frontend)

Base URL: `http://localhost:8080/api`. All bodies JSON (camelCase). Errors: HTTP 4xx/5xx with
`{"error": "message"}`. Timestamps ISO-8601 strings. IDs are calibre book ids (Long).

## Types

```ts
type BookStatus = "PENDING" | "EXTRACTING" | "EMBEDDING" | "INDEXED" | "FAILED" | "SKIPPED" | "DUPLICATE";

interface Book {
  id: number;                 // calibre id
  title: string;
  authors: string[];
  tags: string[];
  series: string | null;
  seriesIndex: number | null;
  languages: string[];
  pubdate: string | null;
  formats: string[];          // upper-case extensions available, e.g. ["PDF","EPUB"]
  sourceFormat: string | null;// format chosen for indexing, e.g. "EPUB"
  hasCover: boolean;
  status: BookStatus;
  chunkCount: number;
  error: string | null;       // last failure message (status FAILED/SKIPPED), or which book a DUPLICATE copies
  indexedAt: string | null;
  duplicateOf: number | null; // status DUPLICATE: the book whose passages stand in for this one (searches restricted
                              // to this book, or to a tag only it has, use that book)
}

interface Page<T> { items: T[]; total: number; page: number; size: number; }

interface SourceChunk {        // one retrieved passage
  ref: number;                 // 1-based citation number used in the answer text: [1], [2] ...
  chunkId: string;
  bookId: number;
  title: string;
  authors: string[];
  page: number | null;         // 1-based page (PDF only)
  section: string | null;      // nearest heading (EPUB etc.)
  text: string;                // chunk text
  score: number;               // higher = better; scale depends on scoreKind
  scoreKind: "RERANK" | "FUSED"; // RERANK: reranker relevance 0..1, comparable across queries;
                               // FUSED: weighted-RRF score, only meaningful within one result list
}

interface IngestStatus {
  running: boolean;
  stopRequested: boolean;
  startedAt: string | null;
  counts: Record<BookStatus, number>;   // over the whole library
  totalBooks: number;
  totalChunks: number;                  // rows in vector store
  sessionProcessed: number;             // books finished (indexed+failed+skipped) in the current/last run
  sessionChunks: number;                // chunks embedded in the current/last run
  chunksPerSecond: number;              // passages embedded per second, rolling over the last ~60s
  etaSeconds: number | null;            // remaining passages / passages of books finished per second (last ~10 min),
                                        // embedded or reused; null until a book has finished
  active: { bookId: number; title: string; stage: "EXTRACTING" | "EMBEDDING"; chunks: number | null }[];
  recentErrors: { bookId: number; title: string; error: string; at: string }[]; // newest first, max 50
}

interface SystemInfo {
  libraryPath: string;
  ollamaUrl: string;
  ollamaReachable: boolean;
  chatModel: string;
  embeddingModel: string;
  embeddingDimensions: number;
  availableChatModels: string[];        // from ollama /api/tags, minus embedding models
  rerankEnabled: boolean;               // server has a reranker configured (raggedlib.rerank.enabled)
  rerankerUrl: string;
  rerankerReachable: boolean;           // GET <rerankerUrl>/health answered
}
```

## Endpoints

| Method | Path | Description |
|---|---|---|
| GET  | `/api/system` | `SystemInfo` |
| POST | `/api/library/sync` | Run `calibredb list` and upsert books table. Returns `{ added, updated, removed, total }`. Books whose calibre `last_modified` changed are reset to PENDING. |
| GET  | `/api/books?q=&status=&tag=&page=0&size=50&sort=title` | `Page<Book>`. `q` matches title/authors (case-insensitive substring). `sort` ∈ `title`,`author`,`pubdate`,`indexedAt`,`id`. |
| GET  | `/api/books/{id}` | `Book` |
| GET  | `/api/books/{id}/cover` | `image/jpeg` (404 if none) |
| GET  | `/api/books/{id}/file?format=PDF` | Streams the book file (inline, correct content-type). Frontend links `.../file?format=PDF#page=12`. |
| GET  | `/api/books/{id}/details` | `BookDetails`: the `Book`, Calibre extras read from `metadata.db` (`calibre`: description as plain text, publisher, identifiers, rating 0.5–5, addedAt, fileSizes; null if unreadable), `duplicateOf: {id, title}` for a DUPLICATE, and `sections: {title, firstPage, passages}[]`, the indexed outline in reading order. |
| POST | `/api/books/{id}/reindex` | Sets PENDING and queues the book (starting ingestion if idle); its passages stay searchable until the new ones replace them. Returns `Book`. |
| GET  | `/api/tags` | `{ name: string, count: number }[]` sorted by count desc |
| POST | `/api/ingest/start` | Body optional `{ "bookIds"?: number[], "retryFailed"?: boolean }`. Starts background ingestion of all PENDING books (plus FAILED if retryFailed). Returns `IngestStatus`. Idempotent if running. |
| POST | `/api/ingest/reprocess` | Body optional `{ "bookIds"?: number[], "formats"?: string[] }`. Extracts INDEXED and DUPLICATE books again (all, or those ids / source formats), e.g. after extraction improved. Passages whose text and section are unchanged keep their embeddings; the rest are re-embedded. Old passages stay searchable until replaced. Returns `IngestStatus`. |
| POST | `/api/ingest/stop` | Graceful stop after in-flight books. Returns `IngestStatus`. |
| GET  | `/api/ingest/status` | `IngestStatus` (frontend polls every 2s) |
| POST | `/api/search` | Body `SearchRequest` → `SearchResponse` (hybrid vector + full-text, reranked by a cross-encoder; no chat model). With reranking, passages below the relevance cutoff are dropped, so `results` can be shorter than `topK` or empty. |
| POST | `/api/chat` | Body `ChatRequest` → `text/event-stream` (see below) |

```ts
interface SearchRequest {
  query: string;
  topK?: number;            // default 8, max 50
  bookIds?: number[];       // restrict to these books
  tags?: string[];          // restrict to books having ANY of these tags
  rerank?: boolean;         // cross-encoder reranking, default true; ignored (fused order) if the server has none
  correct?: boolean;        // spelling correction, default true; false searches for the query exactly as written
}

interface SearchResponse {
  results: SourceChunk[];
  correctedQuery: string | null;               // what was searched for instead, if misspelled words were corrected
  corrections: { from: string; to: string; known: boolean }[]; // as typed → as searched; known: the typed word occurs (rarely)
}

interface ChatRequest extends SearchRequest {
  history?: { role: "user" | "assistant"; content: string }[]; // previous turns, oldest first
  model?: string;           // override chat model (one of availableChatModels)
}
```

### MCP

`/mcp` is an MCP server (streamable HTTP, stateless, protocol handled by Spring AI) with the tools `search_library`,
`ask_library`, `list_books`, `list_tags` and `library_status`. Tool results are JSON text: `search_library` returns
`{query, correctedQuery, passages: Passage[], note}`, where a `Passage` is a `SourceChunk` without `chunkId`. `ask_library`
returns `{answer, sources: Passage[], searchQuery, corrections, model, elapsedMs}`, `searchQuery` being the (rewritten,
spell-corrected) query the passages were retrieved with. Both take `correctSpelling: false` to use the query as written.

Each request needs `Authorization: Bearer <key>` or `X-API-Key: <key>` matching one of `raggedlib.mcp.api-keys`.
Otherwise the server responds `401` with `WWW-Authenticate: Bearer realm="raggedlib-mcp"` and a JSON `{error, message}`.

### Chat SSE stream

`POST /api/chat` responds with `Content-Type: text/event-stream`. Events (each `data:` is JSON):

1. `event: corrected` — `data: {"correctedQuery": string, "corrections": {from, to}[]}` (only when the search query was spell-corrected; before sources)
2. `event: sources` — `data: SourceChunk[]` (sent once)
3. `event: token` — `data: {"t": "<text delta>"}` (many)
4. `event: done` — `data: {"elapsedMs": number}`
5. `event: error` — `data: {"error": "message"}` (terminal, instead of done)

The answer text cites passages with bracketed numbers `[1]`, `[2][4]` matching `SourceChunk.ref`.
Because it is a POST, the frontend consumes it with `fetch` + a `ReadableStream` SSE parser (not `EventSource`).
