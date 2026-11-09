// Copied from docs/API.md — the contract between backend and frontend. Keep in sync.

export type BookStatus =
  | "PENDING"
  | "EXTRACTING"
  | "EMBEDDING"
  | "INDEXED"
  | "FAILED"
  | "SKIPPED"
  | "DUPLICATE"; // same text as another indexed book (duplicateOf), which searches use instead

export const BOOK_STATUSES: readonly BookStatus[] = [
  "PENDING",
  "EXTRACTING",
  "EMBEDDING",
  "INDEXED",
  "FAILED",
  "SKIPPED",
  "DUPLICATE",
];

export interface Book {
  id: number; // internal id, unique across every configured library
  libraryId: number; // which configured library (see GET /api/libraries)
  calibreId: number; // this book's id within its own Calibre library (not unique across libraries)
  title: string;
  authors: string[];
  tags: string[];
  series: string | null;
  seriesIndex: number | null;
  languages: string[];
  pubdate: string | null;
  publisher: string | null;
  formats: string[]; // upper-case extensions available, e.g. ["PDF","EPUB"]
  sourceFormat: string | null; // format chosen for indexing, e.g. "EPUB"
  hasCover: boolean;
  status: BookStatus;
  chunkCount: number;
  error: string | null; // last failure message (status FAILED/SKIPPED), or which book a DUPLICATE copies
  indexedAt: string | null;
  duplicateOf: number | null; // status DUPLICATE: id of the book whose passages stand in for this one
  ocrPages: number; // pages of the indexed text that came from OCR (scanned PDF pages); 0 = none
}

/** GET /api/books/{id}/details */
export interface BookDetails {
  book: Book;
  calibre: CalibreExtras | null; // null when Calibre's metadata.db can't be read
  duplicateOf: { id: number; title: string } | null;
  sections: { title: string; firstPage: number | null; passages: number }[]; // indexed outline, reading order
}

export interface CalibreExtras {
  description: string | null; // plain text, paragraphs separated by blank lines
  publisher: string | null;
  identifiers: Record<string, string>; // isbn, amazon, doi, …
  rating: number | null; // 0.5–5 stars
  addedAt: string | null;
  fileSizes: Record<string, number>; // format -> bytes
}

export interface Page<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}

export interface SourceChunk {
  // one retrieved passage
  ref: number; // 1-based citation number used in the answer text: [1], [2] ...
  chunkId: string;
  bookId: number;
  title: string;
  authors: string[];
  page: number | null; // 1-based page (PDF only)
  section: string | null; // nearest heading (EPUB etc.)
  text: string; // chunk text
  score: number; // higher = better; scale depends on scoreKind
  scoreKind: ScoreKind;
}

/** RERANK: cross-encoder relevance 0..1, comparable across queries. FUSED: weighted-RRF score, relative only. */
export type ScoreKind = "RERANK" | "FUSED";

export type IngestStage = "EXTRACTING" | "OCR" | "EMBEDDING";

/** GET/PUT /api/settings: server settings, shared by everyone using this server. */
export interface ServerSettings {
  parallelBooks: number; // books extracted at the same time while indexing
  parallelBooksDefault: number;
  parallelBooksMax: number;
}

export interface IngestStatus {
  running: boolean;
  stopRequested: boolean;
  startedAt: string | null;
  counts: Record<BookStatus, number>; // over the whole library
  totalBooks: number;
  totalChunks: number; // rows in vector store
  sessionProcessed: number; // books finished (indexed+failed+skipped) in the current/last run
  sessionChunks: number; // chunks embedded in the current/last run
  parallelBooks: number; // books extracted at the same time (Settings)
  chunksPerSecond: number; // passages embedded per second, rolling over the last ~60s
  etaSeconds: number | null; // from passages of books finished in the last ~10 min (embedded or reused)
  active: {
    bookId: number;
    libraryId: number;
    title: string;
    stage: IngestStage;
    chunks: number | null;
    ocr: { done: number; total: number } | null; // stage OCR: pages read so far
  }[];
  recentErrors: { bookId: number; libraryId: number; title: string; error: string; at: string }[]; // newest first, max 50
}

