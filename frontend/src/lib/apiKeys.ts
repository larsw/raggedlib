import type { ApiKey, ScopeInfo } from "../api/types";

export interface ScopeGroup {
  label: string;
  scopes: ScopeInfo[];
}

const GROUPS: { prefix: string; label: string }[] = [
  { prefix: "library:", label: "Library" },
  { prefix: "favorites:", label: "Favorites" },
  { prefix: "reviews:", label: "Reviews" },
];

/**
 * The scopes a key can have, grouped for the form (Library, Favorites, Reviews; anything newer under Other), in the
 * server's order. A system-wide key acts as no user, so it can't have per-user scopes.
 */
export function groupScopes(scopes: readonly ScopeInfo[], system = false): ScopeGroup[] {
  const usable = scopes.filter((s) => !system || !s.perUser);
  const groups: ScopeGroup[] = GROUPS.map((g) => ({ label: g.label, scopes: usable.filter((s) => s.scope.startsWith(g.prefix)) }));
  const other = usable.filter((s) => !GROUPS.some((g) => s.scope.startsWith(g.prefix)));
  if (other.length) groups.push({ label: "Other", scopes: other });
  return groups.filter((g) => g.scopes.length > 0);
}

/** A ready-to-paste command that adds raggedlib's MCP server to Claude Code with this key. */
export function mcpAddCommand(origin: string, rawKey: string): string {
  return `claude mcp add --transport http raggedlib ${origin.replace(/\/+$/, "")}/mcp --header "Authorization: Bearer ${rawKey}"`;
}

export type ExpiryChoice = "never" | "30d" | "90d" | "1y" | "custom";

export const EXPIRY_OPTIONS: { value: ExpiryChoice; label: string }[] = [
  { value: "never", label: "Never" },
  { value: "30d", label: "In 30 days" },
  { value: "90d", label: "In 90 days" },
  { value: "1y", label: "In a year" },
  { value: "custom", label: "On a date…" },
];

const DAY_MS = 86_400_000;

/**
 * When a new key expires, as an ISO instant: null for never, "invalid" for a missing or past custom date. A custom
 * date (YYYY-MM-DD from a date input) means the end of that day, local time.
 */
export function expiresAt(choice: ExpiryChoice, customDate: string, now = Date.now()): string | null | "invalid" {
  switch (choice) {
    case "never":
      return null;
    case "30d":
      return new Date(now + 30 * DAY_MS).toISOString();
    case "90d":
      return new Date(now + 90 * DAY_MS).toISOString();
    case "1y":
      return new Date(now + 365 * DAY_MS).toISOString();
    case "custom": {
      if (!/^\d{4}-\d{2}-\d{2}$/.test(customDate)) return "invalid";
      const t = new Date(`${customDate}T23:59:59`).getTime();
      return Number.isFinite(t) && t > now ? new Date(t).toISOString() : "invalid";
    }
  }
}

export function isExpired(key: Pick<ApiKey, "expiresAt">, now = Date.now()): boolean {
  if (!key.expiresAt) return false;
  const t = new Date(key.expiresAt).getTime();
  return Number.isFinite(t) && t <= now;
}

/** "All libraries" for an unrestricted key, else the names of the libraries it reaches (ids for unknown ones). */
export function keyLibrariesLabel(libraryIds: readonly number[] | null, libraries: readonly { id: number; name: string }[]): string {
  if (libraryIds == null) return "All libraries";
  const names = libraryIds.map((id) => libraries.find((l) => l.id === id)?.name ?? `#${id}`);
  return names.length ? names.join(", ") : "No library";
}
