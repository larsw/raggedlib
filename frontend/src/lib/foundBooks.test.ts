import { describe, expect, test } from "bun:test";
import type { SourceChunk } from "../api/types";
import { booksOf, listNameFrom } from "./foundBooks";

const passage = (ref: number, bookId: number, title = `Book ${bookId}`): SourceChunk => ({
  ref,
  chunkId: `c${bookId}-${ref}`,
  bookId,
  title,
  authors: ["Ann Author"],
  page: null,
  section: null,
  text: "…",
  score: 1,
  scoreKind: "FUSED",
});

describe("booksOf", () => {
  test("one entry per book, in order of first appearance, counting its passages", () => {
    const books = booksOf([{ sources: [passage(1, 7), passage(2, 3), passage(3, 7)] }]);
    expect(books.map((b) => [b.bookId, b.passages])).toEqual([
      [7, 2],
      [3, 1],
    ]);
    expect(books.every((b) => !b.cited)).toBe(true);
  });

  test("across answers, a book is cited if any answer cites one of its passages", () => {
    const books = booksOf([
      { sources: [passage(1, 7), passage(2, 3)], answer: "As [2] shows." },
      { sources: [passage(1, 3), passage(2, 9)], answer: "See [2], and [5] which isn't a source." },
    ]);
    expect(books.map((b) => [b.bookId, b.passages, b.cited])).toEqual([
      [7, 1, false],
      [3, 2, true],
      [9, 1, true],
    ]);
  });

  test("nothing found, nothing to save", () => {
    expect(booksOf([])).toEqual([]);
    expect(booksOf([{ sources: [], answer: "No idea [1]." }])).toEqual([]);
  });
});

describe("listNameFrom", () => {
  test("one line, cut to the limit", () => {
    expect(listNameFrom("  what is\n a  borrow checker? ")).toBe("what is a borrow checker?");
    const long = listNameFrom("x".repeat(300), 200);
    expect(long.length).toBe(200);
    expect(long.endsWith("…")).toBe(true);
  });
});
