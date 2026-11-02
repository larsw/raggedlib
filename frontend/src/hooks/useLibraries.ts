import { useCallback, useEffect, useState } from "react";
import { api } from "../api/client";
import type { Library } from "../api/types";
import { loadJson, saveJson, STORAGE_KEYS } from "../lib/storage";

/**
 * The configured libraries and the one chosen in the navbar's chooser (null = every library, the union view).
 * Scopes what Library/Indexing show and what Ask/Search retrieve from; persisted across reloads.
 */
export function useLibraries() {
  const [libraries, setLibraries] = useState<Library[]>([]);
  const [librariesLoading, setLibrariesLoading] = useState(true);
  const [libraryId, setLibraryIdState] = useState<number | null>(() => loadJson<number | null>(STORAGE_KEYS.libraryId, null));

  const refreshLibraries = useCallback(
    () =>
      api
        .libraries()
        .then(setLibraries)
        .finally(() => setLibrariesLoading(false)),
    [],
  );

  useEffect(() => void refreshLibraries(), [refreshLibraries]);

  const setLibraryId = useCallback((id: number | null) => {
    setLibraryIdState(id);
    saveJson(STORAGE_KEYS.libraryId, id);
  }, []);

  // a library id stored from before (removed from config since) falls back to "all libraries"
  useEffect(() => {
    if (libraryId != null && libraries.length > 0 && !libraries.some((l) => l.id === libraryId)) setLibraryId(null);
  }, [libraries, libraryId, setLibraryId]);

  const library = libraries.find((l) => l.id === libraryId) ?? null;

  return { libraries, library, libraryId, setLibraryId, librariesLoading, refreshLibraries };
}
