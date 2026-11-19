import { afterEach, beforeEach, describe, expect, test } from "bun:test";
import { api, ApiError, bookCoverUrl, onUnauthorized } from "./client";

const realFetch = globalThis.fetch;
let calls: { url: string; init: RequestInit }[] = [];

function respondWith(status: number, body: string, contentType = "application/json") {
  globalThis.fetch = (async (url: string | URL | Request, init?: RequestInit) => {
    calls.push({ url: String(url), init: init ?? {} });
    return new Response(body, { status, headers: { "content-type": contentType } });
  }) as unknown as typeof fetch;
}

const header = (i: number, name: string) => new Headers(calls[i]!.init.headers).get(name);

beforeEach(() => {
  calls = [];
  // the browser's cookies, as the client reads them
  Object.defineProperty(globalThis, "document", { value: { cookie: "theme=dark; XSRF-TOKEN=tok-123" }, configurable: true });
});

afterEach(() => {
  globalThis.fetch = realFetch;
  Reflect.deleteProperty(globalThis, "document");
});

describe("CSRF", () => {
  test("cover writes use multipart without overriding the browser boundary", async () => {
    respondWith(200, '{"book":{}}');
    await api.uploadBookCover(12, new File(["cover"], "cover.webp", { type: "image/webp" }));
    expect(calls[0]!.url).toBe("/api/books/12/cover");
    expect(calls[0]!.init.method).toBe("PUT");
    expect(calls[0]!.init.body).toBeInstanceOf(FormData);
    expect(header(0, "content-type")).toBeNull();
    expect(header(0, "X-XSRF-TOKEN")).toBe("tok-123");
  });

  test("format uploads and removals use the format routes", async () => {
    respondWith(200, '{"book":{}}');
    await api.uploadBookFormat(12, new File(["ebook"], "book.epub"));
    respondWith(200, '{"book":{}}');
    await api.uploadBookFormat(12, new File(["ebook"], "book.epub"), true);
    await api.removeBookFormat(12, "PDF");
    expect(calls.map((call) => [call.url, call.init.method])).toEqual([
      ["/api/books/12/formats?replace=false", "PUT"],
      ["/api/books/12/formats?replace=true", "PUT"],
      ["/api/books/12/formats/PDF", "DELETE"],
    ]);
    expect(calls.slice(0, 2).every((call) => call.init.body instanceof FormData)).toBe(true);
    expect(header(0, "content-type")).toBeNull();
    expect([0, 1, 2].map((i) => header(i, "X-XSRF-TOKEN"))).toEqual(["tok-123", "tok-123", "tok-123"]);
  });

  test("book uploads send all files as multipart to the selected library", async () => {
    respondWith(200, '{"items":[]}');
    const files = [new File(["ebook"], "book.epub"), new File(["pdf"], "book.pdf")];
    await api.uploadBooks(7, files);
    expect(calls[0]!.url).toBe("/api/libraries/7/books");
    expect(calls[0]!.init.method).toBe("POST");
    expect(calls[0]!.init.body).toBeInstanceOf(FormData);
    expect((calls[0]!.init.body as FormData).getAll("files")).toHaveLength(2);
    expect(header(0, "content-type")).toBeNull();
    expect(header(0, "X-XSRF-TOKEN")).toBe("tok-123");
  });

  test("duplicate upload decisions send the selected action and matching book", async () => {
    respondWith(200, '{"choice":"ADD_FORMAT","book":{"id":12}}');
    await api.decideBookUpload(7, "a4d8c925-11ae-41df-b9e0-cb48bc4ed800", "ADD_FORMAT", 12);
    expect(calls[0]!.url).toBe("/api/libraries/7/books/a4d8c925-11ae-41df-b9e0-cb48bc4ed800/decision");
    expect(calls[0]!.init.method).toBe("POST");
    expect(JSON.parse(String(calls[0]!.init.body))).toEqual({ choice: "ADD_FORMAT", bookId: 12 });
    expect(header(0, "X-XSRF-TOKEN")).toBe("tok-123");
  });

  test("cover URLs are versioned using Calibre's last modified value", () => {
    expect(bookCoverUrl(12, "2024-01-02T03:04:05Z")).toBe("/api/books/12/cover?v=2024-01-02T03%3A04%3A05Z");
  });

  test("removing and extracting a cover use the protected cover routes", async () => {
    respondWith(200, '{"book":{}}');
    await api.removeBookCover(12);
    respondWith(200, '{"book":{}}');
    await api.useBookCoverFromFormat(12, "PDF");
    expect(calls.map((call) => [call.url, call.init.method])).toEqual([
      ["/api/books/12/cover", "DELETE"],
      ["/api/books/12/cover/from-format?format=PDF", "POST"],
    ]);
    expect([0, 1].map((i) => header(i, "X-XSRF-TOKEN"))).toEqual(["tok-123", "tok-123"]);
  });

  test("activity filters are encoded in a read-only request", async () => {
    respondWith(200, '{"items":[],"total":0,"page":1,"size":25,"actors":[]}');
    await api.activity({ libraryId: 7, user: "Alice, via key 'tagger'", action: "EDIT_METADATA", page: 1, size: 25 });
    expect(calls[0]!.url).toBe("/api/activity?libraryId=7&user=Alice%2C+via+key+%27tagger%27&action=EDIT_METADATA&page=1&size=25");
    expect(calls[0]!.init.method).toBeUndefined();
    expect(header(0, "X-XSRF-TOKEN")).toBeNull();
  });

  test("pausing library editing uses the session-protected endpoint", async () => {
    respondWith(200, '{"editingPaused":true}');
    await api.setLibraryEditingPaused(7, true);
    expect(calls[0]!.url).toBe("/api/libraries/7/editing");
    expect(calls[0]!.init.method).toBe("PUT");
    expect(JSON.parse(String(calls[0]!.init.body))).toEqual({ paused: true });
    expect(header(0, "X-XSRF-TOKEN")).toBe("tok-123");
  });

  test("writes carry the token from the cookie, reads don't", async () => {
    respondWith(201, '{"id":1}');
    await api.createList({ name: "x" });
    await api.revokeApiKey("k1");
    await api.grantMember(1, 2, "viewer");
    respondWith(200, '{"mine":[],"shared":[]}');
    await api.lists();
    expect(calls.map((c) => c.init.method ?? "GET")).toEqual(["POST", "DELETE", "PUT", "GET"]);
    expect([0, 1, 2].map((i) => header(i, "X-XSRF-TOKEN"))).toEqual(["tok-123", "tok-123", "tok-123"]);
    expect(header(3, "X-XSRF-TOKEN")).toBeNull();
    // the other headers survive
    expect(header(0, "content-type")).toBe("application/json");
    expect(header(3, "accept")).toBe("application/json");
  });

  test("the chat stream's POST carries it too", async () => {
    respondWith(200, 'event: done\ndata: {"elapsedMs":5}\n\n', "text/event-stream");
    const events = [];
    for await (const ev of api.chat({ query: "q" })) events.push(ev);
    expect(events).toEqual([{ type: "done", elapsedMs: 5 }]);
    expect(header(0, "X-XSRF-TOKEN")).toBe("tok-123");
    expect(header(0, "accept")).toBe("text/event-stream");
  });
});

