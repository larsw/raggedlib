import {
  AnchorButton,
  Button,
  Classes,
  Drawer,
  Menu,
  MenuDivider,
  MenuItem,
  NonIdealState,
  Popover,
  Slider,
  Spinner,
  Tooltip,
} from "@blueprintjs/core";
import type { TocItem, View } from "foliate-js/view.js";
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { api, bookFileUrl, errorMessage } from "../../api/client";
import type { Book } from "../../api/types";
import { useAsync } from "../../hooks/useAsync";
import type { Theme } from "../../hooks/useTheme";
import { formatAuthors } from "../../lib/format";
import { isReaderFormat, readerFileName, readerFormat, type ReaderRequest, type ReaderTarget } from "../../lib/reader";
import { loadJson, saveJson, STORAGE_KEYS } from "../../lib/storage";
import { ErrorState } from "../ErrorState";
import { loadFoliate } from "./foliate";
import { locateHeading, locatePassage } from "./locate";

interface Props {
  request: ReaderRequest;
  isOpen: boolean;
  theme: Theme;
  onClose: () => void;
}

/** In-app ebook reader: foliate-js in a drawer over the current view, so Ask/Search/Library keep their state. */
export default function ReaderDrawer({ request, isOpen, theme, onClose }: Props) {
  return (
    <Drawer
      isOpen={isOpen}
      onClose={onClose}
      size="min(1100px, 100%)"
      className="reader-drawer"
      canOutsideClickClose
      shouldReturnFocusOnClose
    >
      <Reader request={request} theme={theme} onClose={onClose} />
    </Drawer>
  );
}

// foliate-js lays the book out again inside its ResizeObserver callback, so every resize of the reader (window, drawer)
// makes the browser report "ResizeObserver loop completed with undelivered notifications". The warning is benign
// (the layout lands a frame later) and browsers don't log it, but dev overlays treat it as an uncaught error. Keep
// it from reaching them; capture listeners on the target run before the overlay's.
window.addEventListener(
  "error",
  (e) => {
    if (e.message?.startsWith("ResizeObserver loop")) e.stopImmediatePropagation();
  },
  true,
);

const disposed = new WeakSet<View>();

/**
 * Closes and removes a foliate view, once (a second close() throws). Must run while the view is still in the
 * document: foliate only stops observing the book page's size if the page's iframe is still attached. And its
 * paginator observes an inner container but its destroy() unobserves the element itself, so that observer outlives
 * it; its callback calls `this.render()`, which a no-op `render` on the destroyed instance silences. Otherwise both
 * re-render the detached book every frame for a while ("ResizeObserver loop" errors).
 */
function dispose(view: View) {
  if (disposed.has(view)) return;
  disposed.add(view);
  const renderer = view.renderer as (View["renderer"] & { render?: () => void }) | undefined;
  view.close();
  if (renderer) renderer.render = () => {};
  view.remove();
}

// ── preferences and reading positions ────────────────────────────────────────────────────────

type Flow = "paginated" | "scrolled";
interface Prefs {
  flow: Flow;
  /** percent of the book's base font size */
  fontSize: number;
}
const DEFAULT_PREFS: Prefs = { flow: "paginated", fontSize: 100 };
const FONT_MIN = 70;
const FONT_MAX = 180;
const MAX_POSITIONS = 200;

function loadPrefs(): Prefs {
  const p = loadJson<Partial<Prefs>>(STORAGE_KEYS.readerPrefs, {});
  return {
    flow: p.flow === "scrolled" ? "scrolled" : "paginated",
    fontSize: typeof p.fontSize === "number" ? Math.min(FONT_MAX, Math.max(FONT_MIN, p.fontSize)) : 100,
  };
}

const positionKey = (bookId: number, format: string) => `${bookId}:${format}`;

function loadPosition(key: string): string | null {
  return loadJson<Record<string, string>>(STORAGE_KEYS.readerPositions, {})[key] ?? null;
}

function savePosition(key: string, cfi: string) {
  const all = loadJson<Record<string, string>>(STORAGE_KEYS.readerPositions, {});
  delete all[key]; // re-insert last, so the oldest positions are the ones dropped
  all[key] = cfi;
  const keys = Object.keys(all);
  for (const k of keys.slice(0, Math.max(0, keys.length - MAX_POSITIONS))) delete all[k];
  saveJson(STORAGE_KEYS.readerPositions, all);
}

// ── book styling ─────────────────────────────────────────────────────────────────────────────

