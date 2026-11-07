import { Alert, Button, Classes, EditableText, HTMLTable, Intent, NonIdealState, Spinner, Switch, Tag, Tooltip } from "@blueprintjs/core";
import { useState } from "react";
import { api } from "../../api/client";
import type { FavoriteList, FavoriteListDetails, FavoriteListEntry } from "../../api/types";
import { BookCover } from "../../components/BookCover";
import { ErrorState } from "../../components/ErrorState";
import { showToast, toastError, toastSuccess } from "../../components/toaster";
import { useAsync } from "../../hooks/useAsync";
import { formatAuthors, formatDate, formatRelative, formatYear, pluralize } from "../../lib/format";

interface Props {
  listId: number;
  /** bumped to load the list again (books added elsewhere, list edited) */
  reloadKey: number;
  selectedBookId: number | null;
  onSelectBook: (id: number | null) => void;
  onEdit: (list: FavoriteList) => void;
  /** the list's name, sharing or books changed: the sidebar shows them too */
  onChanged: () => void;
  onDeleted: (list: FavoriteList) => void;
}

/** Lists tab, main: one list, its books (cover, title, authors, note, added), and the owner's controls. */
export function ListDetails({ listId, reloadKey, selectedBookId, onSelectBook, onEdit, onChanged, onDeleted }: Props) {
  const details = useAsync((signal) => api.list(listId, signal), [listId, reloadKey]);
  const d = details.data?.list.id === listId ? details.data : undefined;
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [sharing, setSharing] = useState(false);
  /** bumped to put the note editors back to the stored notes (after a change that didn't go through) */
  const [noteReset, setNoteReset] = useState(0);

  if (details.error && !d) return <ErrorState error={details.error} onRetry={details.reload} title="Couldn't load the list" />;
  if (!d) return <NonIdealState icon={<Spinner />} description="Loading the list…" />;

  const { list, books } = d;
  const setEntries = (update: (books: FavoriteListEntry[]) => FavoriteListEntry[], updated?: FavoriteList) =>
    details.setData((x): FavoriteListDetails | undefined => x && { list: updated ?? x.list, books: update(x.books) });

  const toggleShared = async (shared: boolean) => {
    setSharing(true);
    try {
      const updated = await api.updateList(list.id, { shared });
      details.setData((x) => x && { ...x, list: updated });
      toastSuccess(shared ? `“${list.name}” is shared with everyone` : `“${list.name}” is private again`);
      onChanged();
    } catch (err) {
      toastError("Couldn't change sharing", err);
    } finally {
      setSharing(false);
    }
  };

  const remove = async (entry: FavoriteListEntry) => {
    try {
      const updated = await api.removeFromList(list.id, entry.book.id);
      setEntries((bs) => bs.filter((e) => e.book.id !== entry.book.id), updated);
      if (selectedBookId === entry.book.id) onSelectBook(null);
      onChanged();
      showToast({
        message: `Took “${entry.book.title}” off the list`,
        icon: "tick-circle",
        intent: Intent.SUCCESS,
        action: {
          text: "Undo",
          onClick: async () => {
            try {
              await api.addToList(list.id, entry.book.id, entry.note ?? undefined);
              details.reload();
              onChanged();
            } catch (err) {
              toastError("Couldn't put it back", err);
            }
          },
        },
      });
    } catch (err) {
      toastError("Couldn't take it off the list", err);
    }
  };

  const saveNote = async (entry: FavoriteListEntry, note: string) => {
    const next = note.trim();
    if (next === (entry.note ?? "")) return;
    try {
      // an empty note removes it
      await api.addToList(list.id, entry.book.id, next);
      setEntries((bs) => bs.map((e) => (e.book.id === entry.book.id ? { ...e, note: next || null } : e)));
    } catch (err) {
      setNoteReset((n) => n + 1);
      toastError("Couldn't save the note", err);
    }
  };

  /** a row click opens the book's details, unless it hit a control inside the row */
  const onRowClick = (e: React.MouseEvent, id: number) => {
    if ((e.target as HTMLElement).closest(`button, a, input, textarea, [role='button'], .${Classes.EDITABLE_TEXT}`)) return;
    onSelectBook(selectedBookId === id ? null : id);
  };
  const onRowKey = (e: React.KeyboardEvent, id: number) => {
    if (e.target !== e.currentTarget || (e.key !== "Enter" && e.key !== " ")) return;
    e.preventDefault();
    onSelectBook(selectedBookId === id ? null : id);
  };

  return (
    <div className="list-details">
      <header className="list-header">
        <div className="list-header-main">
          <h2 className="list-title">{list.name}</h2>
          <div className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL} list-meta`}>
            {list.mine ? (
              <Tag minimal icon={list.shared ? "people" : "lock"}>
                {list.shared ? "shared with everyone" : "private"}
              </Tag>
            ) : (
              <Tag minimal icon="person">
                shared by {list.ownerName}
              </Tag>
            )}
            <span>
              {pluralize(list.bookCount, "book")} · updated {formatRelative(list.updatedAt)}
            </span>
          </div>
          {list.description && <p className="list-description">{list.description}</p>}
        </div>
        {list.mine && (
          <div className="list-actions">
            <Tooltip content="Every signed-in user can see a shared list; only you change it" placement="bottom">
              <Switch
                className="list-share-switch"
                label="Shared"
                checked={list.shared}
                disabled={sharing}
                onChange={(e) => toggleShared(e.currentTarget.checked)}
              />
            </Tooltip>
            <Button icon="edit" text="Edit" onClick={() => onEdit(list)} />
            <Button icon="trash" variant="minimal" intent={Intent.DANGER} aria-label="Delete the list" onClick={() => setConfirmDelete(true)} />
          </div>
        )}
      </header>

      <div className="list-books">
        {books.length === 0 ? (
          <NonIdealState
            icon="bookmark"
            title="No books on this list"
            description={
              list.mine
                ? "Open a book in the Library, or here, and use “Add to list” in its details."
                : "Books on it from libraries you can't see aren't shown."
            }
          />
        ) : (
          <HTMLTable compact striped interactive className={`list-table${details.loading ? " is-stale" : ""}`}>
            <thead>
              <tr>
                <th aria-label="Cover" />
                <th>Title</th>
                <th>Authors</th>
                <th>Note</th>
                <th>Added</th>
                {list.mine && <th aria-label="Actions" />}
              </tr>
            </thead>
            <tbody>
              {books.map((entry) => {
                const b = entry.book;
                const year = formatYear(b.pubdate);
                return (
                  <tr
                    key={b.id}
                    className={`library-row${b.id === selectedBookId ? " is-selected" : ""}`}
                    tabIndex={0}
                    aria-current={b.id === selectedBookId ? "true" : undefined}
                    aria-label={`${b.title}: show details`}
                    onClick={(e) => onRowClick(e, b.id)}
                    onKeyDown={(e) => onRowKey(e, b.id)}
                  >
                    <td className="cover-cell">
                      <BookCover bookId={b.id} hasCover={b.hasCover} width={28} title={b.title} />
                    </td>
                    <td className="list-title-cell">
                      <div className="book-title" title={b.title}>
                        {b.title}
                      </div>
                      {(b.series || year) && (
                        <div className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL}`}>
                          {b.series && `${b.series}${b.seriesIndex != null ? ` #${b.seriesIndex}` : ""}`}
                          {b.series && year && " · "}
                          {year}
                        </div>
                      )}
                    </td>
                    <td className="list-authors-cell" title={b.authors.join(", ")}>
                      {formatAuthors(b.authors)}
                    </td>
                    <td className="list-note-cell">
                      {list.mine ? (
                        <EditableText
                          key={`${b.id}:${entry.note ?? ""}:${noteReset}`}
                          multiline
                          minLines={1}
                          maxLines={4}
                          maxLength={2000}
                          defaultValue={entry.note ?? ""}
                          placeholder="Add a note…"
                          onConfirm={(v) => void saveNote(entry, v)}
                        />
                      ) : (
                        entry.note ?? <span className={Classes.TEXT_MUTED}>—</span>
                      )}
                    </td>
                    <td className={`${Classes.TEXT_SMALL} nowrap`} title={formatDate(entry.addedAt)}>
                      {formatRelative(entry.addedAt)}
                    </td>
                    {list.mine && (
                      <td className="actions-cell">
                        <Tooltip content="Take it off the list" placement="top">
                          <Button
                            variant="minimal"
                            size="small"
                            icon="cross"
                            aria-label={`Take ${b.title} off the list`}
                            onClick={() => remove(entry)}
                          />
                        </Tooltip>
                      </td>
                    )}
                  </tr>
                );
              })}
            </tbody>
          </HTMLTable>
        )}
      </div>

      <Alert
        isOpen={confirmDelete}
        intent={Intent.DANGER}
        icon="trash"
        confirmButtonText="Delete list"
        cancelButtonText="Cancel"
        onCancel={() => setConfirmDelete(false)}
        onConfirm={async () => {
          setConfirmDelete(false);
          try {
            await api.deleteList(list.id);
            toastSuccess(`Deleted “${list.name}”`);
            onDeleted(list);
          } catch (err) {
            toastError("Couldn't delete the list", err);
          }
        }}
      >
        Delete the list <strong>{list.name}</strong>
        {list.shared ? ", which others can see" : ""}? The books stay in the library.
      </Alert>
    </div>
  );
}
