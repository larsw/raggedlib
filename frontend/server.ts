import path from "node:path";
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
  // To the browser this is a same-origin request, so the backend's CORS check must not apply.
  // Forwarding Origin would make every POST fail (403) unless the page was opened via exactly
  // the origin in the backend's allow-list (e.g. 127.0.0.1:3000 or a LAN hostname would break).
  const forwardHeaders = stripHeaders(req.headers);
  forwardHeaders.delete("origin");
  // Where the browser is: the backend builds its OIDC redirect URI (/api/auth/callback/...) from these, so sign-in
  // comes back here, and the session cookie stays on this origin. It trusts them from private addresses only.
  forwardHeaders.set("x-forwarded-host", url.host);
  forwardHeaders.set("x-forwarded-proto", url.protocol.replace(/:$/, ""));
  forwardHeaders.set("x-forwarded-port", url.port || (url.protocol === "https:" ? "443" : "80"));
  const clientIp = server.requestIP(req)?.address;
  if (clientIp) forwardHeaders.set("x-forwarded-for", clientIp);

  let upstream: Response;
  try {
    upstream = await fetch(target, {
      method: req.method,
      headers: forwardHeaders,
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

// The ebook reader's engine, foliate-js, is plain ES modules that import one another lazily (a format's parser only
// for books in that format, PDF.js only for PDFs). It is served as-is rather than bundled, because the bundler would
// inline all of it, PDF.js included, into the app's single script. See src/components/reader/foliate.ts.
const FOLIATE_PREFIX = "/vendor/foliate-js/";
const FOLIATE_DIR = path.join(import.meta.dir, "node_modules", "foliate-js");
const FOLIATE_TYPES: Record<string, string> = {
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
};

async function foliate(req: Request): Promise<Response> {
  const rel = decodeURIComponent(new URL(req.url).pathname.slice(FOLIATE_PREFIX.length));
  const file = path.join(FOLIATE_DIR, rel);
  const type = FOLIATE_TYPES[path.extname(file)];
  if (!type || !file.startsWith(FOLIATE_DIR + path.sep) || !(await Bun.file(file).exists())) {
    return new Response("not found", { status: 404 });
  }
  // revalidated on every load, so a new foliate-js version is picked up right away
  return new Response(Bun.file(file), { headers: { "content-type": type, "cache-control": "no-cache" } });
}

const server = Bun.serve({
  hostname: HOST,
  port: PORT,
  routes: {
    "/healthz": new Response("ok", { headers: { "content-type": "text/plain" } }),
    "/api/*": proxy,
    // the MCP endpoint, so agents can use the UI's origin too (API keys only; see Account → API keys)
    "/mcp": proxy,
    [`${FOLIATE_PREFIX}*`]: foliate,
    "/*": index,
  },
  development: !isProduction && { hmr: true, console: true },
});

console.log(
  `raggedlib frontend on ${server.url} (${isProduction ? "production" : "development"}), proxying /api and /mcp -> ${BACKEND_URL}`,
);
