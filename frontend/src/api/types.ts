// Copied from docs/API.md — the contract between backend and frontend. Keep in sync.

export type BookStatus = "PENDING" | "EXTRACTING" | "EMBEDDING" | "INDEXED" | "FAILED" | "SKIPPED";

export const BOOK_STATUSES: readonly BookStatus[] = [
  "PENDING",
  "EXTRACTING",
  "EMBEDDING",
  "INDEXED",
  "FAILED",
  "SKIPPED",
];

export interface Book {
  id: number; // calibre id
  title: string;
  authors: string[];
  tags: string[];
  series: string | null;
  seriesIndex: number | null;
  languages: string[];
  pubdate: string | null;
  formats: string[]; // upper-case extensions available, e.g. ["PDF","EPUB"]
  sourceFormat: string | null; // format chosen for indexing, e.g. "EPUB"
  hasCover: boolean;
  status: BookStatus;
  chunkCount: number;
  error: string | null; // last failure message (status FAILED/SKIPPED)
  indexedAt: string | null;
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

export type IngestStage = "EXTRACTING" | "EMBEDDING";

export interface IngestStatus {
  running: boolean;
  stopRequested: boolean;
  startedAt: string | null;
  counts: Record<BookStatus, number>; // over the whole library
  totalBooks: number;
  totalChunks: number; // rows in vector store
  sessionProcessed: number; // books finished (indexed+failed+skipped) in the current/last run
  sessionChunks: number; // chunks embedded in the current/last run
  chunksPerSecond: number; // rolling rate over the last ~60s
  etaSeconds: number | null;
  active: { bookId: number; title: string; stage: IngestStage; chunks: number | null }[];
  recentErrors: { bookId: number; title: string; error: string; at: string }[]; // newest first, max 50
}

export interface SystemInfo {
  libraryPath: string;
  ollamaUrl: string;
  ollamaReachable: boolean;
  chatModel: string;
  embeddingModel: string;
  embeddingDimensions: number;
  availableChatModels: string[]; // from ollama /api/tags, minus embedding models
  rerankEnabled: boolean; // server has a reranker configured
  rerankerUrl: string;
  rerankerReachable: boolean;
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

export type BookSort = "title" | "author" | "pubdate" | "indexedAt" | "id";

export interface BookQuery {
  q?: string;
  status?: BookStatus;
  tag?: string;
  page?: number;
  size?: number;
  sort?: BookSort;
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
  rerank?: boolean; // cross-encoder reranking; default on when the server has a reranker
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
  | { type: "sources"; sources: SourceChunk[] }
  | { type: "token"; t: string }
  | { type: "done"; elapsedMs: number }
  | { type: "error"; error: string };
