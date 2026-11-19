import { afterEach, expect, test } from "bun:test";
import { createProxy } from "./proxy";

const originalFetch = globalThis.fetch;

afterEach(() => {
  globalThis.fetch = originalFetch;
});

test("forwards request bodies as a stream without reading them into memory first", async () => {
  let pulls = 0;
  const body = new ReadableStream<Uint8Array>({
    pull(controller) {
      pulls++;
      controller.enqueue(new Uint8Array([1, 2, 3]));
      controller.close();
    },
  }, { highWaterMark: 0 });
  const req = {
    url: "http://frontend/api/libraries/7/books",
    method: "POST",
    headers: new Headers({ "content-type": "multipart/form-data; boundary=probe" }),
    body,
    signal: new AbortController().signal,
  } as Request;
  let forwarded: RequestInit | undefined;
  globalThis.fetch = (async (_input: string | URL | Request, init?: RequestInit) => {
    forwarded = init;
    return new Response("ok");
  }) as unknown as typeof fetch;
  const server = {
    timeout: () => {},
    requestIP: () => null,
  } as unknown as Bun.Server<undefined>;

  const response = await createProxy("http://backend/")(req, server);

  expect(response.status).toBe(200);
  expect(forwarded?.body).toBe(req.body);
  expect(pulls).toBe(0);
});

test("streams a request body through Bun fetch to the backend", async () => {
  let upstreamStarted = false;
  let eagerlyConsumed = false;
  let chunksProduced = 0;
  const backend = Bun.serve({
    port: 0,
    async fetch(request) {
      upstreamStarted = true;
      const body = await request.arrayBuffer();
      return new Response(String(body.byteLength));
    },
  });
  const body = new ReadableStream<Uint8Array>({
    pull(controller) {
      if (chunksProduced > 0 && !upstreamStarted) {
        eagerlyConsumed = true;
        controller.error(new Error("proxy consumed the body before opening the upstream request"));
        return;
      }
      if (chunksProduced === 3) {
        controller.close();
        return;
      }
      chunksProduced++;
      controller.enqueue(new Uint8Array(64 * 1024));
    },
  }, { highWaterMark: 0 });
  const req = {
    url: "http://frontend/api/libraries/7/books",
    method: "POST",
    headers: new Headers({ "content-type": "application/octet-stream" }),
    body,
    signal: new AbortController().signal,
  } as Request;
  const server = {
    timeout: () => {},
    requestIP: () => null,
  } as unknown as Bun.Server<undefined>;

  try {
    const response = await createProxy(backend.url.toString())(req, server);
    expect(response.status).toBe(200);
    expect(await response.text()).toBe(String(3 * 64 * 1024));
    expect(eagerlyConsumed).toBe(false);
  } finally {
    backend.stop(true);
  }
});
