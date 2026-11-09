# raggedlib HTTP API (contract between backend and frontend)

Base URL: `http://localhost:8080/api` (the web UI proxies it as `/api`, and `/mcp` too). All bodies JSON (camelCase).
Errors: HTTP 4xx/5xx with `{"error": "message"}`. Timestamps ISO-8601 strings. IDs are calibre book ids (Long).

## Authentication and authorization

Every endpoint except `/api/auth/**` and `/actuator/health` needs a caller:

- **A browser session.** `GET /api/auth/login/oidc` starts OIDC sign-in (Spring Security `oauth2Login`); the provider
  redirects back to `/api/auth/callback/oidc`, and the backend then to `/` (or `/?login_error=<code>`, e.g.
  `account_disabled`). `POST /api/auth/logout` → `204`. Cookie-authenticated writes (anything but GET/HEAD) must echo
  the `XSRF-TOKEN` cookie in an `X-XSRF-TOKEN` header, else `403`. A session has every scope.
- **An API key**, as `Authorization: Bearer <key>` or `X-API-Key: <key>`, on `/api` and `/mcp` alike: a personal key
  (acts as its owner, with the scopes chosen when it was made), a system-wide key (made by an admin, acts as no
  user, library scopes only), or a key from `raggedlib.mcp.api-keys` (system-wide, `library:read`). A bad key is
  `401` even if a session cookie came along.
- **Single-user mode** (`raggedlib.auth.mode=single-user`): no sign-in; every request without a key, except to
  `/mcp`, is the built-in local admin (a session in all but name: CSRF applies). `/api/auth/login/**` is `404`.

Unauthenticated: `401 {"error", "loginUrl"}` (`/mcp`, or a request with a key: `401` with `WWW-Authenticate: Bearer`).

Scopes (`GET /api/me/api-keys/scopes`) gate endpoints: `library:read` everything that reads libraries and books,
`library:curate` sync/index/re-index/book vectors, `favorites:read|write` `/api/lists`, `reviews:read|write` reviews.
Key management, `/api/admin/**` and library members need a browser session.

Roles gate data, per library: `VIEWER` < `CURATOR` < `ADMIN` (`NONE` = no access). A user's role in a library is the
highest of: a role granted in the app, a role mapped from an IdP group (`raggedlib.auth.group-roles`), and
`raggedlib.auth.default-library-role`; a global admin (admin claim, or granted in the app) is `ADMIN` everywhere.
A system-wide key is `VIEWER`, or `CURATOR` with `library:curate`. A key restricted to some libraries has no role in
the others. Viewers read; curators re-index single books of their libraries, watch indexing and remove reviews of
their books; library admins also sync their libraries, start, stop and retry indexing, and grant roles in the
library. (So a system-wide key, never a library admin, can't sync or start indexing; a library admin's personal key
with `library:curate` can.) A library or book the caller can't see is `404`, an action its role
doesn't allow `403`.

## Types