describe("401", () => {
  test("an ended session: ApiError with where to sign in, and the listener hears of it", async () => {
    const heard: ApiError[] = [];
    const off = onUnauthorized((e) => heard.push(e));
    respondWith(401, '{"error":"sign in first","loginUrl":"/api/auth/login/oidc"}');
    const err = await api.books().catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(401);
    expect((err as ApiError).loginUrl).toBe("/api/auth/login/oidc");
    expect(heard).toHaveLength(1);

    // GET /api/me is how the listener finds out what to show: it isn't told about its own check
    await api.me().catch(() => undefined);
    expect(heard).toHaveLength(1);

    off();
    await api.books().catch(() => undefined);
    expect(heard).toHaveLength(1);
  });

  test("other errors don't count", async () => {
    const heard: ApiError[] = [];
    const off = onUnauthorized((e) => heard.push(e));
    respondWith(403, '{"error":"you may not do this"}');
    const err = (await api.syncLibrary(3).catch((e: unknown) => e)) as ApiError;
    expect(err.status).toBe(403);
    expect(err.message).toBe("you may not do this");
    expect(err.loginUrl).toBeNull();
    expect(calls[0]!.url).toBe("/api/library/sync?libraryId=3");
    expect(heard).toHaveLength(0);
    off();
  });
});

describe("structured API errors", () => {
  test("preserves conflict details on ApiError", async () => {
    respondWith(409, '{"error":"metadata changed","details":{"conflicts":{"tags":["theirs"]}}}');
    const err = (await api.books().catch((e: unknown) => e)) as ApiError;
    expect(err.status).toBe(409);
    expect(err.details).toEqual({ conflicts: { tags: ["theirs"] } });
  });
});
