import { CSRF_COOKIE, CSRF_HEADER, readCookie } from "./src/lib/csrf";

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

/**
 * Every request gets an id the backend can cancel it by (`POST /api/requests/{id}/cancel`, see the backend's
 * RequestCancellations): a servlet container never notices the browser going away while it works on an answer (a
 * layout, the Postgres query behind it), so when the browser aborts a request, the proxy tells the backend.
 */
export const REQUEST_ID_HEADER = "x-request-id";

/** Headers of the request that say nothing about who made it, so the cancel leaves them out. */
const NOT_FOR_CANCEL = ["content-type", "content-length", "content-encoding", "expect", REQUEST_ID_HEADER];

/**
 * Asks the backend to stop working on request [id] as the caller that made it: with its cookies or API key, and the
 * CSRF header a session's POST needs, from its cookie. Fire and forget: if it fails, the work just runs to its end.
 */
function cancelUpstream(base: string, id: string, requestHeaders: Headers): void {
  const headers = new Headers(requestHeaders);
  for (const name of NOT_FOR_CANCEL) headers.delete(name);
  const token = readCookie(requestHeaders.get("cookie") ?? "", CSRF_COOKIE);
  if (token) headers.set(CSRF_HEADER, token);
  fetch(`${base}/api/requests/${encodeURIComponent(id)}/cancel`, { method: "POST", headers, redirect: "manual" })
    .then(async (res) => {
      await res.body?.cancel();
      // 4xx: e.g. signed out meanwhile, or no CSRF cookie yet; nothing to do about it
      if (res.status >= 500) console.warn(`[proxy] cancelling request ${id}: ${res.status}`);
    })
    .catch((err) => console.warn(`[proxy] cancelling request ${id} failed: ${(err as Error).message}`));
}

export function createProxy(backendUrl: string) {
  const base = backendUrl.replace(/\/+$/, "");
  return async (req: Request, server: Bun.Server<undefined>): Promise<Response> => {
    // Chat streams can sit idle for a long time while the model loads; never time them out here.
    server.timeout(req, 0);

    const url = new URL(req.url);
    const target = base + url.pathname + url.search;
    const hasBody = req.method !== "GET" && req.method !== "HEAD";
    // To the browser this is a same-origin request, so the backend's CORS check must not apply.
    const forwardHeaders = stripHeaders(req.headers);
    forwardHeaders.delete("origin");
    // Where the browser is: the backend builds its OIDC redirect URI from these headers.
    forwardHeaders.set("x-forwarded-host", url.host);
    forwardHeaders.set("x-forwarded-proto", url.protocol.replace(/:$/, ""));
    forwardHeaders.set("x-forwarded-port", url.port || (url.protocol === "https:" ? "443" : "80"));
    const clientIp = server.requestIP(req)?.address;
    if (clientIp) forwardHeaders.set("x-forwarded-for", clientIp);
    // ours, not the browser's: unique, so it can only ever cancel this request
    const requestId = crypto.randomUUID();
    forwardHeaders.set(REQUEST_ID_HEADER, requestId);

    let upstream: Response;
    try {
      upstream = await fetch(target, {
        method: req.method,
        headers: forwardHeaders,
        body: hasBody ? req.body ?? undefined : undefined,
        redirect: "manual",
        decompress: false,
        signal: req.signal,
      });
    } catch (err) {
      if (req.signal.aborted) {
        // the browser gave up before the backend answered: tell it to stop working on the answer
        cancelUpstream(base, requestId, forwardHeaders);
        return new Response(null, { status: 499 });
      }
      console.warn(`[proxy] ${req.method} ${target} failed: ${(err as Error).message}`);
      return unreachable();
    }

    const headers = stripHeaders(upstream.headers);
    if ((headers.get("content-type") ?? "").startsWith("text/event-stream")) {
      headers.set("cache-control", "no-cache");
      headers.set("x-accel-buffering", "no");
    }
    return new Response(upstream.body, {
      status: upstream.status,
      statusText: upstream.statusText,
      headers,
    });
  };
}