/** GET /api/libraries: the libraries the caller can see. */
export interface Library {
  id: number;
  name: string;
  path: string | null; // where it is on disk; shown to global admins only
  bookCount: number;
  role: LibraryRole; // the caller's role in it (at least VIEWER)
}

export interface SystemInfo {
  libraries: { name: string; path: string | null }[]; // the caller's libraries; path for global admins only
  ollamaUrl: string;
  ollamaReachable: boolean;
  chatModel: string;
  embeddingModel: string;
  embeddingDimensions: number;
  availableChatModels: string[]; // from ollama /api/tags, minus embedding models
  rerankEnabled: boolean; // server has a reranker configured
  rerankerUrl: string;
  rerankerReachable: boolean;
  rerankerModel: string | null; // the model llama.cpp was started with; null if unreachable or not reported
}

export interface SyncResult {
  added: number;
  updated: number;
  removed: number;
  total: number;
}

export interface TagCount {
  name: string;
  count: number;
}

export type BookSort = "title" | "author" | "publisher" | "pubdate" | "indexedAt" | "id";

export interface BookQuery {
  q?: string;
  status?: BookStatus;
  tag?: string;
  publisher?: string;
  libraryId?: number;
  page?: number;
  size?: number;
  sort?: BookSort | `-${BookSort}`; // "-": the opposite of the field's default order (newest first for dates)
}

export interface IngestStartRequest {
  bookIds?: number[];
  retryFailed?: boolean;
}

export interface SearchRequest {
  query: string;
  topK?: number; // default 8, max 50
  bookIds?: number[]; // restrict to these books
  tags?: string[]; // restrict to books having ANY of these tags
  libraryIds?: number[]; // restrict to these libraries; default: every configured library
  publishers?: string[]; // restrict to books from ANY of these publishers
  rerank?: boolean; // cross-encoder reranking; default on when the server has a reranker
  correct?: boolean; // spelling correction of words the library doesn't contain; default true
}

/** A word of the query as typed and as searched for. */
export interface SpellingCorrection {
  from: string;
  to: string;
  known: boolean; // the typed word does occur in the library, rarely
}

export interface SearchResponse {
  results: SourceChunk[];
  /** what was searched for instead, when misspelled words were corrected */
  correctedQuery?: string | null;
  corrections?: SpellingCorrection[];
}

export interface ChatHistoryMessage {
  role: "user" | "assistant";
  content: string;
}

export interface ChatRequest extends SearchRequest {
  history?: ChatHistoryMessage[]; // previous turns, oldest first
  model?: string; // override chat model (one of availableChatModels)
}

/** Parsed events of the POST /api/chat SSE stream. */
export type ChatEvent =
  | { type: "corrected"; correctedQuery: string }
  | { type: "sources"; sources: SourceChunk[] }
  | { type: "token"; t: string }
  | { type: "done"; elapsedMs: number }
  | { type: "error"; error: string };

export type VectorMethod = "pca" | "umap" | "tsne";
export type VectorLevel = "passages" | "books"; // one point per passage, or per book (its passages' average)

export interface VectorPoint {
  id: string;
  bookId: number;
  title: string;
  page: number | null;
  section: string | null;
  snippet: string;
  x: number;
  y: number;
  z: number | null; // 3-D layouts only
}

/**
 * A projection's tunable parameters: UMAP's neighbors/minDist/epochs, t-SNE's perplexity/iterations (PCA has
 * none). In a VectorSpace, the method's own as used (defaults filled in, capped for a small sample), the rest null.
 */
export type LayoutParams = {
  neighbors?: number | null;
  minDist?: number | null;
  epochs?: number | null;
  perplexity?: number | null;
  iterations?: number | null;
};