/** CSS injected into the book's pages, from the app's theme variables, so the book follows dark/light/sepia. */
function bookCss(theme: Theme, fontSize: number): string {
  const css = getComputedStyle(document.documentElement);
  const v = (name: string, fallback: string) => css.getPropertyValue(name).trim() || fallback;
  const bg = v("--app-surface", "#fff");
  const text = v("--app-text", "#1c2127");
  const link = v("--app-accent", "#2d72d2");
  const code = v("--app-code-bg", "transparent");
  // Dark pages need the book's own (dark) text colors overridden; light and sepia pages keep them.
  const force =
    theme === "dark"
      ? `body *:not(img):not(svg):not(svg *):not(video) { color: inherit !important; background-color: transparent !important; border-color: rgba(255,255,255,.2) !important; }
         pre, code { background-color: ${code} !important; }`
      : "";
  return `
    @namespace epub "http://www.idpf.org/2007/ops";
    html { color-scheme: ${theme === "dark" ? "dark" : "light"}; font-size: ${fontSize}% !important; }
    html, body { background: ${bg} !important; color: ${text} !important; }
    ${force}
    a:any-link { color: ${link} !important; }
    p, li, blockquote, dd { line-height: 1.55; hyphens: auto; -webkit-hyphens: auto; widows: 2; orphans: 2; }
    pre { white-space: pre-wrap !important; overflow-wrap: anywhere; }
    img, svg, video { max-width: 100%; }
    aside[epub|type~="footnote"] { display: none; }
  `;
}

// ── reader ───────────────────────────────────────────────────────────────────────────────────

type Phase = { kind: "loading" } | { kind: "ready" } | { kind: "error"; error: unknown };

interface Location {
  label: string | null;
  href: string | null;
  fraction: number;
}

/** True for keys typed into controls of the drawer, which must keep their own arrow-key behavior. */
const isControl = (target: EventTarget | null) =>
  target instanceof Element && !!target.closest("input, textarea, select, [contenteditable], .bp6-slider, .bp6-menu");

