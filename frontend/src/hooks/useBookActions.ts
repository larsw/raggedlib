import { useCallback, useState } from "react";
import { api } from "../api/client";
import type { Book } from "../api/types";
import { useApp } from "../AppContext";
import { toastError, toastSuccess } from "../components/toaster";

/** Ask about / search in one book: narrows the scope to it and switches tab. */
export function useBookNavigation() {
  const { setScope, navigate } = useApp();
  const focus = useCallback(
    (book: Book, tab: "ask" | "search") => {
      setScope((s) => ({ ...s, books: [{ id: book.id, title: book.title, authors: book.authors }], tags: [], publishers: [] }));
      navigate(tab);
    },
    [setScope, navigate],
  );
  const askAbout = useCallback((book: Book) => focus(book, "ask"), [focus]);
  const searchIn = useCallback((book: Book) => focus(book, "search"), [focus]);
  return { askAbout, searchIn };
}

/** Queues books for reindexing (curators), with toasts; `onReindexed` gets the book's new state. */
export function useReindex(onReindexed?: (book: Book) => void) {
  const [reindexing, setReindexing] = useState<ReadonlySet<number>>(new Set());
  const reindex = async (book: Book) => {
    setReindexing((s) => new Set(s).add(book.id));
    try {
      const updated = await api.reindex(book.id);
      onReindexed?.(updated);
      toastSuccess(`Queued “${book.title}” for reindexing`);
    } catch (err) {
      toastError("Reindex failed", err);
    } finally {
      setReindexing((s) => {
        const n = new Set(s);
        n.delete(book.id);
        return n;
      });
    }
  };
  return { reindexing, reindex };
}