```ts
type BookStatus = "PENDING" | "EXTRACTING" | "EMBEDDING" | "INDEXED" | "FAILED" | "SKIPPED" | "DUPLICATE";

interface Book {
  id: number;                 // internal id, unique across every configured library
  libraryId: number;           // which configured library (see GET /api/libraries)
  calibreId: number;           // this book's id within its own Calibre library (not unique across libraries)
  title: string;
  authors: string[];
  tags: string[];
  series: string | null;
  seriesIndex: number | null;
  languages: string[];
  pubdate: string | null;
  publisher: string | null;             // from Calibre; filled in by a library sync
  formats: string[];          // upper-case extensions available, e.g. ["PDF","EPUB"]
  sourceFormat: string | null;// format chosen for indexing, e.g. "EPUB"
  hasCover: boolean;
  status: BookStatus;
  chunkCount: number;
  error: string | null;       // last failure message (status FAILED/SKIPPED), or which book a DUPLICATE copies
  indexedAt: string | null;
  duplicateOf: number | null; // status DUPLICATE: the book whose passages stand in for this one (searches restricted
                              // to this book, or to a tag only it has, use that book)
  ocrPages: number;           // pages of the indexed text that came from OCR (scanned PDF pages); 0 = none
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
  parallelBooks: number;                // books extracted at the same time (see /api/settings)
  chunksPerSecond: number;              // passages embedded per second, rolling over the last ~60s
  etaSeconds: number | null;            // remaining passages / passages of books finished per second (last ~10 min),
                                        // embedded or reused; null until a book has finished
  active: {
    bookId: number; libraryId: number; title: string;
    stage: "EXTRACTING" | "OCR" | "EMBEDDING";
    chunks: number | null;
    ocr: { done: number; total: number } | null;  // stage OCR: pages read so far
  }[];
  recentErrors: { bookId: number; libraryId: number; title: string; error: string; at: string }[]; // newest first, max 50
}

type LibraryRole = "NONE" | "VIEWER" | "CURATOR" | "ADMIN";

interface Library {
  id: number;
  name: string;
  path: string | null;                  // admins only
  bookCount: number;
  role: LibraryRole;                    // the caller's
}

interface SystemInfo {
  libraries: { name: string; path: string | null }[];  // the caller's; path for admins only
  ollamaUrl: string;
  ollamaReachable: boolean;
  chatModel: string;
  embeddingModel: string;
  embeddingDimensions: number;
  availableChatModels: string[];        // from ollama /api/tags, minus embedding models
  rerankEnabled: boolean;               // server has a reranker configured (raggedlib.rerank.enabled)
  rerankerUrl: string;
  rerankerReachable: boolean;           // GET <rerankerUrl>/health answered
  rerankerModel: string | null;         // GET <rerankerUrl>/v1/models' model name; null if unreachable
}
```

## Endpoints

