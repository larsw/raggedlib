import { useCallback, useEffect, useRef, useState } from "react";
import { loadJson, saveJson, STORAGE_KEYS } from "../lib/storage";

export const PANEL_DEFAULT_WIDTH = 400;
export const PANEL_MIN_WIDTH = 280;
/** The main column keeps at least this much room next to the panel. */
export const MAIN_MIN_WIDTH = 360;
/** Dragging the panel narrower than this (below the minimum) collapses it. */
const COLLAPSE_SNAP = 200;
/** Below this viewport width the panel overlays the main column and starts collapsed. */
export const OVERLAY_BREAKPOINT = 760;

interface Stored {
  width: number;
  collapsed: boolean;
}

/** Clamps [width] to the panel minimum and to what [containerWidth] leaves after the main column. */
export function clampPanelWidth(width: number, containerWidth: number | null): number {
  const max = containerWidth == null ? Infinity : Math.max(PANEL_MIN_WIDTH, containerWidth - MAIN_MIN_WIDTH);
  return Math.round(Math.min(Math.max(width, PANEL_MIN_WIDTH), max));
}

function initial(storageKey: string): Stored {
  const narrow = typeof window !== "undefined" && window.innerWidth < OVERLAY_BREAKPOINT;
  const stored = loadJson<Partial<Stored> | null>(storageKey, null);
  const width = typeof stored?.width === "number" && Number.isFinite(stored.width) ? stored.width : PANEL_DEFAULT_WIDTH;
  const collapsed = typeof stored?.collapsed === "boolean" ? stored.collapsed : narrow;
  return { width: clampPanelWidth(width, null), collapsed };
}

export interface SidePanelOptions {
  /** localStorage key for width and collapsed state */
  storageKey?: string;
  /** Called instead of collapsing when the panel is dragged far below its minimum (panels that close, not fold). */
  onSnapClose?: () => void;
}

/**
 * Width and collapsed state of a resizable side panel on the right of [containerRef], remembered across visits.
 * [handleProps] go on the drag handle: pointer drag, arrow keys (Shift for bigger steps), Home/End, double-click
 * to reset; dragging far below the minimum collapses the panel (or calls [SidePanelOptions.onSnapClose]).
 */
export function useSidePanel(containerRef: React.RefObject<HTMLElement | null>, options: SidePanelOptions = {}) {
  const storageKey = options.storageKey ?? STORAGE_KEYS.sourcesPanel;
  const [state, setState] = useState<Stored>(() => initial(storageKey));
  const onSnapClose = useRef(options.onSnapClose);
  onSnapClose.current = options.onSnapClose;
  const [dragging, setDragging] = useState(false);
  const drag = useRef<{ startX: number; startWidth: number } | null>(null);

  useEffect(() => saveJson(storageKey, state), [storageKey, state]);

  const containerWidth = () => containerRef.current?.getBoundingClientRect().width ?? null;
  const setWidth = useCallback(
    (w: number) => setState((s) => ({ ...s, width: clampPanelWidth(w, containerRef.current?.getBoundingClientRect().width ?? null) })),
    [containerRef],
  );
  const setCollapsed = useCallback((collapsed: boolean) => setState((s) => (s.collapsed === collapsed ? s : { ...s, collapsed })), []);

  const onPointerDown = (e: React.PointerEvent<HTMLElement>) => {
    if (e.button !== 0) return;
    e.preventDefault();
    e.currentTarget.setPointerCapture(e.pointerId);
    drag.current = { startX: e.clientX, startWidth: state.width };
    setDragging(true);
  };
  const onPointerMove = (e: React.PointerEvent<HTMLElement>) => {
    const d = drag.current;
    if (!d) return;
    // the panel is on the right: moving the handle left widens it
    const raw = d.startWidth + (d.startX - e.clientX);
    if (raw < COLLAPSE_SNAP) {
      if (onSnapClose.current) {
        drag.current = null;
        setDragging(false);
        onSnapClose.current();
      } else setCollapsed(true);
      return;
    }
    setState({ collapsed: false, width: clampPanelWidth(raw, containerWidth()) });
  };
  const endDrag = (e: React.PointerEvent<HTMLElement>) => {
    if (!drag.current) return;
    drag.current = null;
    setDragging(false);
    if (e.currentTarget.hasPointerCapture(e.pointerId)) e.currentTarget.releasePointerCapture(e.pointerId);
  };
  const onKeyDown = (e: React.KeyboardEvent<HTMLElement>) => {
    const step = e.shiftKey ? 64 : 16;
    const handled: Record<string, () => void> = {
      ArrowLeft: () => setWidth(state.width + step),
      ArrowRight: () => setWidth(state.width - step),
      Home: () => setWidth(PANEL_MIN_WIDTH),
      End: () => setWidth(Infinity),
    };
    const fn = handled[e.key];
    if (fn) {
      e.preventDefault();
      fn();
    }
  };

  // while dragging, the whole page shows the resize cursor and nothing gets text-selected
  useEffect(() => {
    if (!dragging) return;
    document.body.classList.add("is-resizing-x");
    return () => document.body.classList.remove("is-resizing-x");
  }, [dragging]);

  const cw = containerRef.current?.getBoundingClientRect().width ?? null;
  return {
    width: state.width,
    collapsed: state.collapsed,
    dragging,
    setCollapsed,
    handleProps: {
      role: "separator",
      tabIndex: 0,
      "aria-orientation": "vertical" as const,
      "aria-label": "Resize sources panel",
      "aria-valuemin": PANEL_MIN_WIDTH,
      "aria-valuemax": cw == null ? undefined : Math.max(PANEL_MIN_WIDTH, Math.round(cw - MAIN_MIN_WIDTH)),
      "aria-valuenow": state.width,
      title: "Drag to resize, double-click to reset",
      onPointerDown,
      onPointerMove,
      onPointerUp: endDrag,
      onPointerCancel: endDrag,
      onKeyDown,
      onDoubleClick: () => setWidth(PANEL_DEFAULT_WIDTH),
    },
  };
}
