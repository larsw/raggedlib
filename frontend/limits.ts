const UNITS: Record<string, number> = { B: 1, KB: 1024, MB: 1024 ** 2, GB: 1024 ** 3, TB: 1024 ** 4 };

/** A size as Spring writes it (`2GB`, `500MB`, `1048576` bytes), in bytes; the units are binary, like Spring's. */
export function parseDataSize(text: string): number {
  const match = /^\s*(\d+)\s*([a-zA-Z]{0,2})\s*$/.exec(text);
  const unit = UNITS[(match?.[2] || "B").toUpperCase()];
  if (!match || unit === undefined) throw new Error(`not a size: "${text}" (use e.g. 2GB or 500MB)`);
  return Number(match[1]) * unit;
}

/** What the backend accepts in one request by default (Spring's multipart max-request-size in application.yml). */
export const DEFAULT_MAX_REQUEST_SIZE = "2GB";

/**
 * The largest request body this server takes: the backend's limit for one upload, from the same setting, plus room
 * for the multipart framing, so that an upload over the limit is refused by the backend, which says which limit it
 * hit, rather than here. Bun's own default (128 MB) would refuse large books before they reach the backend.
 */
export function maxRequestBodySize(env: Record<string, string | undefined>): number {
  const configured = env.UPLOAD_MAX_REQUEST_SIZE ?? env.RAGGEDLIB_UPLOAD_MAX_REQUEST_SIZE ?? DEFAULT_MAX_REQUEST_SIZE;
  return parseDataSize(configured) + UNITS.MB!;
}
