import { useCallback, useEffect, useState } from "react";

export const TABS = ["library", "search", "ask", "lists", "vectors", "indexing"] as const;
export type TabId = (typeof TABS)[number];

/** The tab a hash names (#/ask, #/lists/12, …); "ask" for anything else. */
export function tabFromHash(hash: string): TabId {
  const h = hash.replace(/^#\/?/, "").split(/[/?]/)[0] ?? "";
  return (TABS as readonly string[]).includes(h) ? (h as TabId) : "ask";
}

/** The tabs a user gets: Indexing only for curators and library admins (only admins start, stop, sync and reindex). */
export function tabsFor(canCurate: boolean): TabId[] {
  return TABS.filter((t) => t !== "indexing" || canCurate);
}

/** The list a #/lists/{id} hash points at, or null. */
export function listIdFromHash(hash: string): number | null {
  const m = /^#\/?lists\/(\d+)(?:[/?]|$)/.exec(hash);
  return m ? Number(m[1]) : null;
}

/** Active tab stored in the URL hash (#/ask, #/search, ...), so reload and back/forward work. */
export function useHashTab(): [TabId, (tab: TabId) => void] {
  const [tab, setTab] = useState<TabId>(() => tabFromHash(window.location.hash));
  useEffect(() => {
    const onChange = () => setTab(tabFromHash(window.location.hash));
    window.addEventListener("hashchange", onChange);
    return () => window.removeEventListener("hashchange", onChange);
  }, []);
  const navigate = useCallback((next: TabId) => {
    if (tabFromHash(window.location.hash) !== next || !window.location.hash) window.location.hash = `/${next}`;
    setTab(next);
  }, []);
  return [tab, navigate];
}
