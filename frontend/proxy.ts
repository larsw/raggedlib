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
      if (req.signal.aborted) return new Response(null, { status: 499 });
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
