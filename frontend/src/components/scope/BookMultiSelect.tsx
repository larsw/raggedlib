import { MenuItem, Spinner } from "@blueprintjs/core";
import { MultiSelect, type ItemRenderer } from "@blueprintjs/select";
import { useEffect, useState } from "react";
import { api, errorMessage, isAbortError } from "../../api/client";
import type { BookRef } from "../../hooks/useScope";
import { useDebounced } from "../../hooks/useDebounced";
import { formatAuthors } from "../../lib/format";

interface Props {
  selected: BookRef[];
  onChange: (books: BookRef[]) => void;
}

function truncate(s: string, n: number) {
  return s.length > n ? `${s.slice(0, n - 1)}…` : s;
}

/** Book picker with server-side search (GET /api/books?q=&size=20). */
export function BookMultiSelect({ selected, onChange }: Props) {
  const [query, setQuery] = useState("");
  const debounced = useDebounced(query, 250);
  const [items, setItems] = useState<BookRef[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();
  const [open, setOpen] = useState(false);

  useEffect(() => {
    if (!open) return;
    const ctrl = new AbortController();
    setLoading(true);
    api
      .books({ q: debounced.trim() || undefined, size: 20, sort: "title" }, ctrl.signal)
      .then((page) => {
        setItems(page.items.map((b) => ({ id: b.id, title: b.title, authors: b.authors })));
        setError(undefined);
      })
      .catch((err) => {
        if (!isAbortError(err)) setError(errorMessage(err));
      })
      .finally(() => {
        if (!ctrl.signal.aborted) setLoading(false);
      });
    return () => ctrl.abort();
  }, [debounced, open]);

  const isSelected = (b: BookRef) => selected.some((s) => s.id === b.id);

  const toggle = (b: BookRef) => {
    onChange(isSelected(b) ? selected.filter((s) => s.id !== b.id) : [...selected, b]);
  };

  const renderItem: ItemRenderer<BookRef> = (book, { handleClick, handleFocus, modifiers, ref }) => (
    <MenuItem
      key={book.id}
      ref={ref}
      roleStructure="listoption"
      selected={isSelected(book)}
      active={modifiers.active}
      onClick={handleClick}
      onFocus={handleFocus}
      text={
        <div className="book-option">
          <div className="book-option-title">{book.title}</div>
          <div className="book-option-authors">{formatAuthors(book.authors)}</div>
        </div>
      }
    />
  );

  return (
    <MultiSelect<BookRef>
      className="scope-books"
      items={items}
      selectedItems={selected}
      itemsEqual={(a, b) => a.id === b.id}
      itemRenderer={renderItem}
      // filtering happens on the server
      itemListPredicate={(_, all) => all}
      onQueryChange={setQuery}
      query={query}
      onItemSelect={toggle}
      onRemove={(b) => onChange(selected.filter((s) => s.id !== b.id))}
      onClear={() => onChange([])}
      tagRenderer={(b) => truncate(b.title, 32)}
      tagInputProps={{
        tagProps: { minimal: true, interactive: false },
        leftIcon: "book",
        inputProps: { "aria-label": "Limit to books" },
      }}
      placeholder={selected.length ? "" : "All books"}
      resetOnSelect={false}
      popoverProps={{ minimal: true, placement: "bottom-start", onOpening: () => setOpen(true), onClosed: () => setOpen(false) }}
      popoverContentProps={{ className: "scope-popover" }}
      noResults={
        <MenuItem
          disabled
          roleStructure="listoption"
          text={loading ? <Spinner size={16} /> : error ? `Error: ${error}` : "No matching books"}
        />
      }
      fill
    />
  );
}