function Reader({ request, theme, onClose }: { request: ReaderRequest; theme: Theme; onClose: () => void }) {
  const { bookId } = request;
  const book = useAsync((signal) => api.book(bookId, signal), [bookId]);
  const [formatOverride, setFormatOverride] = useState<string | null>(null);
  const format =
    formatOverride ?? (request.format && isReaderFormat(request.format) ? request.format.toUpperCase() : null) ??
    (book.data ? readerFormat(book.data.formats, book.data.sourceFormat) : null);

  const hostRef = useRef<HTMLDivElement>(null);
  const viewRef = useRef<View | null>(null);
  const [phase, setPhase] = useState<Phase>({ kind: "loading" });
  const [toc, setToc] = useState<TocItem[]>([]);
  const [location, setLocation] = useState<Location | null>(null);
  const [locating, setLocating] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const [prefs, setPrefs] = useState<Prefs>(loadPrefs);
  /** the cited passage's highlight, redrawn whenever its section is rendered again */
  const highlightRef = useRef<{ value: string; index: number } | null>(null);
  /** whether the reader has shown a page yet (a position or a target) */
  const shownRef = useRef(false);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  // Open the book: fetch the file, load foliate-js (lazily: it's only needed here) and render.
  useEffect(() => {
    const host = hostRef.current;
    if (!format || !host) return;
    const ac = new AbortController();
    let view: View | null = null;
    let saveTimer: ReturnType<typeof setTimeout> | undefined;
    const key = positionKey(bookId, format);
    setPhase({ kind: "loading" });
    setLocation(null);
    setNote(null);
    shownRef.current = false;
    highlightRef.current = null;

    (async () => {
      const [blob, foliate] = await Promise.all([api.bookFile(bookId, format, ac.signal), loadFoliate()]);
      const { Overlayer } = foliate.overlayer;
      if (ac.signal.aborted) return;
      const v = document.createElement("foliate-view") as View;
      view = v;
      v.className = "reader-view";
      v.addEventListener("relocate", (e) => {
        const d = (e as CustomEvent<View["lastLocation"]>).detail;
        if (!d) return;
        setLocation({ label: d.tocItem?.label?.trim() || null, href: d.tocItem?.href ?? null, fraction: d.fraction ?? 0 });
        clearTimeout(saveTimer);
        saveTimer = setTimeout(() => savePosition(key, d.cfi), 400);
      });
      v.addEventListener("draw-annotation", (e) => {
        const { draw } = (e as CustomEvent<{ draw: (fn: unknown, o?: object) => void }>).detail;
        draw(Overlayer.highlight, { color: getComputedStyle(document.documentElement).getPropertyValue("--app-accent").trim() || "#2d72d2" });
      });
      v.addEventListener("create-overlay", (e) => {
        const h = highlightRef.current;
        if (h && (e as CustomEvent<{ index: number }>).detail.index === h.index) void v.addAnnotation({ value: h.value });
      });
      // keys pressed while the book's iframe has focus never reach the page: forward them
      v.addEventListener("load", (e) => {
        const { doc } = (e as CustomEvent<{ doc: Document }>).detail;
        doc.addEventListener("keydown", (ke) => handleKey(ke, v, { onEscape: () => onCloseRef.current() }));
      });
      host.append(v);
      await v.open(new File([blob], readerFileName(format), { type: blob.type }));
      if (ac.signal.aborted) return;
      setToc(v.book.toc ?? []);
      viewRef.current = v;
      setPhase({ kind: "ready" });
    })().catch((error: unknown) => {
      if (!ac.signal.aborted) setPhase({ kind: "error", error });
    });

    return () => {
      ac.abort();
      clearTimeout(saveTimer);
      viewRef.current = null;
      if (view) dispose(view);
    };
  }, [bookId, format]);

  // On unmount React detaches the drawer's DOM before running effect cleanups, but layout effect cleanups run
  // before: dispose of the book there, while it's still attached (see dispose()).
  useLayoutEffect(
    () => () => {
      const host = hostRef.current;
      host?.querySelectorAll<View>("foliate-view").forEach(dispose);
    },
    [],
  );

  // Layout and styles: on open and whenever the theme or preferences change.
  useEffect(() => {
    const view = viewRef.current;
    if (phase.kind !== "ready" || !view) return;
    const r = view.renderer;
    r.setAttribute("flow", prefs.flow);
    r.setAttribute("gap", "6%");
    r.setAttribute("margin", "32px");
    r.setAttribute("max-inline-size", "720px");
    r.setAttribute("max-column-count", "2");
    r.setStyles(bookCss(theme, prefs.fontSize));
  }, [phase.kind, theme, prefs]);

  useEffect(() => saveJson(STORAGE_KEYS.readerPrefs, prefs), [prefs]);

  // Go where the request asks: the cited passage, a section, or the last reading position.
  const target = request.target;
  const goToTarget = useCallback(
    async (view: View, t: ReaderTarget | undefined, signal: AbortSignal) => {
      const show = async (to: string | number) => {
        await view.goTo(to);
        shownRef.current = true;
      };
      const fallback = async () => {
        if (!shownRef.current) {
          await view.init({ lastLocation: loadPosition(positionKey(bookId, format!)), showTextStart: true });
          shownRef.current = true;
        }
      };
      setNote(null);
      if (!t) return fallback();
      setLocating(true);
      try {
        if (t.kind === "passage") {
          const { found, sectionIndex } = await locatePassage(view.book, t, signal);
          if (signal.aborted) return;
          if (found) {
            const cfi = view.getCFI(found.index, found.range);
            if (highlightRef.current) void view.deleteAnnotation({ value: highlightRef.current.value });
            highlightRef.current = { value: cfi, index: found.index };
            await show(cfi);
            void view.addAnnotation({ value: cfi });
          } else if (sectionIndex != null) {
            await show(sectionIndex);
            setNote(`Couldn't find the exact passage; showing its section${t.section ? ` “${t.section}”` : ""}.`);
          } else {
            await fallback();
            setNote("Couldn't find the passage in this file of the book.");
          }
        } else {
          const found = await locateHeading(view.book, t.title, signal);
          if (signal.aborted) return;
          if (found && "href" in found) await show(found.href);
          else if (found) await show(view.getCFI(found.index, found.range));
          else {
            await fallback();
            setNote(`Couldn't find the section “${t.title}”.`);
          }
        }
      } finally {
        if (!signal.aborted) setLocating(false);
      }
    },
    [bookId, format],
  );

  useEffect(() => {
    const view = viewRef.current;
    if (phase.kind !== "ready" || !view) return;
    const ac = new AbortController();
    goToTarget(view, target, ac.signal).catch((err: unknown) => {
      if (!ac.signal.aborted) setNote(`Couldn't open the location: ${errorMessage(err)}`);
    });
    return () => ac.abort();
  }, [phase.kind, target, goToTarget]);

  // Arrow keys turn pages while the drawer is open (unless a control has focus).
  useEffect(() => {
    if (phase.kind !== "ready") return;
    const onKey = (e: KeyboardEvent) => {
      const view = viewRef.current;
      if (view && !isControl(e.target)) handleKey(e, view);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [phase.kind]);

  const view = viewRef.current;
  const b = book.data;
  const otherFormats = (b?.formats ?? []).filter((f) => isReaderFormat(f) && f !== format);
  const subtitle = locating
    ? request.target?.kind === "passage"
      ? "Finding the cited passage…"
      : "Finding the section…"
    : location?.label || (b ? formatAuthors(b.authors) : "");

  return (
    <div className="reader">
      <div className="reader-header">
        <div className="reader-titles">
          <div className="reader-title" title={request.title}>
            {request.title}
          </div>
          <div className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL} reader-subtitle`}>
            {locating && <Spinner size={12} />}
            <span className={Classes.TEXT_OVERFLOW_ELLIPSIS}>{subtitle}</span>
          </div>
        </div>
        <div className="reader-tools">
          <Popover
            placement="bottom-end"
            disabled={phase.kind !== "ready" || toc.length === 0}
            content={<TocMenu toc={toc} current={location?.href ?? null} onSelect={(href) => void view?.goTo(href)} />}
          >
            <Tooltip content="Contents" placement="bottom">
              <Button variant="minimal" icon="properties" aria-label="Table of contents" disabled={phase.kind !== "ready" || toc.length === 0} />
            </Tooltip>
          </Popover>
          {target?.kind === "passage" && (
            <Tooltip content="Back to the cited passage" placement="bottom">
              <Button
                variant="minimal"
                icon="locate"
                aria-label="Back to the cited passage"
                disabled={phase.kind !== "ready" || locating}
                onClick={() => {
                  const h = highlightRef.current;
                  if (view && h) void view.goTo(h.value);
                }}
              />
            </Tooltip>
          )}
          <Popover
            placement="bottom-end"
            content={
              <Menu>
                <MenuDivider title="Layout" />
                <MenuItem
                  icon="book"
                  text="Pages"
                  roleStructure="listoption"
                  selected={prefs.flow === "paginated"}
                  onClick={() => setPrefs((p) => ({ ...p, flow: "paginated" }))}
                />
                <MenuItem
                  icon="th-list"
                  text="Scroll"
                  roleStructure="listoption"
                  selected={prefs.flow === "scrolled"}
                  onClick={() => setPrefs((p) => ({ ...p, flow: "scrolled" }))}
                />
                <MenuDivider title={`Text size · ${prefs.fontSize}%`} />
                <MenuItem
                  icon="zoom-out"
                  text="Smaller"
                  shouldDismissPopover={false}
                  disabled={prefs.fontSize <= FONT_MIN}
                  onClick={() => setPrefs((p) => ({ ...p, fontSize: Math.max(FONT_MIN, p.fontSize - 10) }))}
                />
                <MenuItem
                  icon="zoom-in"
                  text="Larger"
                  shouldDismissPopover={false}
                  disabled={prefs.fontSize >= FONT_MAX}
                  onClick={() => setPrefs((p) => ({ ...p, fontSize: Math.min(FONT_MAX, p.fontSize + 10) }))}
                />
                {otherFormats.length > 0 && (
                  <>
                    <MenuDivider title="Open as" />
                    {otherFormats.map((f) => (
                      <MenuItem key={f} icon="document" text={f} onClick={() => setFormatOverride(f)} />
                    ))}
                  </>
                )}
              </Menu>
            }
          >
            <Tooltip content="Display settings" placement="bottom">
              <Button variant="minimal" icon="settings" aria-label="Display settings" />
            </Tooltip>
          </Popover>
          {format && (
            <Tooltip content={`Download ${format}`} placement="bottom">
              <AnchorButton variant="minimal" icon="download" href={bookFileUrl(bookId, format)} download aria-label={`Download ${format}`} />
            </Tooltip>
          )}
          <Tooltip content="Close (Esc)" placement="bottom-end">
            <Button variant="minimal" icon="cross" aria-label="Close reader" onClick={onClose} />
          </Tooltip>
        </div>
      </div>

      <div className="reader-stage">
        <div ref={hostRef} className="reader-host" />
        {/* floats over the book's top margin: a banner above the book would resize (and re-lay out) it */}
        {note && (
          <div className="reader-note" role="status">
            <span>{note}</span>
            <Button variant="minimal" size="small" icon="cross" aria-label="Dismiss" onClick={() => setNote(null)} />
          </div>
        )}
        {phase.kind === "ready" && prefs.flow === "paginated" && (
          <>
            <Button className="reader-turn reader-turn-prev" variant="minimal" icon="chevron-left" aria-label="Previous page" onClick={() => void view?.goLeft()} />
            <Button className="reader-turn reader-turn-next" variant="minimal" icon="chevron-right" aria-label="Next page" onClick={() => void view?.goRight()} />
          </>
        )}
        {(phase.kind === "loading" || (locating && !shownRef.current)) && !book.error && (
          <div className="reader-overlay">
            <NonIdealState icon={<Spinner size={32} />} description={phase.kind === "loading" ? "Opening the book…" : "Finding the cited passage…"} />
          </div>
        )}
        {(book.error || phase.kind === "error" || (b && !format)) && (
          <div className="reader-overlay">
            <ReaderProblem book={b} format={format} error={book.error ?? (phase.kind === "error" ? phase.error : null)} onRetry={book.error ? book.reload : undefined} />
          </div>
        )}
      </div>

      {phase.kind === "ready" && <ProgressBar fraction={location?.fraction ?? 0} onSeek={(f) => void view?.goToFraction(f)} />}
    </div>
  );
}

/**
 * Page turns by keyboard. `inBook` (the key came from the book's iframe) also turns pages with Space, which on the
 * page itself must keep pressing the focused button, and closes the reader on Escape, which the Drawer can't see.
 */
function handleKey(e: KeyboardEvent, view: View, inBook?: { onEscape: () => void }) {
  if (e.altKey || e.ctrlKey || e.metaKey) return;
  let turn: (() => Promise<void>) | null = null;
  if (e.key === "ArrowLeft") turn = () => view.goLeft();
  else if (e.key === "ArrowRight") turn = () => view.goRight();
  else if (e.key === "PageUp" || (inBook && e.key === " " && e.shiftKey)) turn = () => view.prev();
  else if (e.key === "PageDown" || (inBook && e.key === " ")) turn = () => view.next();
  else if (inBook && e.key === "Escape") inBook.onEscape();
  if (turn) {
    e.preventDefault();
    void turn();
  }
}

function ProgressBar({ fraction, onSeek }: { fraction: number; onSeek: (fraction: number) => void }) {
  const [drag, setDrag] = useState<number | null>(null);
  const value = drag ?? Math.round(fraction * 1000) / 10;
  return (
    <div className="reader-footer">
      <Slider
        className="reader-slider"
        min={0}
        max={100}
        stepSize={0.1}
        labelRenderer={false}
        value={value}
        onChange={setDrag}
        onRelease={(v) => {
          setDrag(null);
          onSeek(v / 100);
        }}
      />
      <span className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL} reader-percent`}>{Math.round(value)}%</span>
    </div>
  );
}

