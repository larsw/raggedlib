import { csrfHeaders } from "../lib/csrf";
import { readSseStream } from "./sse";
import type {
  ApiKey,
  ApiKeyRequest,
  Book,
  BookDetails,
  BookQuery,
  BookReviews,
  ClusterSpace,
  CreatedApiKey,
  FavoriteList,
  FavoriteListDetails,
  FavoriteListRequest,
  FavoriteLists,
  GrantableRole,
  Member,
  Me,
  Review,
  ReviewRequest,
  ScopeInfo,
  User,
  UserUpdate,
  VectorLevel,
  VectorMethod,
  VectorSpace,
  TopicSpace,
  ChatEvent,
  ChatRequest,
  IngestStartRequest,
  IngestStatus,
  Library,
  Page,
  SearchRequest,
  SearchResponse,
  ServerSettings,
  SourceChunk,
  SyncResult,
  SystemInfo,
  TagCount,
} from "./types";

const BASE = "/api";

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    /** True when the backend could not be reached at all (network error or proxy 502). */
    readonly unreachable: boolean = false,
    /** 401: where to send the browser to sign in. */
    readonly loginUrl: string | null = null,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

export function isUnauthorized(err: unknown): err is ApiError {
  return err instanceof ApiError && err.status === 401;
}

let unauthorizedListener: ((err: ApiError) => void) | null = null;

/**
 * Called whenever an API call comes back 401 (the session ended: expired, signed out elsewhere, or the account was
 * disabled), except GET /api/me, which is how the listener finds out what to show. Returns an unsubscribe function.
 */
export function onUnauthorized(listener: (err: ApiError) => void): () => void {
  unauthorizedListener = listener;
  return () => {
    if (unauthorizedListener === listener) unauthorizedListener = null;
  };
}

function documentCookies(): string {
  return typeof document === "undefined" ? "" : document.cookie;
}

export function isAbortError(err: unknown): boolean {
  return err instanceof DOMException && err.name === "AbortError";
}

export function errorMessage(err: unknown): string {
  if (err instanceof Error) return err.message;
  return String(err);
}

async function errorFromResponse(res: Response): Promise<ApiError> {
  let message = `${res.status} ${res.statusText}`.trim();
  let loginUrl: string | null = null;
  try {
    const text = await res.text();
    try {
      const body = JSON.parse(text) as { error?: unknown; loginUrl?: unknown };
      if (body && typeof body.error === "string") message = body.error;
      if (body && typeof body.loginUrl === "string") loginUrl = body.loginUrl;
    } catch {
      if (text && text.length < 300) message = text;
    }
  } catch {
    /* ignore */
  }
  const unreachable = res.status === 502 && message === "backend unreachable";
  return new ApiError(message, res.status, unreachable, loginUrl);
}

async function send(path: string, init?: RequestInit): Promise<Response> {
  const headers = new Headers(init?.headers);
  for (const [name, value] of Object.entries(csrfHeaders(init?.method, documentCookies()))) headers.set(name, value);
  let res: Response;
  try {
    res = await fetch(BASE + path, { ...init, headers });
  } catch (err) {
    if (isAbortError(err)) throw err;
    throw new ApiError("backend unreachable", 0, true);
  }
  if (!res.ok) {
    const err = await errorFromResponse(res);
    if (err.status === 401 && path !== "/me") unauthorizedListener?.(err);
    throw err;
  }
  return res;
}

async function getJson<T>(path: string, signal?: AbortSignal): Promise<T> {
  const res = await send(path, { signal, headers: { accept: "application/json" } });
  return (await res.json()) as T;
}

