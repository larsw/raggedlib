import { useCallback, useEffect, useState } from "react";

export const TABS = ["ask", "search", "library", "indexing"] as const;
export type TabId = (typeof TABS)[number];

function parseHash(): TabId {
  const h = window.location.hash.replace(/^#\/?/, "").split(/[/?]/)[0] ?? "";
  return (TABS as readonly string[]).includes(h) ? (h as TabId) : "ask";
}

/** Active tab stored in the URL hash (#/ask, #/search, ...), so reload and back/forward work. */
export function useHashTab(): [TabId, (tab: TabId) => void] {
  const [tab, setTab] = useState<TabId>(parseHash);
  useEffect(() => {
    const onChange = () => setTab(parseHash());
    window.addEventListener("hashchange", onChange);
    return () => window.removeEventListener("hashchange", onChange);
  }, []);
  const navigate = useCallback((next: TabId) => {
    if (parseHash() !== next || !window.location.hash) window.location.hash = `/${next}`;
    setTab(next);
  }, []);
  return [tab, navigate];
}
