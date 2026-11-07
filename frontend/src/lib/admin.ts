import type { Member } from "../api/types";

/** Whether a user matches a filter typed in the administration: any word in the name, email or a group. */
export function matchesUser(user: { displayName: string; email: string | null; groups?: readonly string[] }, filter: string): boolean {
  const words = filter.toLocaleLowerCase().split(/\s+/).filter(Boolean);
  if (words.length === 0) return true;
  const haystack = [user.displayName, user.email ?? "", ...(user.groups ?? [])].join(" ").toLocaleLowerCase();
  return words.every((w) => haystack.includes(w));
}

/** A library's members as shown: filtered, and (optionally) only those with access, which come first anyway. */
export function visibleMembers(members: readonly Member[], filter: string, onlyWithAccess: boolean): Member[] {
  return members
    .filter((m) => matchesUser(m, filter) && (!onlyWithAccess || m.role !== "NONE"))
    .sort((a, b) => Number(a.role === "NONE") - Number(b.role === "NONE"));
}
