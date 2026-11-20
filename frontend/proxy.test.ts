import { afterEach, expect, test } from "bun:test";
import { maxRequestBodySize, parseDataSize } from "./limits";
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

test("reads the request limit the way Spring writes it, with room for multipart framing", () => {
  expect(parseDataSize("2GB")).toBe(2 * 1024 ** 3);
  expect(parseDataSize("500mb")).toBe(500 * 1024 ** 2);
  expect(parseDataSize("1048576")).toBe(1024 ** 2);
  expect(() => parseDataSize("lots")).toThrow();
  expect(maxRequestBodySize({})).toBe(2 * 1024 ** 3 + 1024 ** 2);
  expect(maxRequestBodySize({ UPLOAD_MAX_REQUEST_SIZE: "3GB" })).toBe(3 * 1024 ** 3 + 1024 ** 2);
  expect(maxRequestBodySize({ RAGGEDLIB_UPLOAD_MAX_REQUEST_SIZE: "1GB" })).toBe(1024 ** 3 + 1024 ** 2);
});

// Bun refuses bodies over 128 MB unless maxRequestBodySize says otherwise; a book of a few hundred MB must get through.
test("a server with the upload limit passes a body larger than Bun's default limit through the proxy", async () => {
  const size = 129 * 1024 ** 2;
  const chunk = new Uint8Array(1024 ** 2);
  const backend = Bun.serve({
    port: 0,
    maxRequestBodySize: maxRequestBodySize({}),
    async fetch(request) {
      let received = 0;
      for await (const part of request.body!) received += part.byteLength;
      return new Response(String(received));
    },
  });
  const proxy = createProxy(backend.url.toString());
  const frontend = Bun.serve({
    port: 0,
    maxRequestBodySize: maxRequestBodySize({}),
    fetch: (request, server) => proxy(request, server),
  });
  try {
    let sent = 0;
    const body = new ReadableStream<Uint8Array>({
      pull(controller) {
        if (sent >= size) return controller.close();
        sent += chunk.byteLength;
        controller.enqueue(chunk);
      },
    });
    const response = await fetch(new URL("/api/libraries/7/books", frontend.url), {
      method: "POST",
      headers: { "content-type": "application/octet-stream", "content-length": String(size) },
      body,
    });
    expect(response.status).toBe(200);
    expect(await response.text()).toBe(String(size));
  } finally {
    frontend.stop(true);
    backend.stop(true);
  }
}, 60_000);
