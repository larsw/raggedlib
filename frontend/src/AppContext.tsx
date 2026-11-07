import { createContext, useCallback, useContext, useEffect, useMemo, useRef, type ReactNode } from "react";
import { api } from "./api/client";
import type { Library, LibraryRole, Me, SystemInfo } from "./api/types";
import type { TabId } from "./hooks/useHashTab";
import { roleIn as roleInLibraries } from "./lib/auth";
import type { ReaderRequest } from "./lib/reader";
import { usePolling } from "./hooks/usePolling";
import { useLibraries } from "./hooks/useLibraries";
import { useScope, type BookRef, type Scope } from "./hooks/useScope";

interface AppContextValue {
  /** The signed-in user. */
  me: Me;
  /** Re-reads /api/me (roles may have changed), and with it the libraries. */
  refreshMe: () => Promise<void>;
  /** Ends the session; throws if the backend refused. Not available in single-user mode (nothing to sign out of). */
  signOut: () => Promise<void>;
  /** The caller's role in a library (from GET /api/libraries): NONE for one it can't see. */
  roleIn: (libraryId: number | null | undefined) => LibraryRole;
  system: SystemInfo | undefined;
  systemError: unknown;
  systemLoading: boolean;
  refreshSystem: () => void;
  scope: Scope;
  setScope: React.Dispatch<React.SetStateAction<Scope>>;
  /** Adds a book to the retrieval scope (no-op if already present). */
  addBookToScope: (book: BookRef) => void;
  navigate: (tab: TabId) => void;
  /** Opens a book in the in-app reader (ebook formats), at a cited passage or section if given. */
  openReader: (request: ReaderRequest) => void;
  /** The libraries the caller can see, with its role in each. */
  libraries: Library[];
  librariesLoading: boolean;
  refreshLibraries: () => void;
  /** The library chosen in the navbar's chooser; null = every library (the union view). */
  library: Library | null;
  libraryId: number | null;
  setLibraryId: (id: number | null) => void;
}

const AppContext = createContext<AppContextValue | null>(null);

export function AppProvider({
  me,
  refreshMe,
  signOut,
  navigate,
  openReader,
  children,
}: {
  me: Me;
  refreshMe: () => Promise<void>;
  signOut: () => Promise<void>;
  navigate: (tab: TabId) => void;
  openReader: (request: ReaderRequest) => void;
  children: ReactNode;
}) {
  const sys = usePolling((signal) => api.system(signal), 30_000);
  const [scope, setScope] = useScope();
  const { libraries, library, libraryId, setLibraryId, librariesLoading, refreshLibraries } = useLibraries();

  // the libraries (and the roles in them) follow /api/me: refetch when an admin changed what this user may see
  const access = me.libraries.map((l) => `${l.id}:${l.role}`).join(",");
  const lastAccess = useRef(access);
  useEffect(() => {
    if (access === lastAccess.current) return;
    lastAccess.current = access;
    refreshLibraries();
    sys.refresh();
  }, [access, refreshLibraries, sys.refresh]);

  // roles as GET /api/libraries has them; /api/me's copy (the same, computed alike) until that has loaded
  const roleIn = useCallback(
    (id: number | null | undefined) => roleInLibraries(librariesLoading ? me.libraries : libraries, id),
    [librariesLoading, libraries, me.libraries],
  );

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
      me,
      refreshMe,
      signOut,
      roleIn,
      system: sys.data,
      systemError: sys.error,
      systemLoading: sys.loading,
      refreshSystem: sys.refresh,
      scope,
      setScope,
      addBookToScope,
      navigate,
      openReader,
      libraries,
      librariesLoading,
      refreshLibraries,
      library,
      libraryId,
      setLibraryId,
    }),
    [
      me, refreshMe, signOut, roleIn, sys.data, sys.error, sys.loading, sys.refresh, scope, setScope, addBookToScope,
      navigate, openReader, libraries, librariesLoading, refreshLibraries, library, libraryId, setLibraryId,
    ],
  );
  return <AppContext value={value}>{children}</AppContext>;
}

export function useApp(): AppContextValue {
  const ctx = useContext(AppContext);
  if (!ctx) throw new Error("useApp must be used inside <AppProvider>");
  return ctx;
}
