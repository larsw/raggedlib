/**
 * CSRF protection for cookie-authenticated writes (Spring Security's SPA mode): the backend sets the token in the
 * readable cookie `XSRF-TOKEN` on any API response, and every request that isn't GET/HEAD/OPTIONS/TRACE must echo it
 * in the `X-XSRF-TOKEN` header, or it is refused with 403.
 */

export const CSRF_COOKIE = "XSRF-TOKEN";
export const CSRF_HEADER = "X-XSRF-TOKEN";

const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS", "TRACE"]);

/** The value of cookie [name] in a `document.cookie` string, or null. */
export function readCookie(cookies: string, name: string): string | null {
  for (const part of cookies.split(";")) {
    const eq = part.indexOf("=");
    if (eq < 0) continue;
    if (part.slice(0, eq).trim() !== name) continue;
    const raw = part.slice(eq + 1).trim();
    try {
      return decodeURIComponent(raw);
    } catch {
      return raw;
    }
  }
  return null;
}

/** Whether a request with this method changes state (and so needs the CSRF header). */
export function needsCsrf(method: string | undefined): boolean {
  return !SAFE_METHODS.has((method ?? "GET").toUpperCase());
}

/** The CSRF header to add to a request, from the cookies; empty when the method is safe or there is no token yet. */
export function csrfHeaders(method: string | undefined, cookies: string): Record<string, string> {
  if (!needsCsrf(method)) return {};
  const token = readCookie(cookies, CSRF_COOKIE);
  return token ? { [CSRF_HEADER]: token } : {};
}
