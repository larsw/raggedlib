import type { GrantableRole, LibraryRole } from "../api/types";

/** Library roles in order: each includes the ones before it. */
export const LIBRARY_ROLES: readonly LibraryRole[] = ["NONE", "VIEWER", "CURATOR", "ADMIN"];

/** The roles that can be granted, least first. */
export const GRANTABLE_ROLES: readonly GrantableRole[] = ["viewer", "curator", "admin"];

export function roleAtLeast(role: LibraryRole | null | undefined, min: LibraryRole): boolean {
  return LIBRARY_ROLES.indexOf(role ?? "NONE") >= LIBRARY_ROLES.indexOf(min);
}

/** "viewer", "curator", "admin" or "no access". */
export function roleLabel(role: LibraryRole | null | undefined): string {
  return !role || role === "NONE" ? "no access" : role.toLowerCase();
}

export function toGrantable(role: LibraryRole | null | undefined): GrantableRole | null {
  return !role || role === "NONE" ? null : (role.toLowerCase() as GrantableRole);
}

/** What each role allows, for tooltips. */
export const ROLE_HELP: Record<GrantableRole, string> = {
  viewer: "Browse, search, ask and read the library's books; review them and put them on lists",
  curator: "Also sync the library, (re)index its books, and remove others' reviews of them",
  admin: "Also grant and revoke roles in the library",
};

/** The caller's role in [libraryId], from a list of libraries with roles (GET /api/libraries or /api/me). */
export function roleIn(libraries: readonly { id: number; role: LibraryRole }[], libraryId: number | null | undefined): LibraryRole {
  if (libraryId == null) return "NONE";
  return libraries.find((l) => l.id === libraryId)?.role ?? "NONE";
}

/** A human message for the `?login_error=` code the backend redirects to after a failed sign-in. */
export function loginErrorMessage(code: string): string {
  switch (code) {
    case "account_disabled":
      return "This account is disabled. Ask an administrator to enable it again.";
    case "access_denied":
      return "Sign-in was cancelled or refused by the identity provider.";
    case "invalid_id_token":
      return "The identity provider sent a sign-in token raggedlib couldn't use.";
    default:
      return `Sign-in failed (${code}). Try again, or ask an administrator.`;
  }
}

/** Splits `?login_error=…` off a URL: the code (or null) and the URL without it, to put back in the address bar. */
export function takeLoginError(href: string): { code: string | null; cleanUrl: string } {
  const url = new URL(href);
  const code = url.searchParams.get("login_error");
  if (code == null) return { code: null, cleanUrl: href };
  url.searchParams.delete("login_error");
  return { code: code || "login_failed", cleanUrl: url.pathname + url.search + url.hash };
}

/**
 * Shortens a long display name for the navbar: every part but the last collapses to an initial
 * ("Alexander Hamilton-Featherstonehaugh" → "A. Hamilton-Featherstonehaugh"). Short names, and single words, stay.
 */
export function abbreviateDisplayName(name: string, maxLength = 20): string {
  const trimmed = name.trim().replace(/\s+/g, " ");
  if (trimmed.length <= maxLength) return trimmed;
  const parts = trimmed.split(" ");
  if (parts.length < 2) return trimmed;
  const surname = parts[parts.length - 1];
  const initials = parts
    .slice(0, -1)
    .map((p) => `${(Array.from(p)[0] ?? "").toLocaleUpperCase()}.`)
    .join(" ");
  return `${initials} ${surname}`;
}

/** Whether what the previous user left in this browser (conversation, scope, chosen library) must go. */
export function isOtherUser(lastUserId: number | null, userId: number | null): boolean {
  return lastUserId != null && lastUserId !== userId;
}