function TocMenu({ toc, current, onSelect }: { toc: TocItem[]; current: string | null; onSelect: (href: string) => void }) {
  const items = useMemo(() => {
    const out: { item: TocItem; depth: number; key: string }[] = [];
    const visit = (list: TocItem[], depth: number, prefix: string) =>
      list.forEach((item, i) => {
        out.push({ item, depth, key: `${prefix}${i}` });
        if (item.subitems?.length) visit(item.subitems, depth + 1, `${prefix}${i}.`);
      });
    visit(toc, 0, "");
    return out;
  }, [toc]);
  return (
    <Menu className="reader-toc">
      {items.map(({ item, depth, key }) => (
        <MenuItem
          key={key}
          text={item.label?.trim() || "—"}
          title={item.label}
          active={item.href === current}
          disabled={!item.href}
          style={{ paddingLeft: 7 + depth * 14 }}
          onClick={() => item.href && onSelect(item.href)}
        />
      ))}
    </Menu>
  );
}

function ReaderProblem({ book, format, error, onRetry }: { book: Book | undefined; format: string | null; error: unknown; onRetry?: () => void }) {
  if (error && onRetry) return <ErrorState error={error} onRetry={onRetry} title="Couldn't load the book" />;
  const files = (book?.formats ?? []).map((f) => (
    <AnchorButton key={f} variant="outlined" icon={f === "PDF" ? "document-open" : "download"} text={f} href={bookFileUrl(book!.id, f)} target="_blank" rel="noopener" />
  ));
  return (
    <NonIdealState
      icon="book"
      title={format ? `Couldn't open the ${format} file` : "No format the reader can open"}
      description={
        format
          ? errorMessage(error)
          : `The reader opens ${["EPUB", "AZW3", "MOBI", "FB2", "CBZ"].join(", ")} and similar formats. Open or download the file instead.`
      }
      action={files.length ? <div className="reader-files">{files}</div> : undefined}
    />
  );
}
