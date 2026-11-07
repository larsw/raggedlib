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
  Tooltip,
} from "@blueprintjs/core";
import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api } from "../api/client";
import type { ClusterSpace, VectorLevel, VectorMethod, VectorPoint, VectorSpace, TopicSpace } from "../api/types";
import { useApp } from "../AppContext";
import { ErrorState } from "../components/ErrorState";
import { toastError, toastSuccess } from "../components/toaster";
import { useAsync } from "../hooks/useAsync";
import { useSidePanel } from "../hooks/useSidePanel";
import { roleAtLeast } from "../lib/auth";
import { formatNumber } from "../lib/format";
import { STORAGE_KEYS } from "../lib/storage";

// deck.gl (WebGL) loads with the first plot, not with the app
const DeckScatter = lazy(() => import("../components/vectors/DeckScatter"));

const METHODS: { id: VectorMethod; label: string; hint: string }[] = [
  { id: "pca", label: "PCA", hint: "Linear projection onto the directions of greatest variance. Fast; distances are roughly preserved." },
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
const NOISE_TOPIC = -1;
const NOISE_GRAY: [number, number, number] = [130, 130, 130];

const CLUSTER_SPACES: { id: ClusterSpace; label: string; hint: string }[] = [
  { id: "raw", label: "Raw", hint: "Cluster the full embeddings directly (cosine distance)." },
  { id: "umap", label: "UMAP-reduced", hint: "Reduce to ~8 dimensions with UMAP first, like BERTopic does, then cluster that (slower)." },
];

/** Stable hue per group (book or topic id) so the same one keeps its colour across reloads and methods. */
function groupHue(id: number): number {
  return (id * 137.508) % 360;
}

function hslString(hue: number): string {
  return `hsl(${hue}, 65%, 52%)`;
}

/** [hue] as 0-255 RGB, for WebGL. */
function hslRgb(hue: number): [number, number, number] {
  const s = 0.65;
  const l = 0.52;
  const k = (n: number) => (n + hue / 30) % 12;
  const a = s * Math.min(l, 1 - l);
  const f = (n: number) => l - a * Math.max(-1, Math.min(k(n) - 3, Math.min(9 - k(n), 1)));
  return [Math.round(f(0) * 255), Math.round(f(8) * 255), Math.round(f(4) * 255)];
}

function bookColour(bookId: number): string {
  return hslString(groupHue(bookId));
}

/** [bookColour] as 0-255 RGB, for WebGL. */
export function bookColourRgb(bookId: number): [number, number, number] {
  return hslRgb(groupHue(bookId));
}

/** Like [bookColour], but noise (unclustered, id -1) is always a neutral grey instead of a hue. */
function topicColour(topicId: number): string {
  return topicId === NOISE_TOPIC ? `rgb(${NOISE_GRAY.join(",")})` : hslString(groupHue(topicId));
}

function topicColourRgb(topicId: number): [number, number, number] {
  return topicId === NOISE_TOPIC ? NOISE_GRAY : hslRgb(groupHue(topicId));
}

/** WebGL2 is what deck.gl draws with; without it (or after it failed) the canvas renderer draws 2-D plots. */
function hasWebGL2(): boolean {
  try {
    return !!document.createElement("canvas").getContext("webgl2");
  } catch {
    return false;
  }
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
  const { library, libraries, roleIn } = useApp();
  // One library at a time: the navbar's choice, or — with "All libraries" chosen there — a pick of this view's own.
  const [ownLibraryId, setOwnLibraryId] = useState<number | null>(null);
  const libraryId = library?.id ?? libraries.find((l) => l.id === ownLibraryId)?.id ?? libraries[0]?.id;
  // computing book vectors writes to the library's index: curators only
  const canCompute = roleAtLeast(roleIn(libraryId), "CURATOR");
  const [level, setLevel] = useState<VectorLevel>("passages");
  const [method, setMethod] = useState<VectorMethod>("umap");
  const [size, setSize] = useState(1000);
  const [gpu, setGpu] = useState(hasWebGL2);
  const [dimsWanted, setDims] = useState<2 | 3>(2);
  const dims = gpu ? dimsWanted : 2; // 3-D needs the GPU renderer
  // undefined: the library's current sample (kept by the server, so a reload shows the last reshuffle); a number:
  // a new sample, after Reshuffle
  const [seed, setSeed] = useState<number | undefined>();
  const [highlightBook, setHighlightBook] = useState<number | null>(null);
  const [pinned, setPinned] = useState<VectorPoint | null>(null);
  const [computing, setComputing] = useState(false);
  const bodyRef = useRef<HTMLDivElement>(null);
  const panel = useSidePanel(bodyRef, { storageKey: STORAGE_KEYS.vectorsPanel });
  const [colorBy, setColorBy] = useState<"book" | "topic">("book");
  const [clusterSpace, setClusterSpace] = useState<ClusterSpace>("raw");
  const [highlightTopic, setHighlightTopic] = useState<number | null>(null);

  const space = useAsync<VectorSpace | undefined>(
    (signal) =>
      libraryId == null ? Promise.resolve(undefined) : api.vectors({ libraryId, level, method, size, dims, seed }, signal),
    [libraryId, level, method, size, dims, seed],
  );

  // independent of the projection (method/dims): switching PCA/UMAP/t-SNE or 2D/3D never refetches topics
  const topicsAsync = useAsync<TopicSpace | undefined>(
    (signal) =>
      libraryId == null || colorBy !== "topic"
        ? Promise.resolve(undefined)
        : api.vectorTopics({ libraryId, level, size, seed, space: clusterSpace }, signal),
    [libraryId, level, size, seed, clusterSpace, colorBy],
  );

  // a new library's passages are different points: drop the selection and highlight of the previous one, and show
  // that library's own current sample rather than carrying a reshuffle over
  useEffect(() => {
    setPinned(null);
    setHighlightBook(null);
    setHighlightTopic(null);
    setSeed(undefined);
  }, [libraryId]);
  // passages and books are different points too
  useEffect(() => {
    setPinned(null);
    setHighlightBook(null);
    setHighlightTopic(null);
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
  // (a 2-D layout shown in 3-D, or the other way round, would be wrong, so not across dimensions either)
  const data =
    space.data && space.data.libraryId === libraryId && space.data.level === level && space.data.dims === dims
      ? space.data
      : undefined;
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

  // only trust topics computed for the sample currently shown (same library/level/size/seed)
  const topicsData =
    colorBy === "topic" && topicsAsync.data && data && topicsAsync.data.libraryId === libraryId &&
      topicsAsync.data.level === level && topicsAsync.data.size === data.size && topicsAsync.data.seed === data.seed
      ? topicsAsync.data
      : undefined;
  const pointTopic = useMemo(() => new Map(Object.entries(topicsData?.pointTopics ?? {})), [topicsData]);
  const topicOf = useCallback((p: VectorPoint) => pointTopic.get(p.id) ?? NOISE_TOPIC, [pointTopic]);
  const noiseCount = useMemo(
    () => (topicsData ? data?.points.filter((p) => topicOf(p) === NOISE_TOPIC).length ?? 0 : 0),
    [topicsData, data, topicOf],
  );

  const methodMeta = METHODS.find((m) => m.id === method)!;
  const groupId = colorBy === "book" ? (p: VectorPoint) => p.bookId : topicOf;
  const highlightGroup = colorBy === "book" ? highlightBook : highlightTopic;
  const colour = colorBy === "book" ? bookColourRgb : topicColourRgb;

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
        <ButtonGroup aria-label="2-D or 3-D">
          <Button text="2D" active={dims === 2} onClick={() => setDims(2)} />
          <Tooltip
            content={gpu ? "Lay out in three dimensions; drag to rotate" : "3-D needs WebGL2, which this browser doesn't offer"}
            placement="bottom"
          >
            <Button text="3D" active={dims === 3} disabled={!gpu} onClick={() => setDims(3)} />
          </Tooltip>
        </ButtonGroup>
        <Button icon="random" text="Reshuffle sample" onClick={reshuffle} />
        <ButtonGroup aria-label="Colour by book or topic">
          <Button text="Book" active={colorBy === "book"} onClick={() => setColorBy("book")} title="Colour points by their book" />
          <Button
            text="Topic"
            active={colorBy === "topic"}
            onClick={() => setColorBy("topic")}
            title="Cluster the embeddings (HDBSCAN) and colour points by discovered topic"
          />
        </ButtonGroup>
        {colorBy === "topic" && (
          <ButtonGroup aria-label="Cluster on raw embeddings or a UMAP reduction">
            {CLUSTER_SPACES.map((s) => (
              <Button key={s.id} text={s.label} active={clusterSpace === s.id} onClick={() => setClusterSpace(s.id)} title={s.hint} />
            ))}
          </ButtonGroup>
        )}
        <span className="spacer" />
        {data && (
          <span className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL}`}>
            {formatNumber(data.size)} of {formatNumber(data.total)} {byBook ? "books" : "passages"} · {data.dimensions}-D →{" "}
            {data.dims}-D in {formatNumber(data.elapsedMs)} ms{data.cached ? " (cached)" : ""} ·{" "}
            {gpu ? "WebGL" : "canvas"}
          </span>
        )}
      </div>
      {colorBy === "topic" && topicsData && (
        <div className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL} vectors-topics-status`}>
          {formatNumber(topicsData.topics.length)} topics, {formatNumber(noiseCount)} unclustered in{" "}
          {formatNumber(topicsData.elapsedMs)} ms{topicsData.cached ? " (cached)" : ""}
        </div>
      )}

      {byBook && data && data.missingBookVectors > 0 && data.points.length > 0 && (
        <Callout compact intent={Intent.PRIMARY} icon="info-sign" className="vectors-missing">
          {formatNumber(data.missingBookVectors)} indexed {data.missingBookVectors === 1 ? "book has" : "books have"} no
          book vector yet, so {data.missingBookVectors === 1 ? "it isn't" : "they aren't"} plotted.{" "}
          {canCompute ? (
            <Button size="small" intent={Intent.PRIMARY} text="Compute book vectors" loading={computing} onClick={computeBookVectors} />
          ) : (
            "A curator of the library can compute them."
          )}
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
            description={
              `${formatNumber(data.missingBookVectors)} indexed books can get one: the average of their passages' embeddings, ` +
              (canCompute
                ? "computed from what's already indexed (a few seconds, no re-indexing)."
                : "which a curator of the library can compute from what's already indexed.")
            }
            action={
              canCompute ? (
                <Button intent={Intent.PRIMARY} icon="calculator" text="Compute book vectors" loading={computing} onClick={computeBookVectors} />
              ) : undefined
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
        <div className={`vectors-body${panel.dragging ? " is-resizing" : ""}`} ref={bodyRef}>
          <div className="vectors-canvas-wrap">
            <Callout compact className="vectors-hint">
              {byBook ? "Each point is a book: the average of its passages. " : ""}
              {methodMeta.hint}{" "}
              {dims === 3
                ? "Drag to rotate, right-drag or Shift-drag to pan, scroll to zoom, click a point for details."
                : "Scroll to zoom, drag to pan, click a point for details."}
            </Callout>
            {gpu ? (
              <Suspense fallback={<NonIdealState icon={<Spinner />} description="Loading the plot…" />}>
                <DeckScatter
                  points={data.points}
                  dims={dims}
                  groupId={groupId}
                  highlightGroup={highlightGroup}
                  pinnedId={pinned?.id ?? null}
                  onPick={setPinned}
                  colour={colour}
                  onError={(err) => {
                    console.warn("WebGL plot failed, falling back to the canvas renderer", err);
                    setGpu(false);
                  }}
                />
              </Suspense>
            ) : (
              <ScatterCanvas
                active={active}
                points={data.points}
                groupId={groupId}
                highlightGroup={highlightGroup}
                colour={colour}
                pinnedId={pinned?.id ?? null}
                onPick={setPinned}
              />
            )}
          </div>
          <div className="side-dock" style={{ width: panel.width }}>
            <div className="side-resize-handle" {...panel.handleProps} aria-label="Resize the Vectors sidebar" />
            <aside className="vectors-side">
            {pinned && (
              <div className="vectors-detail" aria-live="polite">
                <div className="vectors-detail-head">
                  <Tag minimal style={{ background: colorBy === "book" ? bookColour(pinned.bookId) : topicColour(topicOf(pinned)) }}>
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
            {colorBy === "topic" ? (
              <div className="vectors-legend">
                <div className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL}`}>
                  Topics — click to highlight
                  {highlightTopic != null ? " (click again to clear)" : ""}
                </div>
                {!topicsData ? (
                  <div className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL}`}>
                    {topicsAsync.error ? "Clustering failed." : "Clustering embeddings…"}
                  </div>
                ) : (
                  <>
                    {topicsData.topics.map((t) => (
                      <button
                        key={t.id}
                        type="button"
                        className={`vectors-legend-item${highlightTopic === t.id ? " is-active" : ""}`}
                        aria-pressed={highlightTopic === t.id}
                        onClick={() => setHighlightTopic(highlightTopic === t.id ? null : t.id)}
                        title={t.keywords.join(", ")}
                      >
                        <span className="vectors-swatch" style={{ background: topicColour(t.id) }} />
                        <span className="vectors-legend-title">{t.label}</span>
                        <span className={Classes.TEXT_MUTED}>{formatNumber(t.size)}</span>
                      </button>
                    ))}
                    {noiseCount > 0 && (
                      <button
                        type="button"
                        className={`vectors-legend-item${highlightTopic === NOISE_TOPIC ? " is-active" : ""}`}
                        aria-pressed={highlightTopic === NOISE_TOPIC}
                        onClick={() => setHighlightTopic(highlightTopic === NOISE_TOPIC ? null : NOISE_TOPIC)}
                        title="Points that didn't settle into any stable topic"
                      >
                        <span className="vectors-swatch" style={{ background: topicColour(NOISE_TOPIC) }} />
                        <span className="vectors-legend-title">Unclustered</span>
                        <span className={Classes.TEXT_MUTED}>{formatNumber(noiseCount)}</span>
                      </button>
                    )}
                  </>
                )}
              </div>
            ) : (
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
            )}
            </aside>
          </div>
        </div>
      )}
      {space.loading && data && (
        <Tag className="vectors-busy" icon={<Spinner size={12} />} intent={Intent.PRIMARY} minimal>
          Re-projecting…
        </Tag>
      )}
      {colorBy === "topic" && topicsAsync.loading && (
        <Tag className="vectors-busy" icon={<Spinner size={12} />} intent={Intent.PRIMARY} minimal>
          Clustering…
        </Tag>
      )}
    </div>
  );
}

interface CanvasProps {
  active: boolean;
  points: VectorPoint[];
  groupId: (p: VectorPoint) => number;
  highlightGroup: number | null;
  colour: (groupId: number) => [number, number, number];
  pinnedId: string | null;
  onPick: (p: VectorPoint | null) => void;
}

/** Canvas scatter plot with wheel zoom, drag to pan, nearest-point hover and click. */
function ScatterCanvas({ active, points, groupId, highlightGroup, colour, pinnedId, onPick }: CanvasProps) {
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

    const dimmed = highlightGroup != null;
    for (const p of points) {
      const { x, y } = transform(p);
      const isHi = groupId(p) === highlightGroup;
      ctx.globalAlpha = dimmed ? (isHi ? 1 : 0.12) : 0.75;
      const [r, g, b] = colour(groupId(p));
      ctx.fillStyle = `rgb(${r},${g},${b})`;
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
  }, [active, points, transform, size, groupId, highlightGroup, colour, hover, pinnedId]);

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
