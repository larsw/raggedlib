import { useCallback, useState } from "react";
import { api } from "../api/client";
import type { SyncResult } from "../api/types";
import { toastError, toastSuccess } from "../components/toaster";
import { clearFacetCache } from "./useFacet";

/** POST /api/library/sync with toast feedback. Resolves with the result (or undefined on failure). */
export function useSyncLibrary() {
  const [syncing, setSyncing] = useState(false);
  const sync = useCallback(async (): Promise<SyncResult | undefined> => {
    setSyncing(true);
    try {
      const r = await api.syncLibrary();
      clearFacetCache(); // tags and publishers may have changed; the next dropdown that opens refetches them
      toastSuccess(`Library synced: ${r.added} added, ${r.updated} updated, ${r.removed} removed (${r.total} books)`);
      return r;
    } catch (err) {
      toastError("Library sync failed", err);
      return undefined;
    } finally {
      setSyncing(false);
    }
  }, []);
  return { sync, syncing };
}
