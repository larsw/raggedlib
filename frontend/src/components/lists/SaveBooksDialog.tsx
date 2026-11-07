import {
  Button,
  Callout,
  Checkbox,
  Classes,
  Dialog,
  DialogBody,
  DialogFooter,
  FormGroup,
  HTMLSelect,
  InputGroup,
  Intent,
  SegmentedControl,
  Spinner,
  Switch,
  Tag,
} from "@blueprintjs/core";
import { useEffect, useMemo, useState } from "react";
import { api, errorMessage } from "../../api/client";
import type { FavoriteList } from "../../api/types";
import { useAsync } from "../../hooks/useAsync";
import { formatNumber } from "../../lib/format";
import type { FoundBook } from "../../lib/foundBooks";
import { showToast } from "../toaster";

const MAX_NAME = 200;

type Target = "new" | "existing";

interface Props {
  isOpen: boolean;
  onClose: () => void;
  /** the books to choose from, best match first */
  books: FoundBook[];
  /** where they were found, after "Books with passages found": `for “borrow checker”`, `in this conversation` */
  foundWhere: string;
  /** the name a new list starts with */
  suggestedName: string;
}

const plural = (n: number, one: string, many = `${one}s`) => `${formatNumber(n)} ${n === 1 ? one : many}`;

function openList(id: number) {
  window.location.hash = `/lists/${id}`;
}

