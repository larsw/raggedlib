import { Button, Intent, NonIdealState, Spinner } from "@blueprintjs/core";
import { useCallback, useEffect, useRef, useState } from "react";
import { api } from "../api/client";
import type { FavoriteList } from "../api/types";
import { BookDetailsPanel } from "../components/BookDetailsPanel";
import { ErrorState } from "../components/ErrorState";
import { ListFormDialog } from "../components/lists/ListFormDialog";
import { useAsync } from "../hooks/useAsync";
import { useBookNavigation, useReindex } from "../hooks/useBookActions";
import { listIdFromHash, tabFromHash } from "../hooks/useHashTab";
import { useSidePanel } from "../hooks/useSidePanel";
import { STORAGE_KEYS } from "../lib/storage";
import { ListDetails } from "./lists/ListDetails";
import { ListsSidebar } from "./lists/ListsSidebar";

/**
 * Favorite lists: the caller's own (private or shared) and the ones others share, one list's books at a time, and a
 * book's details beside them. The open list is in the address (#/lists/{id}), so it can be linked to.
 */
export function ListsView({ active }: { active: boolean }) {
  const lists = useAsync((signal) => api.lists(undefined, signal), []);
  const [selectedId, setSelectedId] = useState<number | null>(() => listIdFromHash(window.location.hash));
  const [bookId, setBookId] = useState<number | null>(null);
  /** bumped to load the open list again */
  const [listKey, setListKey] = useState(0);
  /** bumped to refresh the open book's details (after a reindex) */
  const [bookKey, setBookKey] = useState(0);
  /** the create/edit dialog; `list` stays while it closes, so its title doesn't flip during the transition */
  const [form, setFormState] = useState<{ open: boolean; list: FavoriteList | null }>({ open: false, list: null });
  const openForm = (list: FavoriteList | null) => setFormState({ open: true, list });
  const closeForm = () => setFormState((f) => ({ ...f, open: false }));
  const bodyRef = useRef<HTMLDivElement>(null);
  const panel = useSidePanel(bodyRef, { storageKey: STORAGE_KEYS.bookPanel, onSnapClose: () => setBookId(null) });
  const { askAbout, searchIn } = useBookNavigation();
  const { reindexing, reindex } = useReindex(useCallback(() => setBookKey((k) => k + 1), []));

  const all = [...(lists.data?.mine ?? []), ...(lists.data?.shared ?? [])];
  const selected = selectedId == null ? all[0] : all.find((l) => l.id === selectedId);

  const reloadAll = useCallback(() => {
    lists.reload();
    setListKey((k) => k + 1);
  }, [lists.reload]);

  // coming back to the tab: books may have been put on lists from the Library meanwhile
  const wasActive = useRef(active);
  useEffect(() => {
    if (active && !wasActive.current) reloadAll();
    wasActive.current = active;
  }, [active, reloadAll]);

  // the open list goes in the address; a pasted or back/forward #/lists/{id} opens it
  useEffect(() => {
    if (!active) return;
    const id = selected?.id ?? selectedId;
    const want = id != null ? `#/lists/${id}` : "#/lists";
    if (window.location.hash !== want) window.history.replaceState(window.history.state, "", want);
  }, [active, selected?.id, selectedId]);
  useEffect(() => {
    const onHash = () => {
      const id = listIdFromHash(window.location.hash);
      if (tabFromHash(window.location.hash) === "lists" && id != null) setSelectedId(id);
    };
    window.addEventListener("hashchange", onHash);
    return () => window.removeEventListener("hashchange", onHash);
  }, []);

  const select = (id: number) => {
    setSelectedId(id);
    setBookId(null);
  };

  const main = () => {
    if (lists.error && !lists.data) return <ErrorState error={lists.error} onRetry={lists.reload} title="Couldn't load your lists" />;
    if (!lists.data) return <NonIdealState icon={<Spinner />} description="Loading lists…" />;
    if (all.length === 0) {
      return (
        <NonIdealState
          icon="bookmark"
          title="No lists yet"
          description="Keep books together: to read, favorites, for a course. Make a list here, or from a book's details in the Library."
          action={<Button intent={Intent.PRIMARY} icon="plus" text="New list" onClick={() => openForm(null)} />}
        />
      );
    }
    if (!selected) {
      // a list just created shows up with the next load
      if (lists.loading) return <NonIdealState icon={<Spinner />} />;
      return (
        <NonIdealState
          icon="search"
          title="List not found"
          description="It was deleted, or it isn't shared (anymore)."
          action={<Button text="Show my lists" onClick={() => setSelectedId(null)} />}
        />
      );
    }
    return (
      <ListDetails
        listId={selected.id}
        reloadKey={listKey}
        selectedBookId={bookId}
        onSelectBook={setBookId}
        onEdit={openForm}
        onChanged={lists.reload}
        onDeleted={(deleted) => {
          // gone from the sidebar at once, so the next list opens rather than the deleted one again
          lists.setData((d) => d && { ...d, mine: d.mine.filter((l) => l.id !== deleted.id) });
          setSelectedId(null);
          setBookId(null);
          lists.reload();
        }}
      />
    );
  };

  return (
    <div
      className={`lists-view${panel.dragging ? " is-resizing" : ""}`}
      onKeyDown={(e) => {
        if (e.key === "Escape" && bookId != null && !e.defaultPrevented) setBookId(null);
      }}
    >
      <ListsSidebar
        lists={lists.data}
        loading={lists.loading}
        selectedId={selected?.id ?? null}
        onSelect={select}
        onCreate={() => openForm(null)}
      />
      <div className="lists-body" ref={bodyRef}>
        <div className="lists-main">{main()}</div>
        {bookId != null && (
          <div className="side-dock" style={{ width: panel.width }}>
            <div className="side-resize-handle" {...panel.handleProps} aria-label="Resize book details" />
            <BookDetailsPanel
              bookId={bookId}
              refreshKey={bookKey}
              reindexing={reindexing.has(bookId)}
              onClose={() => setBookId(null)}
              onSelectBook={setBookId}
              onAsk={askAbout}
              onSearchIn={searchIn}
              onReindex={reindex}
              onListsChanged={reloadAll}
            />
          </div>
        )}
      </div>
      <ListFormDialog
        isOpen={form.open}
        list={form.list}
        onClose={closeForm}
        onSaved={(list) => {
          closeForm();
          setSelectedId(list.id);
          reloadAll();
        }}
      />
    </div>
  );
}
