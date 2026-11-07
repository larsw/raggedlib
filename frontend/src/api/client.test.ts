import { afterEach, beforeEach, describe, expect, test } from "bun:test";
import { api, ApiError, onUnauthorized } from "./client";

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