export interface VectorSpace {
  libraryId: number;
  level: VectorLevel;
  method: VectorMethod;
  dims: 2 | 3;
  seed: number;
  size: number;
  total: number; // passages (or books) with a vector in this library
  missingBookVectors: number; // books with passages but no book vector yet
  dimensions: number;
  explainedVariance: number[] | null;
  params: LayoutParams;
  elapsedMs: number; // how long computing it took (also when served from the cache)
  points: VectorPoint[];
  /** milliseconds per step of computing it, in order (sample, parse, pca, distances, umap/tsne) */
  timings: Record<string, number>;
  cached: boolean; // served from the server's cache
}

/** Where topics are clustered: the raw embeddings (cosine), or a UMAP reduction first (closer to BERTopic). */
export type ClusterSpace = "raw" | "umap";

/** A discovered topic (HDBSCAN cluster): keywords from class-based TF-IDF over its members' text. */
export interface Topic {
  id: number;
  label: string; // keywords joined for display, e.g. "distributed, consensus, raft"
  keywords: string[];
  size: number;
}

export interface TopicSpace {
  libraryId: number;
  level: VectorLevel;
  seed: number;
  size: number; // points in the sample that were clustered
  total: number; // passages (or books) with a vector in this library
  space: ClusterSpace;
  /** HDBSCAN's parameters as used (the defaults filled in) */
  minClusterSize: number;
  minSamples: number;
  elapsedMs: number;
  topics: Topic[];
  /** point id (matches VectorPoint.id for the same library/level/size/seed) -> topic id, or -1 for noise */
  pointTopics: Record<string, number>;
  /** milliseconds per step of computing it, in order (sample, parse, distances, hdbscan, labels) */
  timings: Record<string, number>;
  cached: boolean;
}

// ---- users, roles and API keys ------------------------------------------------------------------------------

/**
 * A user's role in one library, least to most; each includes the ones before it. VIEWER: browse, search, ask, read,
 * review and list books. CURATOR: also sync the library, (re)index its books and remove others' reviews of them.
 * ADMIN: also grant and revoke roles in the library. A global admin is ADMIN everywhere.
 */
export type LibraryRole = "NONE" | "VIEWER" | "CURATOR" | "ADMIN";

/** Roles that can be granted (PUT /api/libraries/{id}/members/{userId}); revoking is a DELETE. */
export type GrantableRole = "viewer" | "curator" | "admin";

/** `oidc`: sign-in through the identity provider. `single-user`: none configured, everyone is the local admin. */
export type AuthMode = "oidc" | "single-user";

export type ApiScope =
  | "library:read"
  | "library:curate"
  | "favorites:read"
  | "favorites:write"
  | "reviews:read"
  | "reviews:write";

/** GET /api/me (401 with `loginUrl` when signed out). */
export interface Me {
  id: number | null; // null for a system-wide API key
  displayName: string;
  email: string | null;
  admin: boolean; // global admin
  authMode: AuthMode;
  viaApiKey: boolean;
  scopes: ApiScope[];
  libraries: { id: number; name: string; role: LibraryRole }[]; // the libraries the caller can see
  canCurate: boolean; // curator (or more) in at least one library
  canManageMembers: boolean; // library admin in at least one library (or a global admin)
}

/** The body of a 401 from the web API. */
export interface UnauthorizedBody {
  error: string;
  loginUrl?: string; // e.g. /api/auth/login/oidc; navigate the browser there to sign in
}

/** GET /api/me/api-keys/scopes */
export interface ScopeInfo {
  scope: ApiScope;
  description: string;
  perUser: boolean; // acts on the owner's own data: not for system-wide keys
}

/** An API key as listed; never the key itself. */
export interface ApiKey {
  id: string; // UUID
  name: string;
  keyPrefix: string; // the key's first characters, e.g. "rlk_AbCd"
  scopes: ApiScope[];
  libraryIds: number[] | null; // null = every library the owner can see
  system: boolean; // system-wide (no owner)
  createdBy: string | null;
  createdAt: string;
  lastUsedAt: string | null;
  expiresAt: string | null;
}

