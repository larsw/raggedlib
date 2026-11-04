import { useCallback, useEffect, useRef, useState } from "react";
import { loadJson, saveJson } from "../lib/storage";

export interface ColumnSpec {
  id: string;
  label: string;
  /** px; a [flexible] column has no width of its own until the user sets one */
  defaultWidth: number;
  /** takes whatever room the other columns leave, until resized */
  flexible?: boolean;
}

export const COLUMN_MIN_WIDTH = 48;
/** A flexible column never gets narrower than this: below it, the table scrolls sideways instead. */
export const FLEX_MIN_WIDTH = 240;

export type ColumnWidths = Record<string, number>;

export function clampColumnWidth(width: number): number {
  return Math.round(Math.max(COLUMN_MIN_WIDTH, width));
}

/** Stored widths, keeping only known columns with a usable width. */
export function sanitizeWidths(raw: unknown, columns: ColumnSpec[]): ColumnWidths {
  if (!raw || typeof raw !== "object") return {};
  const out: ColumnWidths = {};
  for (const c of columns) {
    const w = (raw as Record<string, unknown>)[c.id];
    if (typeof w === "number" && Number.isFinite(w)) out[c.id] = clampColumnWidth(w);
  }
  return out;
}

/** The width a column is drawn at: the user's, else its default; undefined = flexible (the remaining room). */
export function columnWidth(widths: ColumnWidths, c: ColumnSpec): number | undefined {
  return widths[c.id] ?? (c.flexible ? undefined : c.defaultWidth);
}

/** The table's minimum width: every column at its width, a flexible one at [FLEX_MIN_WIDTH], plus [fixed] px. */
export function tableMinWidth(widths: ColumnWidths, columns: ColumnSpec[], fixed: number): number {
  return columns.reduce((sum, c) => sum + (columnWidth(widths, c) ?? FLEX_MIN_WIDTH), fixed);
}

/**
 * User-resizable table columns, remembered under [storageKey]. Put [handleProps] on a handle at a header cell's
 * right edge: pointer drag, arrow keys (Shift for bigger steps), double-click to go back to the default.
 */
export function useColumnWidths(storageKey: string, columns: ColumnSpec[]) {
  const [widths, setWidths] = useState<ColumnWidths>(() => sanitizeWidths(loadJson<unknown>(storageKey, null), columns));
  const [dragging, setDragging] = useState(false);
  const drag = useRef<{ id: string; startX: number; startWidth: number } | null>(null);

  useEffect(() => saveJson(storageKey, widths), [storageKey, widths]);

  const setWidth = useCallback(
    (id: string, w: number) => setWidths((cur) => (cur[id] === clampColumnWidth(w) ? cur : { ...cur, [id]: clampColumnWidth(w) })),
    [],
  );
  const reset = useCallback(
    (id: string) =>
      setWidths((cur) => {
        if (!(id in cur)) return cur;
        const { [id]: _, ...rest } = cur;
        return rest;
      }),
    [],
  );

  // while dragging, the whole page shows the resize cursor and nothing gets text-selected
  useEffect(() => {
    if (!dragging) return;
    document.body.classList.add("is-resizing-x");
    return () => document.body.classList.remove("is-resizing-x");
  }, [dragging]);

  /** the width the column has on screen now (a flexible one has no stored width to start from) */
  const shownWidth = (handle: HTMLElement, c: ColumnSpec) =>
    widths[c.id] ?? handle.parentElement?.getBoundingClientRect().width ?? c.defaultWidth;

  const handleProps = (c: ColumnSpec) => ({
    role: "separator",
    tabIndex: 0,
    "aria-orientation": "vertical" as const,
    "aria-label": `Resize ${c.label} column`,
    "aria-valuemin": COLUMN_MIN_WIDTH,
    "aria-valuenow": columnWidth(widths, c),
    title: "Drag to resize, double-click to reset",
    onPointerDown: (e: React.PointerEvent<HTMLElement>) => {
      if (e.button !== 0) return;
      e.preventDefault();
      e.stopPropagation();
      e.currentTarget.setPointerCapture(e.pointerId);
      drag.current = { id: c.id, startX: e.clientX, startWidth: shownWidth(e.currentTarget, c) };
      setDragging(true);
    },
    onPointerMove: (e: React.PointerEvent<HTMLElement>) => {
      const d = drag.current;
      if (d?.id === c.id) setWidth(c.id, d.startWidth + (e.clientX - d.startX));
    },
    onPointerUp: (e: React.PointerEvent<HTMLElement>) => endDrag(e),
    onPointerCancel: (e: React.PointerEvent<HTMLElement>) => endDrag(e),
    onKeyDown: (e: React.KeyboardEvent<HTMLElement>) => {
      const step = e.shiftKey ? 64 : 16;
      const delta = e.key === "ArrowRight" ? step : e.key === "ArrowLeft" ? -step : 0;
      if (delta) {
        e.preventDefault();
        setWidth(c.id, shownWidth(e.currentTarget, c) + delta);
      } else if (e.key === "Enter") {
        e.preventDefault();
        reset(c.id);
      }
    },
    onDoubleClick: () => reset(c.id),
    // the header cell may sit inside something clickable; a click on the handle is only ever a resize
    onClick: (e: React.MouseEvent) => e.stopPropagation(),
  });

  const endDrag = (e: React.PointerEvent<HTMLElement>) => {
    if (!drag.current) return;
    drag.current = null;
    setDragging(false);
    if (e.currentTarget.hasPointerCapture(e.pointerId)) e.currentTarget.releasePointerCapture(e.pointerId);
  };

  return { widths, dragging, handleProps };
}