async function postJson<T>(path: string, body?: unknown, signal?: AbortSignal, method = "POST"): Promise<T> {
  const res = await send(path, {
    method,
    signal,
    headers: { accept: "application/json", ...(body !== undefined ? { "content-type": "application/json" } : {}) },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

function queryString(params: Record<string, string | number | undefined | null>): string {
  const usp = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null && v !== "") usp.set(k, String(v));
  }
  const s = usp.toString();
  return s ? `?${s}` : "";
}

export const bookCoverUrl = (id: number) => `${BASE}/books/${id}/cover`;

/** Link to the book file; for PDFs, `#page=N` makes the browser viewer jump to the page. */
export function bookFileUrl(id: number, format: string, page?: number | null): string {
  const url = `${BASE}/books/${id}/file?format=${encodeURIComponent(format)}`;
  return page ? `${url}#page=${page}` : url;
}

const putJson = <T>(path: string, body?: unknown) => postJson<T>(path, body, undefined, "PUT");
const deleteJson = <T = void>(path: string) => postJson<T>(path, undefined, undefined, "DELETE");

export const api = {
  system: (signal?: AbortSignal) => getJson<SystemInfo>("/system", signal),

  /** Who is signed in; 401 (ApiError with `loginUrl`) when no one is. */
  me: (signal?: AbortSignal) => getJson<Me>("/me", signal),
  logout: () => postJson<void>("/auth/logout"),

  libraries: (signal?: AbortSignal) => getJson<Library[]>("/libraries", signal),

  /** One library, or (without an id) every library the caller curates. */
  syncLibrary: (libraryId?: number) => postJson<SyncResult>(`/library/sync${queryString({ libraryId })}`),

  // personal API keys (browser session only)
  apiKeyScopes: (signal?: AbortSignal) => getJson<ScopeInfo[]>("/me/api-keys/scopes", signal),
  apiKeys: (signal?: AbortSignal) => getJson<ApiKey[]>("/me/api-keys", signal),
  createApiKey: (body: ApiKeyRequest) => postJson<CreatedApiKey>("/me/api-keys", body),
  revokeApiKey: (id: string) => deleteJson(`/me/api-keys/${encodeURIComponent(id)}`),

  // global admins (browser session only)
  adminUsers: (signal?: AbortSignal) => getJson<User[]>("/admin/users", signal),
  updateAdminUser: (id: number, body: UserUpdate) => putJson<User>(`/admin/users/${id}`, body),
  systemApiKeys: (signal?: AbortSignal) => getJson<ApiKey[]>("/admin/api-keys", signal),
  createSystemApiKey: (body: ApiKeyRequest) => postJson<CreatedApiKey>("/admin/api-keys", body),
  revokeSystemApiKey: (id: string) => deleteJson(`/admin/api-keys/${encodeURIComponent(id)}`),

  // library admins (and global admins)
  libraryMembers: (libraryId: number, signal?: AbortSignal) => getJson<Member[]>(`/libraries/${libraryId}/members`, signal),
  grantMember: (libraryId: number, userId: number, role: GrantableRole) =>
    putJson<Member>(`/libraries/${libraryId}/members/${userId}`, { role }),
  revokeMember: (libraryId: number, userId: number) => deleteJson<Member>(`/libraries/${libraryId}/members/${userId}`),

  // favorite lists
  lists: (bookId?: number, signal?: AbortSignal) => getJson<FavoriteLists>(`/lists${queryString({ bookId })}`, signal),
  list: (id: number, signal?: AbortSignal) => getJson<FavoriteListDetails>(`/lists/${id}`, signal),
  createList: (body: FavoriteListRequest) => postJson<FavoriteList>("/lists", body),
  updateList: (id: number, body: FavoriteListRequest) => putJson<FavoriteList>(`/lists/${id}`, body),
  deleteList: (id: number) => deleteJson(`/lists/${id}`),
  /** Adds a book to one of the caller's lists (or, if it's there, sets its note when one is given). */
  addToList: (id: number, bookId: number, note?: string) =>
    putJson<FavoriteList>(`/lists/${id}/books/${bookId}`, note !== undefined ? { note } : {}),
  removeFromList: (id: number, bookId: number) => deleteJson<FavoriteList>(`/lists/${id}/books/${bookId}`),

  // reviews
  bookReviews: (bookId: number, signal?: AbortSignal) => getJson<BookReviews>(`/books/${bookId}/reviews`, signal),
  writeReview: (bookId: number, body: ReviewRequest) => putJson<Review>(`/books/${bookId}/reviews/mine`, body),
  deleteMyReview: (bookId: number) => deleteJson(`/books/${bookId}/reviews/mine`),
  /** Someone else's review: needs curator in the book's library. */
  deleteReview: (reviewId: number) => deleteJson(`/reviews/${reviewId}`),
  myReviews: (signal?: AbortSignal) => getJson<Review[]>("/me/reviews", signal),

  books: (q: BookQuery = {}, signal?: AbortSignal) =>
    getJson<Page<Book>>(
      `/books${queryString({ q: q.q, status: q.status, tag: q.tag, publisher: q.publisher, libraryId: q.libraryId, page: q.page, size: q.size, sort: q.sort })}`,
      signal,
    ),

  book: (id: number, signal?: AbortSignal) => getJson<Book>(`/books/${id}`, signal),

  bookDetails: (id: number, signal?: AbortSignal) => getJson<BookDetails>(`/books/${id}/details`, signal),

  reindex: (id: number) => postJson<Book>(`/books/${id}/reindex`),

  /** The book file itself, for the in-app reader. */
  bookFile: async (id: number, format: string, signal?: AbortSignal) =>
    (await send(`/books/${id}/file?format=${encodeURIComponent(format)}`, { signal })).blob(),

  tags: (libraryId?: number, signal?: AbortSignal) => getJson<TagCount[]>(`/tags${queryString({ libraryId })}`, signal),
  publishers: (libraryId?: number, signal?: AbortSignal) =>
    getJson<TagCount[]>(`/publishers${queryString({ libraryId })}`, signal),

  ingestStart: (body?: IngestStartRequest) => postJson<IngestStatus>("/ingest/start", body ?? {}),
  /** `force`: interrupt books currently being extracted (killing e.g. OCR) and put them back to PENDING, instead of waiting for them to finish. */
  ingestStop: (force = false) => postJson<IngestStatus>(`/ingest/stop${queryString({ force: force ? "true" : undefined })}`),
  ingestStatus: (signal?: AbortSignal) => getJson<IngestStatus>("/ingest/status", signal),

  settings: (signal?: AbortSignal) => getJson<ServerSettings>("/settings", signal),
  /** Global admins only. */
  updateSettings: (body: Partial<Pick<ServerSettings, "parallelBooks">>) => putJson<ServerSettings>("/settings", body),
  vectors: (
    q: { libraryId: number; level: VectorLevel; method: VectorMethod; size: number; dims: 2 | 3; seed?: number },
    signal?: AbortSignal,
  ) => getJson<VectorSpace>(`/vectors${queryString(q)}`, signal),
  vectorTopics: (
    q: { libraryId: number; level: VectorLevel; size: number; space: ClusterSpace; seed?: number },
    signal?: AbortSignal,
  ) => getJson<TopicSpace>(`/vectors/topics${queryString(q)}`, signal),
  computeBookVectors: (libraryId?: number) =>
    postJson<{ computed: number; missing: number }>(`/vectors/book-vectors${queryString({ libraryId })}`),
  search: (req: SearchRequest, signal?: AbortSignal) => postJson<SearchResponse>("/search", req, signal),

  /**
   * POST /api/chat and yield parsed stream events (corrected? -> sources -> token* -> done | error).
   * Throws ApiError for non-2xx responses; the generator ends after done/error.
   */
  async *chat(req: ChatRequest, signal?: AbortSignal): AsyncGenerator<ChatEvent> {
    const res = await send("/chat", {
      method: "POST",
      signal,
      headers: { accept: "text/event-stream", "content-type": "application/json" },
      body: JSON.stringify(req),
    });
    if (!res.body) throw new ApiError("empty response body", res.status);
    for await (const ev of readSseStream(res.body, signal)) {
      const parsed = parseChatEvent(ev.event, ev.data);
      if (!parsed) continue;
      yield parsed;
      if (parsed.type === "done" || parsed.type === "error") return;
    }
  },
};

/** Maps a raw SSE event of the chat stream to a typed ChatEvent (null for unknown events). */
export function parseChatEvent(event: string, data: string): ChatEvent | null {
  let payload: unknown;
  try {
    payload = JSON.parse(data);
  } catch {
    if (event === "error") return { type: "error", error: data || "unknown error" };
    return null;
  }
  const obj = (payload ?? {}) as Record<string, unknown>;
  switch (event) {
    case "corrected":
      return typeof obj.correctedQuery === "string" ? { type: "corrected", correctedQuery: obj.correctedQuery } : null;
    case "sources":
      return { type: "sources", sources: Array.isArray(payload) ? (payload as SourceChunk[]) : [] };
    case "token":
      return typeof obj.t === "string" ? { type: "token", t: obj.t } : null;
    case "done":
      return { type: "done", elapsedMs: typeof obj.elapsedMs === "number" ? obj.elapsedMs : 0 };
    case "error":
      return { type: "error", error: typeof obj.error === "string" ? obj.error : "unknown error" };
    default:
      return null;
  }
}
