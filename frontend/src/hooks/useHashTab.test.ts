import { describe, expect, test } from "bun:test";
import { bookRefFromHash, listIdFromHash, tabFromHash, tabsFor } from "./useHashTab";

describe("hash routes", () => {
  test("tabs, with or without a slash and a sub-path", () => {
    expect(tabFromHash("#/library")).toBe("library");
    expect(tabFromHash("#lists")).toBe("lists");
    expect(tabFromHash("#/lists/12")).toBe("lists");
    expect(tabFromHash("#/search?q=x")).toBe("search");
    expect(tabFromHash("")).toBe("ask");
    expect(tabFromHash("#/admin")).toBe("ask");
  });

  test("a list id after #/lists/", () => {
    expect(listIdFromHash("#/lists/12")).toBe(12);
    expect(listIdFromHash("#lists/7/")).toBe(7);
    expect(listIdFromHash("#/lists")).toBeNull();
    expect(listIdFromHash("#/lists/abc")).toBeNull();
    expect(listIdFromHash("#/library/12")).toBeNull();
  });

  test("a book details link after #/library", () => {
    expect(bookRefFromHash("#/library?bookId=12&libraryId=3")).toEqual({ bookId: 12, libraryId: 3 });
    expect(bookRefFromHash("#/library?bookId=0&libraryId=3")).toBeNull();
    expect(bookRefFromHash("#/search?bookId=12&libraryId=3")).toBeNull();
  });

  test("Indexing is for curators only", () => {
    expect(tabsFor(true)).toContain("indexing");
    expect(tabsFor(false)).not.toContain("indexing");
    expect(tabsFor(false)).toContain("lists");
  });
});
