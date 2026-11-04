import {
  Button,
  ButtonGroup,
  Callout,
  Classes,
  HTMLSelect,
  Intent,
  NonIdealState,
  Spinner,
  Tag,
} from "@blueprintjs/core";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api } from "../api/client";
import type { VectorLevel, VectorMethod, VectorPoint, VectorSpace } from "../api/types";
import { useApp } from "../AppContext";
import { ErrorState } from "../components/ErrorState";
import { toastError, toastSuccess } from "../components/toaster";
import { useAsync } from "../hooks/useAsync";
import { formatNumber } from "../lib/format";

const METHODS: { id: VectorMethod; label: string; hint: string }[] = [
  { id: "pca", label: "PCA", hint: "Linear projection onto the two directions of greatest variance. Fast; distances are roughly preserved." },
  { id: "umap", label: "UMAP", hint: "Preserves local neighbourhoods. Clusters are compact; global distances are not meaningful." },
  { id: "tsne", label: "t-SNE", hint: "Preserves local neighbourhoods. Slowest at large sizes; global distances are not meaningful." },
];

const LEVELS: { id: VectorLevel; label: string; hint: string }[] = [
  { id: "passages", label: "Passages", hint: "One point per passage: a sample of the library's passages." },
  { id: "books", label: "Books", hint: "One point per book: the average of its passages' embeddings." },
];

const SIZES = [250, 500, 1000, 2000];
const PAD = 24;
const POINT_RADIUS = 3.5;

/** Stable hue per book so the same book keeps its colour across reloads and methods. */
function bookColour(bookId: number): string {
  return `hsl(${(bookId * 137.508) % 360}, 65%, 52%)`;
}

interface Bounds {
  minX: number;
  maxX: number;
  minY: number;
  maxY: number;
}

function boundsOf(points: VectorPoint[]): Bounds {
  let minX = Infinity;
  let maxX = -Infinity;
  let minY = Infinity;
  let maxY = -Infinity;
  for (const p of points) {
    minX = Math.min(minX, p.x);
    maxX = Math.max(maxX, p.x);
    minY = Math.min(minY, p.y);
    maxY = Math.max(maxY, p.y);
  }
  return { minX, maxX, minY, maxY };
}

interface View {
  scale: number;
  offsetX: number;
  offsetY: number;
}

const IDENTITY: View = { scale: 1, offsetX: 0, offsetY: 0 };

