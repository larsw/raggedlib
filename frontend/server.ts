import path from "node:path";
import index from "./index.html";
import { maxRequestBodySize } from "./limits";
import { createProxy } from "./proxy";

const BACKEND_URL = (process.env.BACKEND_URL ?? "http://localhost:8080").replace(/\/+$/, "");
const PORT = Number(process.env.PORT ?? 3000);
// Not HOSTNAME: Docker sets that to the container id.
const HOST = process.env.HOST ?? "0.0.0.0";
const isProduction = process.env.NODE_ENV === "production";

const proxy = createProxy(BACKEND_URL);

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
  // book uploads stream through to the backend, which enforces the real limits
  maxRequestBodySize: maxRequestBodySize(process.env),
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
