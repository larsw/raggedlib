import { createContext, useCallback, useContext, useMemo, type ReactNode } from "react";
import { api } from "./api/client";
import type { SystemInfo } from "./api/types";
import type { TabId } from "./hooks/useHashTab";
import { usePolling } from "./hooks/usePolling";
import { useScope, type BookRef, type Scope } from "./hooks/useScope";

interface AppContextValue {
  system: SystemInfo | undefined;
  systemError: unknown;
  systemLoading: boolean;
  refreshSystem: () => void;
  scope: Scope;
  setScope: React.Dispatch<React.SetStateAction<Scope>>;
  /** Adds a book to the retrieval scope (no-op if already present). */
  addBookToScope: (book: BookRef) => void;
  navigate: (tab: TabId) => void;
}

const AppContext = createContext<AppContextValue | null>(null);

export function AppProvider({ navigate, children }: { navigate: (tab: TabId) => void; children: ReactNode }) {
  const sys = usePolling((signal) => api.system(signal), 30_000);
  const [scope, setScope] = useScope();

  const addBookToScope = useCallback(
    (book: BookRef) =>
      setScope((s) =>
        s.books.some((b) => b.id === book.id)
          ? s
          : { ...s, books: [...s.books, { id: book.id, title: book.title, authors: book.authors }] },
      ),
    [setScope],
  );

  const value = useMemo<AppContextValue>(
    () => ({
      system: sys.data,
      systemError: sys.error,
      systemLoading: sys.loading,
      refreshSystem: sys.refresh,
      scope,
      setScope,
      addBookToScope,
      navigate,
    }),
    [sys.data, sys.error, sys.loading, sys.refresh, scope, setScope, addBookToScope, navigate],
  );
  return <AppContext value={value}>{children}</AppContext>;
}

export function useApp(): AppContextValue {
  const ctx = useContext(AppContext);
  if (!ctx) throw new Error("useApp must be used inside <AppProvider>");
  return ctx;
}
