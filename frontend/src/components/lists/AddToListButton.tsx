import { Button, Checkbox, Classes, Icon, InputGroup, Intent, Popover, Spinner, Tooltip } from "@blueprintjs/core";
import { useState } from "react";
import { api } from "../../api/client";
import type { FavoriteList, FavoriteLists } from "../../api/types";
import { useAsync } from "../../hooks/useAsync";
import { formatNumber } from "../../lib/format";
import { ErrorCallout } from "../ErrorState";
import { toastError, toastSuccess } from "../toaster";

const byName = (a: FavoriteList, b: FavoriteList) => a.name.localeCompare(b.name, undefined, { sensitivity: "base" }) || a.id - b.id;

interface Props {
  bookId: number;
  /** after the book was put on or taken off a list */
  onChanged?: () => void;
}

/** Book details: which of the caller's lists hold this book, as checkboxes, and a new list made on the spot. */
export function AddToListButton({ bookId, onChanged }: Props) {
  const lists = useAsync((signal) => api.lists(bookId, signal), [bookId]);
  const [busy, setBusy] = useState<ReadonlySet<number>>(new Set());
  const [newName, setNewName] = useState("");
  const [creating, setCreating] = useState(false);
  const data = lists.data;
  const mine = data?.mine ?? [];
  const onCount = mine.filter((l) => l.containsBook).length;

  const patch = (id: number, change: Partial<FavoriteList>) =>
    lists.setData((d): FavoriteLists | undefined => d && { ...d, mine: d.mine.map((l) => (l.id === id ? { ...l, ...change } : l)) });

  const toggle = async (list: FavoriteList) => {
    const adding = !list.containsBook;
    setBusy((s) => new Set(s).add(list.id));
    patch(list.id, { containsBook: adding }); // at once; put back if it fails
    try {
      const updated = adding ? await api.addToList(list.id, bookId) : await api.removeFromList(list.id, bookId);
      patch(list.id, { ...updated, containsBook: adding });
      onChanged?.();
    } catch (err) {
      patch(list.id, { containsBook: !adding });
      toastError(adding ? `Couldn't add it to “${list.name}”` : `Couldn't take it off “${list.name}”`, err);
    } finally {
      setBusy((s) => {
        const n = new Set(s);
        n.delete(list.id);
        return n;
      });
    }
  };

  const create = async () => {
    const name = newName.trim();
    if (!name) return;
    setCreating(true);
    try {
      const list = await api.createList({ name });
      const updated = await api.addToList(list.id, bookId);
      lists.setData((d) => d && { ...d, mine: [...d.mine, { ...updated, containsBook: true }].sort(byName) });
      setNewName("");
      toastSuccess(`Added to the new list “${updated.name}”`);
      onChanged?.();
    } catch (err) {
      toastError("Couldn't create the list", err);
    } finally {
      setCreating(false);
    }
  };

  const content = (
    <div className="add-to-list">
      <div className={`${Classes.TEXT_MUTED} add-to-list-title`}>Your lists</div>
      {lists.error && !data ? (
        <ErrorCallout error={lists.error} title="Couldn't load your lists" />
      ) : !data ? (
        <Spinner size={18} />
      ) : mine.length === 0 ? (
        <p className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL}`}>No lists yet: name one below to start.</p>
      ) : (
        <div className="add-to-list-items">
          {mine.map((l) => (
            <Checkbox
              key={l.id}
              checked={!!l.containsBook}
              disabled={busy.has(l.id)}
              onChange={() => toggle(l)}
              labelElement={
                <span className="add-to-list-item">
                  <span className="add-to-list-name">{l.name}</span>
                  {l.shared && (
                    <Tooltip content="Shared with everyone" placement="top">
                      <Icon icon="people" size={12} className={Classes.TEXT_MUTED} />
                    </Tooltip>
                  )}
                  <span className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL}`}>{formatNumber(l.bookCount)}</span>
                </span>
              }
            />
          ))}
        </div>
      )}
      <form
        className="add-to-list-new"
        onSubmit={(e) => {
          e.preventDefault();
          void create();
        }}
      >
        <InputGroup
          size="small"
          placeholder="New list…"
          aria-label="Name of a new list for this book"
          maxLength={200}
          value={newName}
          disabled={creating}
          onChange={(e) => setNewName(e.currentTarget.value)}
          rightElement={
            <Button
              type="submit"
              size="small"
              variant="minimal"
              icon="plus"
              loading={creating}
              disabled={!newName.trim()}
              aria-label="Create the list and add the book"
            />
          }
        />
      </form>
    </div>
  );

  return (
    <Popover content={content} placement="bottom-start" popoverClassName="add-to-list-popover" onOpening={() => lists.reload()}>
      <Button
        icon="bookmark"
        intent={onCount > 0 ? Intent.PRIMARY : Intent.NONE}
        variant={onCount > 0 ? "outlined" : "solid"}
        endIcon="caret-down"
        text={onCount > 0 ? `On ${onCount} ${onCount === 1 ? "list" : "lists"}` : "Add to list"}
      />
    </Popover>
  );
}
