import { describe, expect, test } from "bun:test";
import {
  clampColumnWidth,
  COLUMN_MIN_WIDTH,
  columnWidth,
  FLEX_MIN_WIDTH,
  sanitizeWidths,
  tableMinWidth,
  type ColumnSpec,
} from "./useColumnWidths";

const COLS: ColumnSpec[] = [
  { id: "title", label: "Title", defaultWidth: 400, flexible: true },
  { id: "authors", label: "Authors", defaultWidth: 200 },
  { id: "tags", label: "Tags", defaultWidth: 240 },
];

describe("column widths", () => {
  test("widths are whole pixels, never below the minimum", () => {
    expect(clampColumnWidth(10)).toBe(COLUMN_MIN_WIDTH);
    expect(clampColumnWidth(123.6)).toBe(124);
  });
  test("stored widths keep only known columns with numeric widths", () => {
    expect(sanitizeWidths({ authors: 180.4, gone: 300, tags: "wide", title: 5 }, COLS)).toEqual({
      authors: 180,
      title: COLUMN_MIN_WIDTH,
    });
    expect(sanitizeWidths(null, COLS)).toEqual({});
    expect(sanitizeWidths([1, 2], COLS)).toEqual({});
  });
  test("a flexible column has no width until set; others fall back to their default", () => {
    expect(columnWidth({}, COLS[0]!)).toBeUndefined();
    expect(columnWidth({ title: 500 }, COLS[0]!)).toBe(500);
    expect(columnWidth({}, COLS[1]!)).toBe(200);
  });
  test("the table's minimum width counts a flexible column at its floor, plus fixed columns", () => {
    expect(tableMinWidth({}, COLS, 100)).toBe(100 + FLEX_MIN_WIDTH + 200 + 240);
    expect(tableMinWidth({ title: 300, tags: 100 }, COLS, 100)).toBe(100 + 300 + 200 + 100);
  });
});
