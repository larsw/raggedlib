import { describe, expect, test } from "bun:test";
import type { ScopeInfo } from "../api/types";
import { expiresAt, groupScopes, isExpired, keyLibrariesLabel, mcpAddCommand } from "./apiKeys";

const SCOPES: ScopeInfo[] = [
  { scope: "library:read", description: "read", perUser: false },
  { scope: "library:curate", description: "curate", perUser: false },
  { scope: "favorites:read", description: "fav read", perUser: true },
  { scope: "favorites:write", description: "fav write", perUser: true },
  { scope: "reviews:read", description: "rev read", perUser: true },
  { scope: "reviews:write", description: "rev write", perUser: true },
];

describe("groupScopes", () => {
  test("personal keys: every scope, by group, in the server's order", () => {
    const groups = groupScopes(SCOPES);
    expect(groups.map((g) => g.label)).toEqual(["Library", "Favorites", "Reviews"]);
    expect(groups[0]!.scopes.map((s) => s.scope)).toEqual(["library:read", "library:curate"]);
    expect(groups[2]!.scopes.map((s) => s.scope)).toEqual(["reviews:read", "reviews:write"]);
  });

  test("system keys: no per-user scopes, and no empty groups", () => {
    const groups = groupScopes(SCOPES, true);
    expect(groups.map((g) => g.label)).toEqual(["Library"]);
  });

  test("unknown scopes land under Other", () => {
    const groups = groupScopes([...SCOPES, { scope: "notes:read" as ScopeInfo["scope"], description: "", perUser: true }]);
    expect(groups.at(-1)).toEqual({ label: "Other", scopes: [{ scope: "notes:read" as ScopeInfo["scope"], description: "", perUser: true }] });
  });
});

describe("mcpAddCommand", () => {
  test("points at the UI's own /mcp", () => {
    expect(mcpAddCommand("http://books.lan:3000", "rlk_abc")).toBe(
      'claude mcp add --transport http raggedlib http://books.lan:3000/mcp --header "Authorization: Bearer rlk_abc"',
    );
    expect(mcpAddCommand("https://x.example/", "rlk_k")).toContain("https://x.example/mcp ");
  });
});

describe("expiresAt", () => {
  const now = Date.UTC(2026, 0, 10, 12, 0, 0);

  test("never and fixed periods", () => {
    expect(expiresAt("never", "", now)).toBeNull();
    expect(expiresAt("30d", "", now)).toBe("2026-02-09T12:00:00.000Z");
    expect(expiresAt("1y", "", now)).toBe("2027-01-10T12:00:00.000Z");
  });

  test("a custom date is the end of that day, and must be in the future", () => {
    const later = expiresAt("custom", "2026-03-01", now);
    expect(typeof later).toBe("string");
    expect(new Date(later as string).getDate()).toBe(1);
    expect(expiresAt("custom", "2025-12-31", now)).toBe("invalid");
    expect(expiresAt("custom", "", now)).toBe("invalid");
    expect(expiresAt("custom", "tomorrow", now)).toBe("invalid");
  });
});

describe("keys", () => {
  test("expiry", () => {
    const now = Date.UTC(2026, 0, 10);
    expect(isExpired({ expiresAt: null }, now)).toBe(false);
    expect(isExpired({ expiresAt: "2026-01-09T00:00:00Z" }, now)).toBe(true);
    expect(isExpired({ expiresAt: "2026-01-11T00:00:00Z" }, now)).toBe(false);
  });

  test("library restriction label", () => {
    const libs = [
      { id: 1, name: "Tech" },
      { id: 2, name: "Misc" },
    ];
    expect(keyLibrariesLabel(null, libs)).toBe("All libraries");
    expect(keyLibrariesLabel([2, 7], libs)).toBe("Misc, #7");
  });
});