/** POST /api/me/api-keys and POST /api/admin/api-keys */
export interface ApiKeyRequest {
  name: string;
  scopes: ApiScope[];
  libraryIds?: number[]; // omit for every library
  expiresAt?: string | null; // ISO instant; omitted/null = never
}

/** 201 response to creating a key: the only time `rawKey` is shown. */
export interface CreatedApiKey {
  apiKey: ApiKey;
  rawKey: string; // rlk_…
}

/** GET /api/admin/users: everyone who has signed in. */
export interface User {
  id: number;
  displayName: string;
  email: string | null;
  groups: string[];
  admin: boolean; // global admin, through the claim or granted in the app
  adminFromClaim: boolean;
  adminGranted: boolean;
  disabled: boolean;
  createdAt: string;
  lastLoginAt: string | null;
  granted: Record<string, LibraryRole>; // library id -> role granted in the app
  roles: Record<string, LibraryRole>; // library id -> effective role (grants, groups, default, admin)
}

/** PUT /api/admin/users/{id} (you can't disable or demote yourself: 400) */
export interface UserUpdate {
  adminGranted?: boolean;
  disabled?: boolean;
}

/** GET /api/libraries/{libraryId}/members: every user, with their role in that library. */
export interface Member {
  userId: number;
  displayName: string;
  email: string | null;
  granted: LibraryRole | null; // granted in the app for this library
  role: LibraryRole; // effective
  admin: boolean; // global admin
  disabled: boolean;
}

// ---- favorite lists -----------------------------------------------------------------------------------------

export interface FavoriteList {
  id: number;
  name: string;
  description: string | null;
  shared: boolean; // visible (read-only) to every signed-in user
  ownerId: number;
  ownerName: string;
  mine: boolean; // the caller owns it, and may change it
  bookCount: number; // books the caller can see
  createdAt: string;
  updatedAt: string;
  containsBook: boolean | null; // with GET /api/lists?bookId=: whether that book is on the list
}

/** GET /api/lists[?bookId=] */
export interface FavoriteLists {
  mine: FavoriteList[];
  shared: FavoriteList[]; // others' shared lists
}

export interface FavoriteListEntry {
  book: Book;
  note: string | null;
  addedAt: string;
}

/** GET /api/lists/{id} */
export interface FavoriteListDetails {
  list: FavoriteList;
  books: FavoriteListEntry[]; // newest first; books of libraries the caller can't see are left out
}

/** POST /api/lists, PUT /api/lists/{id} (fields left out stay as they are) */
export interface FavoriteListRequest {
  name?: string;
  description?: string;
  shared?: boolean;
  bookIds?: number[]; // only when creating a list: the books to start it with, first shown first
}

/** POST /api/lists/{id}/books */
export interface AddBooksResult {
  list: FavoriteList;
  added: number; // new on the list
  alreadyOn: number; // were on it already (and keep their note and place)
}

// ---- reviews ------------------------------------------------------------------------------------------------

export interface Review {
  id: number;
  bookId: number;
  bookTitle: string;
  userId: number;
  author: string;
  rating: number | null; // 1-5, or null for a review without one
  body: string;
  createdAt: string;
  updatedAt: string;
  mine: boolean;
}

/** GET /api/books/{id}/reviews */
export interface BookReviews {
  bookId: number;
  count: number;
  averageRating: number | null; // over the reviews that have a rating
  ratings: number;
  reviews: Review[]; // newest first, the caller's own included
  mine: Review | null;
  canModerate: boolean; // may remove others' reviews (curator of the book's library)
}

/** PUT /api/books/{id}/reviews/mine: replaces the caller's review; a rating, a text, or both. */
export interface ReviewRequest {
  rating: number | null;
  body: string;
}
