import type { SourceChunk } from "../api/types";
import { citedRefs } from "./citations";

/** A book behind some retrieved passages: what goes on a list from a search or a conversation. */
export interface FoundBook {
  bookId: number;
  title: string;
  authors: string[];
  /** how many of the passages are from it */
  passages: number;
  /** an answer cited one of them */
  cited: boolean;
}

/**
 * The books of one or more sets of passages (a search's results, the sources of each answer), in order of first
 * appearance, so the best match first. With its `answer`, a set's passages cited there ([n]) mark their book cited.
 */
export function booksOf(sets: readonly { sources: readonly SourceChunk[]; answer?: string }[]): FoundBook[] {
  const byId = new Map<number, FoundBook>();
  for (const { sources, answer } of sets) {
    const cited = answer ? new Set(citedRefs(answer, (r) => sources.some((s) => s.ref === r))) : null;
    for (const s of sources) {
      let book = byId.get(s.bookId);
      if (!book) {
        book = { bookId: s.bookId, title: s.title, authors: s.authors, passages: 0, cited: false };
        byId.set(s.bookId, book);
      }
      book.passages++;
      if (cited?.has(s.ref)) book.cited = true;
    }
  }
  return [...byId.values()];
}

/** A name for a new list from a question or query: one line, at most `max` characters. */
export function listNameFrom(text: string, max = 200): string {
  const line = text.replace(/\s+/g, " ").trim();
  return line.length <= max ? line : line.slice(0, max - 1).trimEnd() + "…";
}