| Method | Path | Description |
|---|---|---|
| GET  | `/api/system` | `SystemInfo` |
| GET  | `/api/libraries` | `Library[]`: the configured Calibre libraries the caller can see, with book counts and the caller's role. |
| POST | `/api/library/sync?libraryId=` | Library admin. Runs `calibredb list` (or reads `metadata.db`) for `libraryId`, or every library the caller administers, and upserts the books table; one library's failure doesn't block the others. Returns `{ added, updated, removed, total }` summed over all of them. Books whose calibre `last_modified` changed are reset to PENDING. |
| GET  | `/api/books?q=&status=&tag=&publisher=&libraryId=&page=0&size=50&sort=title` | `Page<Book>`. `q` matches title/authors (case-insensitive substring). `tag` and `publisher` are exact names. `libraryId` restricts to one library (default: every library the caller can see). `sort` ∈ `title`,`author`,`publisher`,`pubdate`,`indexedAt`,`id`. |
| GET  | `/api/books/{id}` | `Book` |
| GET  | `/api/books/{id}/cover` | `image/jpeg` (404 if none) |
| GET  | `/api/books/{id}/file?format=PDF` | Streams the book file (inline, correct content-type). Frontend links `.../file?format=PDF#page=12`. |
| GET  | `/api/books/{id}/details` | `BookDetails`: the `Book`, Calibre extras read from `metadata.db` (`calibre`: description as plain text, publisher, identifiers, rating 0.5–5, addedAt, fileSizes; null if unreadable), `duplicateOf: {id, title}` for a DUPLICATE, and `sections: {title, firstPage, passages}[]`, the indexed outline in reading order. |
| POST | `/api/books/{id}/reindex` | Curator of the book's library. Sets PENDING and queues the book (starting ingestion if idle); its passages stay searchable until the new ones replace them. Returns `Book`. |
| GET  | `/api/tags?libraryId=` | `{ name: string, count: number }[]` sorted by count desc; `libraryId` restricts to one library |
| GET  | `/api/publishers?libraryId=` | `{ name: string, count: number }[]`: publishers by number of books, like `/api/tags` (books without one are left out) |
| POST | `/api/ingest/start` | Library admin. Body optional `{ "bookIds"?: number[], "retryFailed"?: boolean }`. Starts background ingestion of all PENDING books (plus FAILED if retryFailed) of the libraries the caller administers (`bookIds`: each must be in one). Returns `IngestStatus`. Idempotent if running. |
| POST | `/api/ingest/reprocess` | Library admin. Body optional `{ "bookIds"?: number[], "formats"?: string[] }`. Extracts INDEXED and DUPLICATE books of the caller's administered libraries again (all when `bookIds` is absent; an empty list selects none; or those ids / source formats), e.g. after extraction improved. Passages whose text and section are unchanged keep their embeddings; the rest are re-embedded. Old passages stay searchable until replaced. Returns `IngestStatus`. |
| POST | `/api/ingest/stop?force=` | Library admin (of any library: the run is shared). Graceful by default: waits for books in flight. `force=true` interrupts them right away (killing e.g. an OCR process) and puts them back to PENDING instead of letting them finish. Returns `IngestStatus`. |
| GET  | `/api/settings` | `ServerSettings` (below): settings changed at runtime, stored on the server. |
| PUT  | `/api/settings` | Global admin. Body `{ "parallelBooks"?: number }` → `ServerSettings`. `parallelBooks` (1–`parallelBooksMax`) applies to a running indexing at once; `400` outside the range. |
| GET  | `/api/ingest/status` | `IngestStatus` (frontend polls every 2s). One background run indexes every library together; counts, active books and errors are those of the libraries the caller can see. |
| POST | `/api/search` | Body `SearchRequest` → `SearchResponse` (hybrid vector + full-text, reranked by a cross-encoder; no chat model), over the libraries the caller can see. With reranking, passages below the relevance cutoff are dropped, so `results` can be shorter than `topK` or empty. |
| POST | `/api/chat` | Body `ChatRequest` → `text/event-stream` (see below) |
| GET | `/api/vectors?libraryId=&level=passages\|books&method=pca\|umap\|tsne&size=1000&dims=2\|3&seed=&neighbors=&minDist=&epochs=&perplexity=&iterations=` | `VectorSpace`: a seeded sample of the passages (`level=passages`, default) or book vectors (`level=books`: one per book, the normalized average of its passages' embeddings) of one library's books (`libraryId` required; `404` if unknown), projected to 2-D or, with `dims=3`, 3-D (points get a `z`). `size` 1..2000 (default 1000); the same seed gives the same sample and layout. Without `seed`, the library's current sample (seed 1 until reshuffled); a `seed` (Reshuffle) becomes the library's current sample. The last layout per library, level, method, size and dims is cached in memory until the library's passages or book vectors change (a book indexed, re-indexed or removed; book vectors computed) or it is reshuffled; `cached` says whether it came from there. Coordinates are only comparable within one response. `t-SNE` is O(n²) per iteration, so it is the slowest at large sizes. Optional tuning, each only for its method (the others are ignored; `400` if out of range): UMAP `neighbors` 2..100 (default 15), `minDist` 0..0.99 (0.1), `epochs` 50..1000 (300); t-SNE `perplexity` 2..100 (30, at most (points − 1) / 3), `iterations` 300..2000 (1000). Each set of parameters is cached apart; `params` reports the ones used. |
| GET | `/api/vectors/topics?libraryId=&level=passages\|books&size=1000&space=raw\|umap&seed=&minClusterSize=&minSamples=` | `TopicSpace`: HDBSCAN topics of the same sample as `/api/vectors` (same library, level, size and seed), labelled by class-based TF-IDF, clustered on the raw embeddings or a UMAP reduction (`space`). `minClusterSize` 2..500 (default about 2.5 % of `size`, between 5 and 50) is the smallest topic; `minSamples` 1..500 (default `minClusterSize`): higher means fewer, denser topics and more noise. The response reports both as used. Cached like the layouts. |
| POST | `/api/vectors/book-vectors?libraryId=` | Curator. Computes book vectors for books (of `libraryId`, default every library the caller curates) that have passages but no book vector, or one older than their last indexing: averaged from the stored passage embeddings, nothing re-indexed (about 3 s for ~1,800 books). Indexing computes a book's vector itself; this is for books indexed before book vectors existed. Returns `{ computed, missing }`. |

```ts
interface SearchRequest {
  query: string;
  topK?: number;            // default 8, max 50
  bookIds?: number[];       // restrict to these books
  tags?: string[];          // restrict to books having ANY of these tags
  libraryIds?: number[];    // restrict to these libraries (see GET /api/libraries); default: every library
  publishers?: string[];    // restrict to books from ANY of these publishers (see GET /api/publishers)
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

```ts
interface VectorSpace {
  libraryId: number;
  level: "passages" | "books";
  method: "pca" | "umap" | "tsne";
  dims: 2 | 3;
  seed: number;
  size: number;                        // points projected (≤ total)
  total: number;                       // passages (or books) with a vector in this library
  missingBookVectors: number;          // books with passages but no book vector yet (POST /api/vectors/book-vectors)
  dimensions: number;                  // embedding dimensions before projection
  explainedVariance: number[] | null;  // PCA only: variance share of the two axes
  params: { neighbors?: number; minDist?: number; epochs?: number; perplexity?: number; iterations?: number }; // the method's own, as used
  elapsedMs: number;                   // how long computing it took (also when served from the cache)
  points: VectorPoint[];
  timings: Record<string, number>;     // ms per step of computing it, in order (count, sample, parse, pca, distances, umap/tsne)
  cached: boolean;                     // served from the cache rather than computed for this request
}

interface VectorPoint {
  id: string;        // the passage's id, or (books) the book's
  bookId: number;
  title: string;
  page: number | null;
  section: string | null; // the passage's section; for a book, "N passages"
  snippet: string;   // leading part of the passage; for a book, its authors · publisher · year
  x: number;
  y: number;
  z: number | null;  // 3-D layouts only
}
```

### Account and API keys

| Method | Path | Description |
|---|---|---|
| GET    | `/api/me` | `Me`: who the caller is, its libraries and roles. |
| GET    | `/api/me/api-keys/scopes` | `{ scope, description, perUser }[]`: the scopes a key can have. |
| GET    | `/api/me/api-keys` | Session only. `ApiKey[]`: the caller's keys (not revoked). |
| POST   | `/api/me/api-keys` | Session only. Body `ApiKeyRequest` → `201 { apiKey: ApiKey, rawKey }`; `rawKey` (`rlk_…`) is shown only here. |
| DELETE | `/api/me/api-keys/{id}` | Session only. Revokes the key at once → `204`. |
| GET    | `/api/me/reviews` | `reviews:read`. The caller's `Review[]`, newest first. |

```ts
type ApiScope = "library:read" | "library:curate" | "favorites:read" | "favorites:write" | "reviews:read" | "reviews:write";

interface Me {
  id: number | null;                    // null for a system-wide key
  displayName: string;
  email: string | null;
  admin: boolean;                       // global admin
  authMode: "oidc" | "single-user";
  viaApiKey: boolean;
  scopes: ApiScope[];
  libraries: { id: number; name: string; role: LibraryRole }[];   // the ones the caller can see
  canCurate: boolean;                   // curator (or more) somewhere
  canManageMembers: boolean;            // library admin somewhere
  canRunIndexing: boolean;              // library admin somewhere: may sync and start/stop indexing
}

interface ApiKeyRequest {
  name: string;
  scopes: ApiScope[];                   // at least one; system-wide keys: library scopes only
  libraryIds?: number[];                // only these libraries; omitted = every library
  expiresAt?: string | null;            // omitted/null = never
}

interface ApiKey {
  id: string; name: string;
  keyPrefix: string;                    // first 8 characters, to tell keys apart
  scopes: ApiScope[]; libraryIds: number[] | null;
  system: boolean; createdBy: string | null;
  createdAt: string; lastUsedAt: string | null; expiresAt: string | null;
}
```

### Administration

Global admins, browser session only.

| Method | Path | Description |
|---|---|---|
| GET    | `/api/admin/users` | `User[]`: everyone who has signed in. |
| PUT    | `/api/admin/users/{id}` | Body `{ adminGranted?, disabled? }` → `User`. A disabled user's sessions and keys stop working and sign-in is refused. `400` for yourself. |
| GET    | `/api/admin/api-keys` | `ApiKey[]`: system-wide keys. |
| POST   | `/api/admin/api-keys` | Body `ApiKeyRequest` (library scopes only) → `201 { apiKey, rawKey }`. |
| DELETE | `/api/admin/api-keys/{id}` | Revokes a system-wide key → `204`. |

Library admins of `{libraryId}` (and global admins), browser session only:

| Method | Path | Description |
|---|---|---|
| GET    | `/api/libraries/{libraryId}/members` | `Member[]`: every user, with the role granted in this library and the effective one. |
| PUT    | `/api/libraries/{libraryId}/members/{userId}` | Body `{ role: "viewer" \| "curator" \| "admin" }` (any case) → `Member`. |
| DELETE | `/api/libraries/{libraryId}/members/{userId}` | Revokes the granted role → `Member` (what the user still has, e.g. through a group); `404` if none was granted. |

```ts
interface User {
  id: number; displayName: string; email: string | null; groups: string[];
  admin: boolean; adminFromClaim: boolean; adminGranted: boolean; disabled: boolean;
  createdAt: string; lastLoginAt: string | null;
  granted: Record<number, LibraryRole>;   // by library id, granted in the app
  roles: Record<number, LibraryRole>;     // by library id, effective (grants, groups, default, admin)
}

interface Member {
  userId: number; displayName: string; email: string | null;
  granted: LibraryRole | null; role: LibraryRole; admin: boolean; disabled: boolean;
}
```

### Favorite lists

Per user: a list is private, or shared (readable by every signed-in user). Only its owner changes it. Readers see
only the books of libraries they can see. `favorites:read` for GET, `favorites:write` for the rest.

| Method | Path | Description |
|---|---|---|
| GET    | `/api/lists?bookId=` | `{ mine: FavoriteList[], shared: FavoriteList[] }` (shared: other users' shared lists). With `bookId`, each list says whether it holds that book. |
| POST   | `/api/lists` | Body `{ name, description?, shared?, bookIds? }` → `201 FavoriteList`. `bookIds` (up to 500): the books to start it with, first shown first; all must be visible to the caller, or nothing is created (`404`). |
| GET    | `/api/lists/{id}` | `{ list: FavoriteList, books: { book: Book, note, addedAt }[] }`, newest first. |
| PUT    | `/api/lists/{id}` | Body `{ name?, description?, shared? }` → `FavoriteList`. |
| DELETE | `/api/lists/{id}` | `204`. |
| PUT    | `/api/lists/{id}/books/{bookId}` | Body optional `{ note? }`: adds the book, or sets its note (omitted or null: keep it; empty: clear it) → `FavoriteList`. |
| POST   | `/api/lists/{id}/books` | Body `{ bookIds }` (1 to 500), such as a search result's books: adds them, first shown first; books already on the list keep their note and place → `{ list: FavoriteList, added, alreadyOn }`. All or nothing: an unknown or invisible book is a `404`. |
| DELETE | `/api/lists/{id}/books/{bookId}` | → `FavoriteList`. |

```ts
interface FavoriteList {
  id: number; name: string; description: string | null; shared: boolean;
  ownerId: number; ownerName: string; mine: boolean;
  bookCount: number;                    // books the caller can see
  createdAt: string; updatedAt: string;
  containsBook: boolean | null;         // with ?bookId=
}
```

### Reviews

One per user and book: a rating 1–5, a text, or both. Readable by everyone who can see the book; `reviews:read` for
GET, `reviews:write` for the rest.

| Method | Path | Description |
|---|---|---|
| GET    | `/api/books/{id}/reviews` | `BookReviews`. |
| PUT    | `/api/books/{id}/reviews/mine` | Body `{ rating?, body? }` (at least one) → `Review`; replaces the caller's earlier review. |
| DELETE | `/api/books/{id}/reviews/mine` | `204`. |
| DELETE | `/api/reviews/{id}` | The author, or a curator of the book's library → `204`. |

```ts
interface Review {
  id: number; bookId: number; bookTitle: string; userId: number; author: string;
  rating: number | null; body: string; createdAt: string; updatedAt: string; mine: boolean;
}

interface BookReviews {
  bookId: number; count: number;
  averageRating: number | null; ratings: number;  // over the reviews that have a rating
  reviews: Review[]; mine: Review | null;
  canModerate: boolean;                 // the caller may remove others' reviews (curator)
}
```

### MCP

`/mcp` is an MCP server (streamable HTTP, stateless, protocol handled by Spring AI) for API keys only (a browser
session gets `403`). Each tool needs a scope, and `tools/list` lists only the tools the key may call:

| Scope | Tools |
|---|---|
| `library:read` | `search_library`, `ask_library`, `list_books`, `list_tags`, `list_libraries`, `library_status` |
| `favorites:read` | `list_favorite_lists`, `get_favorite_list` |
| `favorites:write` | `create_favorite_list`, `add_to_favorite_list`, `add_books_to_favorite_list`, `remove_from_favorite_list` |
| `reviews:read` | `get_book_reviews`, `list_my_reviews` |
| `reviews:write` | `write_book_review` |

The favorites and reviews tools act as the key's owner, so a system-wide key never has them. Every tool sees only
the libraries the key (and its owner) can.

Tool results are JSON text: `search_library` returns `{query, correctedQuery, passages: Passage[], note}`, where a
`Passage` is a `SourceChunk` without `chunkId`. `ask_library` returns `{answer, sources: Passage[], searchQuery,
corrections, model, elapsedMs}`, `searchQuery` being the (rewritten, spell-corrected) query the passages were
retrieved with. Both take `correctSpelling: false` to use the query as written, and an optional `libraryIds` to
restrict to particular libraries (ids from `list_libraries`).

Without a valid key the server responds `401` with `WWW-Authenticate: Bearer realm="raggedlib"` and a JSON
`{error, message}`.

### Chat SSE stream

`POST /api/chat` responds with `Content-Type: text/event-stream`. Events (each `data:` is JSON):

1. `event: corrected` — `data: {"correctedQuery": string, "corrections": {from, to}[]}` (only when the search query was spell-corrected; before sources)
2. `event: sources` — `data: SourceChunk[]` (sent once)
3. `event: token` — `data: {"t": "<text delta>"}` (many)
4. `event: done` — `data: {"elapsedMs": number}`
5. `event: error` — `data: {"error": "message"}` (terminal, instead of done)

The answer text cites passages with bracketed numbers `[1]`, `[2][4]` matching `SourceChunk.ref`.
Because it is a POST, the frontend consumes it with `fetch` + a `ReadableStream` SSE parser (not `EventSource`).

### Server settings

```ts
interface ServerSettings {
  parallelBooks: number;        // books extracted at the same time while indexing (the GPU embeds one queue)
  parallelBooksDefault: number; // raggedlib.ingest.extract-concurrency
  parallelBooksMax: number;     // raggedlib.ingest.max-extract-concurrency, or the number of cores (at most 16)
}
```

