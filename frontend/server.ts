import index from "./index.html";

const BACKEND_URL = (process.env.BACKEND_URL ?? "http://localhost:8080").replace(/\/+$/, "");
const PORT = Number(process.env.PORT ?? 3000);
// Not HOSTNAME: Docker sets that to the container id.
const HOST = process.env.HOST ?? "0.0.0.0";
const isProduction = process.env.NODE_ENV === "production";

// Hop-by-hop headers must not be forwarded by a proxy (RFC 9110 §7.6.1).
const HOP_BY_HOP = [
  "connection",
  "keep-alive",
  "proxy-authenticate",
  "proxy-authorization",
  "te",
  "trailer",
  "transfer-encoding",
  "upgrade",
  "host",
];

function stripHeaders(source: Headers): Headers {
  const headers = new Headers(source);
  for (const name of HOP_BY_HOP) headers.delete(name);
  return headers;
}

function unreachable(): Response {
  return Response.json({ error: "backend unreachable" }, { status: 502 });
}

async function proxy(req: Request, server: Bun.Server<undefined>): Promise<Response> {
  // Chat streams can sit idle for a long time while the model loads; never time them out here.
  server.timeout(req, 0);

  const url = new URL(req.url);
  const target = BACKEND_URL + url.pathname + url.search;
  const hasBody = req.method !== "GET" && req.method !== "HEAD";

  let upstream: Response;
  try {
    upstream = await fetch(target, {
      method: req.method,
      headers: stripHeaders(req.headers),
      body: hasBody ? await req.arrayBuffer() : undefined,
      redirect: "manual",
      decompress: false, // pass the body through byte-for-byte, keep Content-Encoding intact
      signal: req.signal, // client went away -> abort upstream (stops LLM generation)
    });
  } catch (err) {
    if (req.signal.aborted) return new Response(null, { status: 499 });
    console.warn(`[proxy] ${req.method} ${target} failed: ${(err as Error).message}`);
    return unreachable();
  }

  const headers = stripHeaders(upstream.headers);
  if ((headers.get("content-type") ?? "").startsWith("text/event-stream")) {
    headers.set("cache-control", "no-cache");
    headers.set("x-accel-buffering", "no");
  }
  // Streaming the body through untouched keeps SSE incremental.
  return new Response(upstream.body, {
    status: upstream.status,
    statusText: upstream.statusText,
    headers,
  });
}

const server = Bun.serve({
  hostname: HOST,
  port: PORT,
  routes: {
    "/healthz": new Response("ok", { headers: { "content-type": "text/plain" } }),
    "/api/*": proxy,
    "/*": index,
  },
  development: !isProduction && { hmr: true, console: true },
});

console.log(
  `raggedlib frontend on ${server.url} (${isProduction ? "production" : "development"}), proxying /api -> ${BACKEND_URL}`,
);