/** Puts the books of a search or a conversation on a new list, or on one of the caller's lists. */
export function SaveBooksDialog({ isOpen, onClose, books, foundWhere, suggestedName }: Props) {
  const lists = useAsync((signal) => (isOpen ? api.lists(undefined, signal) : Promise.resolve(undefined)), [isOpen]);
  const mine = useMemo(() => lists.data?.mine ?? [], [lists.data]);
  const [chosen, setChosen] = useState<ReadonlySet<number>>(new Set());
  const [target, setTarget] = useState<Target>("new");
  const [name, setName] = useState("");
  const [shared, setShared] = useState(false);
  const [listId, setListId] = useState<number | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!isOpen) return;
    setChosen(new Set(books.map((b) => b.bookId)));
    setTarget("new");
    setName(suggestedName);
    setShared(false);
    setListId(null);
    setSaving(false);
    setError(null);
  }, [isOpen, books, suggestedName]);

  // the list worked on last is the likeliest one to add to
  const latest = useMemo(
    () => mine.reduce<FavoriteList | null>((a, l) => (!a || l.updatedAt > a.updatedAt ? l : a), null),
    [mine],
  );
  const existing = mine.find((l) => l.id === listId) ?? latest;

  const picked = books.filter((b) => chosen.has(b.bookId)).map((b) => b.bookId);
  const anyCited = books.some((b) => b.cited);
  const ready = picked.length > 0 && !saving && (target === "new" ? name.trim() !== "" : existing != null);

  const toggle = (id: number) =>
    setChosen((s) => {
      const next = new Set(s);
      if (!next.delete(id)) next.add(id);
      return next;
    });

  const save = async () => {
    if (!ready) return;
    setSaving(true);
    setError(null);
    try {
      if (target === "new") {
        const list = await api.createList({ name: name.trim(), shared, bookIds: picked });
        showToast({
          intent: Intent.SUCCESS,
          icon: "tick-circle",
          message: `Created “${list.name}” with ${plural(list.bookCount, "book")}`,
          action: { text: "Open list", onClick: () => openList(list.id) },
        });
      } else if (existing) {
        const { list, added, alreadyOn } = await api.addBooksToList(existing.id, picked);
        showToast({
          intent: added > 0 ? Intent.SUCCESS : Intent.NONE,
          icon: added > 0 ? "tick-circle" : "info-sign",
          message:
            added === 0
              ? `${alreadyOn === 1 ? "That book was" : `All ${formatNumber(alreadyOn)} books were`} already on “${list.name}”`
              : `Added ${plural(added, "book")} to “${list.name}”${alreadyOn > 0 ? ` (${formatNumber(alreadyOn)} already on it)` : ""}`,
          action: { text: "Open list", onClick: () => openList(list.id) },
        });
      }
      onClose();
    } catch (err) {
      setError(errorMessage(err));
      setSaving(false);
    }
  };

  return (
    <Dialog isOpen={isOpen} onClose={onClose} title="Save books to a list" icon="bookmark" className="save-books-dialog">
      <form
        onSubmit={(e) => {
          e.preventDefault();
          void save();
        }}
      >
        <DialogBody>
          <div className="save-books-head">
            <span className={Classes.TEXT_MUTED}>
              Books with passages found {foundWhere}: {formatNumber(picked.length)} of {formatNumber(books.length)} chosen
            </span>
            <span className="save-books-select">
              <Button size="small" variant="minimal" text="All" onClick={() => setChosen(new Set(books.map((b) => b.bookId)))} />
              {anyCited && (
                <Button
                  size="small"
                  variant="minimal"
                  text="Cited"
                  title="Only the books an answer cites"
                  onClick={() => setChosen(new Set(books.filter((b) => b.cited).map((b) => b.bookId)))}
                />
              )}
              <Button size="small" variant="minimal" text="None" onClick={() => setChosen(new Set())} />
            </span>
          </div>
          <div className="save-books-list" role="group" aria-label="Books to save">
            {books.map((b) => (
              <Checkbox
                key={b.bookId}
                checked={chosen.has(b.bookId)}
                onChange={() => toggle(b.bookId)}
                labelElement={
                  <span className="save-books-item">
                    <span className="save-books-title">
                      <span className={Classes.TEXT_OVERFLOW_ELLIPSIS}>{b.title}</span>
                      {b.authors.length > 0 && (
                        <span className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL} ${Classes.TEXT_OVERFLOW_ELLIPSIS}`}>
                          {b.authors.join(", ")}
                        </span>
                      )}
                    </span>
                    {b.cited && (
                      <Tag minimal intent={Intent.PRIMARY} title="An answer cites it">
                        cited
                      </Tag>
                    )}
                    <span className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL} save-books-count`}>
                      {plural(b.passages, "passage")}
                    </span>
                  </span>
                }
              />
            ))}
          </div>

          <SegmentedControl
            className="save-books-target"
            fill
            size="small"
            value={target}
            onValueChange={(v) => setTarget(v as Target)}
            options={[
              { label: "New list", value: "new" },
              { label: "Add to one of my lists", value: "existing", disabled: lists.data != null && mine.length === 0 },
            ]}
          />
          {target === "new" ? (
            <>
              <FormGroup label="Name" labelFor="save-books-name">
                <InputGroup
                  id="save-books-name"
                  maxLength={MAX_NAME}
                  value={name}
                  onChange={(e) => setName(e.currentTarget.value)}
                />
              </FormGroup>
              <Switch
                checked={shared}
                onChange={(e) => setShared(e.currentTarget.checked)}
                label="Share with everyone (every signed-in user can see it; only you change it)"
              />
            </>
          ) : !lists.data ? (
            lists.error ? (
              <Callout intent={Intent.DANGER} icon="error" compact>
                Couldn't load your lists: {errorMessage(lists.error)}
              </Callout>
            ) : (
              <Spinner size={20} />
            )
          ) : (
            <FormGroup label="List" labelFor="save-books-list-select">
              <HTMLSelect
                id="save-books-list-select"
                fill
                value={existing?.id ?? ""}
                onChange={(e) => setListId(Number(e.currentTarget.value))}
                options={mine.map((l) => ({
                  value: l.id,
                  label: `${l.name} (${plural(l.bookCount, "book")}${l.shared ? ", shared" : ""})`,
                }))}
              />
            </FormGroup>
          )}
          {error && (
            <Callout intent={Intent.DANGER} icon="error" compact className="save-books-error">
              {error}
            </Callout>
          )}
        </DialogBody>
        <DialogFooter
          actions={
            <>
              <Button text="Cancel" onClick={onClose} disabled={saving} />
              <Button
                type="submit"
                intent={Intent.PRIMARY}
                loading={saving}
                disabled={!ready}
                text={
                  target === "new"
                    ? `Create list with ${plural(picked.length, "book")}`
                    : `Add ${plural(picked.length, "book")}`
                }
              />
            </>
          }
        />
      </form>
    </Dialog>
  );
}
