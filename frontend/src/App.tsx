import { BlueprintProvider } from "@blueprintjs/core";
import { lazy, Suspense, useCallback, useState } from "react";
import { AppProvider } from "./AppContext";
import { AppNavbar } from "./components/AppNavbar";
import { AuthGate, type SignedIn } from "./components/auth/AuthGate";
import { NoLibraryAccess } from "./components/auth/NoLibraryAccess";
import { TABS, tabsFor, useHashTab, type TabId } from "./hooks/useHashTab";
import { useTheme, type Theme } from "./hooks/useTheme";
import type { ReaderRequest } from "./lib/reader";
import { AskView } from "./views/AskView";
import { IndexingView } from "./views/IndexingView";
import { LibraryView } from "./views/LibraryView";
import { ListsView } from "./views/ListsView";
import { SearchView } from "./views/SearchView";
import { VectorSpaceView } from "./views/VectorSpaceView";

// The reader (and foliate-js with it) loads on first use only.
const ReaderDrawer = lazy(() => import("./components/reader/ReaderDrawer"));

export function App() {
  const [theme, toggleTheme] = useTheme();
  return (
    <BlueprintProvider>
      <AuthGate theme={theme} onToggleTheme={toggleTheme}>
        {(auth) => <SignedInApp auth={auth} theme={theme} onToggleTheme={toggleTheme} />}
      </AuthGate>
    </BlueprintProvider>
  );
}

function SignedInApp({ auth, theme, onToggleTheme }: { auth: SignedIn; theme: Theme; onToggleTheme: () => void }) {
  const { me } = auth;
  const [hashTab, setTab] = useHashTab();
  const noAccess = me.libraries.length === 0;
  const tabs = noAccess ? [] : tabsFor(me.canCurate);
  // a tab this user doesn't get (e.g. #/indexing for a viewer) falls back to Ask
  const tab: TabId = tabs.includes(hashTab) ? hashTab : "ask";
  // Views mount on first visit and then stay mounted (hidden), so a streaming answer,
  // search results and library filters survive tab switches.
  const [visited, setVisited] = useState<Set<TabId>>(() => new Set([tab]));
  if (!visited.has(tab)) setVisited(new Set(visited).add(tab));

  // The reader is a drawer over whichever view opened it; the request stays while it closes (exit transition).
  const [reader, setReader] = useState<{ request: ReaderRequest; open: boolean } | null>(null);
  const openReader = useCallback((request: ReaderRequest) => setReader({ request, open: true }), []);
  const closeReader = useCallback(() => setReader((r) => r && { ...r, open: false }), []);

  const render = (id: TabId) => {
    const active = id === tab;
    switch (id) {
      case "ask":
        return <AskView />;
      case "search":
        return <SearchView />;
      case "library":
        return <LibraryView active={active} />;
      case "lists":
        return <ListsView active={active} />;
      case "vectors":
        return <VectorSpaceView active={active} />;
      case "indexing":
        return <IndexingView active={active} />;
    }
  };

  return (
    <AppProvider me={me} refreshMe={auth.refreshMe} signOut={auth.signOut} navigate={setTab} openReader={openReader}>
      <div className="app">
        <AppNavbar tab={tab} tabs={tabs} onTabChange={setTab} theme={theme} onToggleTheme={onToggleTheme} />
        <main className="app-main">
          {noAccess ? (
            <NoLibraryAccess />
          ) : (
            TABS.filter((id) => visited.has(id) && tabs.includes(id)).map((id) => (
              <div key={id} className="view" hidden={id !== tab} role="tabpanel" aria-label={id}>
                {render(id)}
              </div>
            ))
          )}
        </main>
      </div>
      {reader && (
        <Suspense fallback={null}>
          <ReaderDrawer request={reader.request} isOpen={reader.open} theme={theme} onClose={closeReader} />
        </Suspense>
      )}
    </AppProvider>
  );
}
