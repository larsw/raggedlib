import { readSseStream } from "./sse";
import type {
  Book,
  BookDetails,
  BookQuery,
  ChatEvent,
  ChatRequest,
  IngestStartRequest,
  IngestStatus,
  Page,
  SearchRequest,
  SearchResponse,
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
  ) {
    super(message);
    this.name = "ApiError";
  }
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
  try {
    const text = await res.text();
    try {
      const body = JSON.parse(text) as { error?: unknown };
      if (body && typeof body.error === "string") message = body.error;
    } catch {
      if (text && text.length < 300) message = text;
    }
  } catch {
    /* ignore */
  }
  const unreachable = res.status === 502 && message === "backend unreachable";
  return new ApiError(message, res.status, unreachable);
}

async function send(path: string, init?: RequestInit): Promise<Response> {
  let res: Response;
  try {
    res = await fetch(BASE + path, init);
  } catch (err) {
    if (isAbortError(err)) throw err;
    throw new ApiError("backend unreachable", 0, true);
  }
  if (!res.ok) throw await errorFromResponse(res);
  return res;
}

async function getJson<T>(path: string, signal?: AbortSignal): Promise<T> {
  const res = await send(path, { signal, headers: { accept: "application/json" } });
  return (await res.json()) as T;
}

async function postJson<T>(path: string, body?: unknown, signal?: AbortSignal): Promise<T> {
  const res = await send(path, {
    method: "POST",
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

export const api = {
  system: (signal?: AbortSignal) => getJson<SystemInfo>("/system", signal),

  syncLibrary: () => postJson<SyncResult>("/library/sync"),

  books: (q: BookQuery = {}, signal?: AbortSignal) =>
    getJson<Page<Book>>(
      `/books${queryString({ q: q.q, status: q.status, tag: q.tag, page: q.page, size: q.size, sort: q.sort })}`,
      signal,
    ),

  book: (id: number, signal?: AbortSignal) => getJson<Book>(`/books/${id}`, signal),

  bookDetails: (id: number, signal?: AbortSignal) => getJson<BookDetails>(`/books/${id}/details`, signal),

  reindex: (id: number) => postJson<Book>(`/books/${id}/reindex`),

  tags: (signal?: AbortSignal) => getJson<TagCount[]>("/tags", signal),

  ingestStart: (body?: IngestStartRequest) => postJson<IngestStatus>("/ingest/start", body ?? {}),
  ingestStop: () => postJson<IngestStatus>("/ingest/stop"),
  ingestStatus: (signal?: AbortSignal) => getJson<IngestStatus>("/ingest/status", signal),

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