export function VectorSpaceView({ active }: { active: boolean }) {
  const { library, libraries } = useApp();
  // One library at a time: the navbar's choice, or — with "All libraries" chosen there — a pick of this view's own.
  const [ownLibraryId, setOwnLibraryId] = useState<number | null>(null);
  const libraryId = library?.id ?? libraries.find((l) => l.id === ownLibraryId)?.id ?? libraries[0]?.id;
  const [level, setLevel] = useState<VectorLevel>("passages");
  const [method, setMethod] = useState<VectorMethod>("umap");
  const [size, setSize] = useState(1000);
  // undefined: the library's current sample (kept by the server, so a reload shows the last reshuffle); a number:
  // a new sample, after Reshuffle
  const [seed, setSeed] = useState<number | undefined>();
  const [highlightBook, setHighlightBook] = useState<number | null>(null);
  const [pinned, setPinned] = useState<VectorPoint | null>(null);
  const [computing, setComputing] = useState(false);

  const space = useAsync<VectorSpace | undefined>(
    (signal) =>
      libraryId == null ? Promise.resolve(undefined) : api.vectors({ libraryId, level, method, size, seed }, signal),
    [libraryId, level, method, size, seed],
  );

  // a new library's passages are different points: drop the selection and highlight of the previous one, and show
  // that library's own current sample rather than carrying a reshuffle over
  useEffect(() => {
    setPinned(null);
    setHighlightBook(null);
    setSeed(undefined);
  }, [libraryId]);
  // passages and books are different points too
  useEffect(() => {
    setPinned(null);
    setHighlightBook(null);
  }, [level]);

  /** book vectors for books indexed before they existed: averaged from their passages, nothing re-indexed */
  const computeBookVectors = async () => {
    if (libraryId == null) return;
    setComputing(true);
    try {
      const r = await api.computeBookVectors(libraryId);
      toastSuccess(`Computed ${formatNumber(r.computed)} book vectors`);
      space.reload();
    } catch (err) {
      toastError("Computing book vectors failed", err);
    } finally {
      setComputing(false);
    }
  };

  const reshuffle = () => setSeed(1 + Math.floor(Math.random() * 1_000_000));

  // the last layout stays up while the next loads, but not across libraries or levels
  const data = space.data && space.data.libraryId === libraryId && space.data.level === level ? space.data : undefined;
  const byBook = level === "books";
  const books = useMemo(() => {
    const byId = new Map<number, { bookId: number; title: string; count: number }>();
    for (const p of data?.points ?? []) {
      const entry = byId.get(p.bookId) ?? { bookId: p.bookId, title: p.title, count: 0 };
      entry.count += 1;
      byId.set(p.bookId, entry);
    }
    // a book is one point at the books level: alphabetical then, else the books with most passages first
    return [...byId.values()].sort((a, b) => b.count - a.count || a.title.localeCompare(b.title));
  }, [data]);

  const methodMeta = METHODS.find((m) => m.id === method)!;

  return (
    <div className="vectors-view">
      <div className="view-toolbar">
        {library == null && libraries.length > 1 && libraryId != null && (
          <HTMLSelect
            aria-label="Library to plot"
            value={libraryId}
            onChange={(e) => setOwnLibraryId(Number(e.currentTarget.value))}
            options={libraries.map((l) => ({ value: l.id, label: l.name }))}
          />
        )}
        <ButtonGroup aria-label="Plot passages or books">
          {LEVELS.map((l) => (
            <Button key={l.id} text={l.label} active={level === l.id} onClick={() => setLevel(l.id)} title={l.hint} />
          ))}
        </ButtonGroup>
        <ButtonGroup>
          {METHODS.map((m) => (
            <Button
              key={m.id}
              text={m.label}
              active={method === m.id}
              onClick={() => setMethod(m.id)}
              title={m.hint}
            />
          ))}
        </ButtonGroup>
        <ButtonGroup>
          {SIZES.map((s) => (
            <Button key={s} text={formatNumber(s)} active={size === s} onClick={() => setSize(s)} />
          ))}
        </ButtonGroup>
        <Button icon="random" text="Reshuffle sample" onClick={reshuffle} />
        <span className="spacer" />
        {data && (
          <span className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL}`}>
            {formatNumber(data.size)} of {formatNumber(data.total)} {byBook ? "books" : "passages"} · {data.dimensions}-D →
            2-D in {formatNumber(data.elapsedMs)} ms{data.cached ? " (cached)" : ""}
          </span>
        )}
      </div>

      {byBook && data && data.missingBookVectors > 0 && data.points.length > 0 && (
        <Callout compact intent={Intent.PRIMARY} icon="info-sign" className="vectors-missing">
          {formatNumber(data.missingBookVectors)} indexed {data.missingBookVectors === 1 ? "book has" : "books have"} no
          book vector yet, so {data.missingBookVectors === 1 ? "it isn't" : "they aren't"} plotted.{" "}
          <Button size="small" intent={Intent.PRIMARY} text="Compute book vectors" loading={computing} onClick={computeBookVectors} />
        </Callout>
      )}

      {space.error ? (
        <ErrorState error={space.error} onRetry={space.reload} />
      ) : !data ? (
        <NonIdealState icon={<Spinner />} description="Projecting embeddings…" />
      ) : data.points.length === 0 ? (
        byBook && data.missingBookVectors > 0 ? (
          <NonIdealState
            icon="scatter-plot"
            title="No book vectors yet"
            description={`${formatNumber(data.missingBookVectors)} indexed books can get one: the average of their passages' embeddings, computed from what's already indexed (a few seconds, no re-indexing).`}
            action={
              <Button intent={Intent.PRIMARY} icon="calculator" text="Compute book vectors" loading={computing} onClick={computeBookVectors} />
            }
          />
        ) : (
          <NonIdealState
            icon="scatter-plot"
            title="No embeddings yet"
            description="Index some books of this library to see them here."
          />
        )
      ) : (
        <div className="vectors-body">
          <div className="vectors-canvas-wrap">
            <Callout compact className="vectors-hint">
              {byBook ? "Each point is a book: the average of its passages. " : ""}
              {methodMeta.hint} Scroll to zoom, drag to pan, click a point for details.
            </Callout>
            <ScatterCanvas
              active={active}
              points={data.points}
              highlightBook={highlightBook}
              pinnedId={pinned?.id ?? null}
              onPick={setPinned}
            />
          </div>
          <aside className="vectors-side">
            {pinned && (
              <div className="vectors-detail" aria-live="polite">
                <div className="vectors-detail-head">
                  <Tag minimal style={{ background: bookColour(pinned.bookId) }}>
                    {pinned.title}
                  </Tag>
                  <Button variant="minimal" icon="cross" aria-label="Close details" onClick={() => setPinned(null)} />
                </div>
                <div className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL}`}>
                  {pinned.section ?? "No section"}
                  {pinned.page != null ? ` · p. ${pinned.page}` : ""}
                </div>
                <p className="vectors-snippet">{pinned.snippet}</p>
              </div>
            )}
            <div className="vectors-legend">
              <div className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL}`}>
                {byBook ? `${formatNumber(books.length)} books` : "Books"} — click to highlight
                {highlightBook != null ? " (click again to clear)" : ""}
              </div>
              {books.map((b) => (
                <button
                  key={b.bookId}
                  type="button"
                  className={`vectors-legend-item${highlightBook === b.bookId ? " is-active" : ""}`}
                  aria-pressed={highlightBook === b.bookId}
                  onClick={() => setHighlightBook(highlightBook === b.bookId ? null : b.bookId)}
                >
                  <span className="vectors-swatch" style={{ background: bookColour(b.bookId) }} />
                  <span className="vectors-legend-title">{b.title}</span>
                  {!byBook && <span className={Classes.TEXT_MUTED}>{formatNumber(b.count)}</span>}
                </button>
              ))}
            </div>
          </aside>
        </div>
      )}
      {space.loading && data && (
        <Tag className="vectors-busy" icon={<Spinner size={12} />} intent={Intent.PRIMARY} minimal>
          Re-projecting…
        </Tag>
      )}
    </div>
  );
}

interface CanvasProps {
  active: boolean;
  points: VectorPoint[];
  highlightBook: number | null;
  pinnedId: string | null;
  onPick: (p: VectorPoint | null) => void;
}

/** Canvas scatter plot with wheel zoom, drag to pan, nearest-point hover and click. */
function ScatterCanvas({ active, points, highlightBook, pinnedId, onPick }: CanvasProps) {
  const wrapRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [size, setSize] = useState({ w: 0, h: 0 });
  const [view, setView] = useState<View>(IDENTITY);
  const [hover, setHover] = useState<VectorPoint | null>(null);
  const drag = useRef<{ x: number; y: number; moved: boolean } | null>(null);

  const bounds = useMemo(() => boundsOf(points), [points]);

  // Re-fit when the data changes.
  useEffect(() => setView(IDENTITY), [points]);

  useEffect(() => {
    const el = wrapRef.current;
    if (!el) return;
    const ro = new ResizeObserver((entries) => {
      const entry = entries[0];
      if (!entry) return;
      const { width, height } = entry.contentRect;
      setSize({ w: Math.floor(width), h: Math.floor(height) });
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  // Maps data coordinates to canvas pixels, fitting the bounds into the viewport with padding.
  const transform = useCallback(
    (p: { x: number; y: number }) => {
      const spanX = bounds.maxX - bounds.minX || 1;
      const spanY = bounds.maxY - bounds.minY || 1;
      const fit = Math.min((size.w - 2 * PAD) / spanX, (size.h - 2 * PAD) / spanY);
      const cx = PAD + ((p.x - bounds.minX) * fit) + (size.w - 2 * PAD - spanX * fit) / 2;
      // Flip y so larger values point up.
      const cy = PAD + ((bounds.maxY - p.y) * fit) + (size.h - 2 * PAD - spanY * fit) / 2;
      return { x: cx * view.scale + view.offsetX, y: cy * view.scale + view.offsetY };
    },
    [bounds, size, view],
  );

  useEffect(() => {
    if (!active) return;
    const canvas = canvasRef.current;
    if (!canvas || size.w === 0) return;
    const dpr = window.devicePixelRatio || 1;
    canvas.width = size.w * dpr;
    canvas.height = size.h * dpr;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, size.w, size.h);

    const dimmed = highlightBook != null;
    for (const p of points) {
      const { x, y } = transform(p);
      const isHi = p.bookId === highlightBook;
      ctx.globalAlpha = dimmed ? (isHi ? 1 : 0.12) : 0.75;
      ctx.fillStyle = bookColour(p.bookId);
      ctx.beginPath();
      ctx.arc(x, y, POINT_RADIUS, 0, Math.PI * 2);
      ctx.fill();
    }
    ctx.globalAlpha = 1;

    for (const p of [hover, points.find((q) => q.id === pinnedId)]) {
      if (!p) continue;
      const { x, y } = transform(p);
      ctx.strokeStyle = "currentColor";
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.arc(x, y, POINT_RADIUS + 3, 0, Math.PI * 2);
      ctx.stroke();
    }
  }, [active, points, transform, size, highlightBook, hover, pinnedId]);

  const nearest = (px: number, py: number): VectorPoint | null => {
    let best: VectorPoint | null = null;
    let bestD = 64; // 8px radius
    for (const p of points) {
      const { x, y } = transform(p);
      const d = (x - px) ** 2 + (y - py) ** 2;
      if (d < bestD) {
        bestD = d;
        best = p;
      }
    }
    return best;
  };

  const localXY = (e: React.MouseEvent) => {
    const rect = canvasRef.current!.getBoundingClientRect();
    return { x: e.clientX - rect.left, y: e.clientY - rect.top };
  };

  const onWheel = (e: React.WheelEvent) => {
    const { x, y } = localXY(e);
    const factor = Math.exp(-e.deltaY * 0.0015);
    setView((v) => {
      const scale = Math.min(50, Math.max(0.5, v.scale * factor));
      const ratio = scale / v.scale;
      return { scale, offsetX: x - (x - v.offsetX) * ratio, offsetY: y - (y - v.offsetY) * ratio };
    });
  };

  return (
    <div ref={wrapRef} className="vectors-canvas">
      <canvas
        ref={canvasRef}
        style={{ width: size.w, height: size.h, cursor: drag.current ? "grabbing" : hover ? "pointer" : "grab" }}
        role="img"
        aria-label={`Scatter plot of ${points.length} passages`}
        onWheel={onWheel}
        onMouseDown={(e) => {
          drag.current = { x: e.clientX, y: e.clientY, moved: false };
        }}
        onMouseMove={(e) => {
          const d = drag.current;
          if (d) {
            const dx = e.clientX - d.x;
            const dy = e.clientY - d.y;
            if (Math.abs(dx) + Math.abs(dy) > 3) d.moved = true;
            drag.current = { x: e.clientX, y: e.clientY, moved: d.moved };
            if (d.moved) {
              setView((v) => ({ ...v, offsetX: v.offsetX + dx, offsetY: v.offsetY + dy }));
              setHover(null);
            }
            return;
          }
          const { x, y } = localXY(e);
          setHover(nearest(x, y));
        }}
        onMouseUp={() => {
          const wasDrag = drag.current?.moved;
          drag.current = null;
          if (!wasDrag) {
            // Plain click: pin the nearest point, or clear the pin when clicking empty space.
            onPick(hover);
          }
        }}
        onMouseLeave={() => {
          drag.current = null;
          setHover(null);
        }}
      />
      {hover && !drag.current?.moved && (() => {
        const { x, y } = transform(hover);
        return (
          <div className="vectors-tooltip" style={{ left: Math.min(x + 12, size.w - 260), top: Math.min(y + 12, size.h - 80) }}>
            <strong>{hover.title}</strong>
            <div className={Classes.TEXT_MUTED}>
              {hover.section ?? "No section"}
              {hover.page != null ? ` · p. ${hover.page}` : ""}
            </div>
          </div>
        );
      })()}
      {view.scale !== 1 || view.offsetX !== 0 || view.offsetY !== 0 ? (
        <Button
          className="vectors-reset"
          small
          icon="zoom-to-fit"
          text="Reset view"
          onClick={() => setView(IDENTITY)}
        />
      ) : null}
    </div>
  );
}
