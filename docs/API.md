# raggedlib HTTP API (contract between backend and frontend)

Base URL: `http://localhost:8080/api`. All bodies JSON (camelCase). Errors: HTTP 4xx/5xx with
`{"error": "message"}`. Timestamps ISO-8601 strings. IDs are calibre book ids (Long).

## Types

```ts
type BookStatus = "PENDING" | "EXTRACTING" | "EMBEDDING" | "INDEXED" | "FAILED" | "SKIPPED";

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
  error: string | null;       // last failure message (status FAILED/SKIPPED)
  indexedAt: string | null;
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
  chunksPerSecond: number;              // rolling rate over the last ~60s
  etaSeconds: number | null;
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
| POST | `/api/books/{id}/reindex` | Deletes chunks, sets PENDING. If ingestion not running, starts it. Returns `Book`. |
| GET  | `/api/tags` | `{ name: string, count: number }[]` sorted by count desc |
| POST | `/api/ingest/start` | Body optional `{ "bookIds"?: number[], "retryFailed"?: boolean }`. Starts background ingestion of all PENDING books (plus FAILED if retryFailed). Returns `IngestStatus`. Idempotent if running. |
| POST | `/api/ingest/stop` | Graceful stop after in-flight books. Returns `IngestStatus`. |
| GET  | `/api/ingest/status` | `IngestStatus` (frontend polls every 2s) |
| POST | `/api/search` | Body `SearchRequest` → `SourceChunk[]` (hybrid vector + full-text, reranked by a cross-encoder; no chat model). With reranking, passages below the relevance cutoff are dropped, so the list can be shorter than `topK` or empty. |
| POST | `/api/chat` | Body `ChatRequest` → `text/event-stream` (see below) |

```ts
interface SearchRequest {
  query: string;
  topK?: number;            // default 8, max 50
  bookIds?: number[];       // restrict to these books
  tags?: string[];          // restrict to books having ANY of these tags
  rerank?: boolean;         // cross-encoder reranking, default true; ignored (fused order) if the server has none
}

interface ChatRequest extends SearchRequest {
  history?: { role: "user" | "assistant"; content: string }[]; // previous turns, oldest first
  model?: string;           // override chat model (one of availableChatModels)
}
```

### Chat SSE stream

`POST /api/chat` responds with `Content-Type: text/event-stream`. Events (each `data:` is JSON):

1. `event: sources` — `data: SourceChunk[]` (sent once, first)
2. `event: token` — `data: {"t": "<text delta>"}` (many)
3. `event: done` — `data: {"elapsedMs": number}`
4. `event: error` — `data: {"error": "message"}` (terminal, instead of done)

The answer text cites passages with bracketed numbers `[1]`, `[2][4]` matching `SourceChunk.ref`.
Because it is a POST, the frontend consumes it with `fetch` + a `ReadableStream` SSE parser (not `EventSource`).
