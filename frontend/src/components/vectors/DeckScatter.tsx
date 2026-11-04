import { OrbitView, OrthographicView, type PickingInfo } from "@deck.gl/core";
import { ScatterplotLayer } from "@deck.gl/layers";
import DeckGL from "@deck.gl/react";
import { useEffect, useMemo, useRef, useState } from "react";
import type { VectorPoint } from "../../api/types";

/** A point placed in a box of half-width 1 around the origin, so one camera setting fits every layout. */
interface Placed {
  position: [number, number, number];
  point: VectorPoint;
}

type Rgb = [number, number, number];

export interface DeckScatterProps {
  points: VectorPoint[];
  dims: 2 | 3;
  highlightBook: number | null;
  pinnedId: string | null;
  onPick: (p: VectorPoint | null) => void;
  colour: (bookId: number) => Rgb;
  /** WebGL failed (no context, lost context): the caller falls back to the canvas renderer */
  onError: (error: Error) => void;
}

/** Centres the layout on the origin and scales its largest extent to 2 (so it spans -1..1). */
function place(points: VectorPoint[], dims: 2 | 3): Placed[] {
  const axes = dims === 3 ? (["x", "y", "z"] as const) : (["x", "y"] as const);
  const lo = axes.map((a) => Math.min(...points.map((p) => p[a] ?? 0)));
  const hi = axes.map((a) => Math.max(...points.map((p) => p[a] ?? 0)));
  const centre = axes.map((_, i) => (lo[i]! + hi[i]!) / 2);
  const half = Math.max(...axes.map((_, i) => (hi[i]! - lo[i]!) / 2)) || 1;
  return points.map((point) => ({
    point,
    position: [
      (point.x - centre[0]!) / half,
      (point.y - centre[1]!) / half,
      dims === 3 ? ((point.z ?? 0) - centre[2]!) / half : 0,
    ],
  }));
}

/**
 * GPU scatter plot (deck.gl, WebGL2): a flat zoom/pan view for 2-D layouts, an orbiting camera for 3-D ones.
 * Hover shows the passage or book, click pins it (click empty space to unpin).
 */
export default function DeckScatter({ points, dims, highlightBook, pinnedId, onPick, colour, onError }: DeckScatterProps) {
  const wrapRef = useRef<HTMLDivElement>(null);
  const [size, setSize] = useState<{ w: number; h: number } | null>(null);

  useEffect(() => {
    const el = wrapRef.current;
    if (!el) return;
    const ro = new ResizeObserver(([entry]) => {
      if (entry) setSize({ w: entry.contentRect.width, h: entry.contentRect.height });
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  const placed = useMemo(() => place(points, dims), [points, dims]);

  // The camera fits the data when the layout (or its dimensions) changes, not on every resize: a new object here
  // makes deck.gl reset the view, discarding the user's zoom and rotation.
  const fitTo = size ? Math.min(size.w, size.h) : 0;
  const fitRef = useRef(fitTo);
  if (fitTo > 0 && fitRef.current === 0) fitRef.current = fitTo;
  const initialViewState = useMemo(() => {
    const zoom = Math.log2(Math.max(fitRef.current, 100) / 2 * (dims === 3 ? 0.6 : 0.9));
    return dims === 3
      ? { target: [0, 0, 0] as [number, number, number], rotationX: 20, rotationOrbit: 30, zoom, minZoom: zoom - 3, maxZoom: zoom + 8 }
      : { target: [0, 0, 0] as [number, number, number], zoom, minZoom: zoom - 3, maxZoom: zoom + 10 };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [placed, dims, fitRef.current > 0]);

  const view = useMemo(
    () => (dims === 3 ? new OrbitView({ id: "orbit", orbitAxis: "Y" }) : new OrthographicView({ id: "flat" })),
    [dims],
  );

  const layer = new ScatterplotLayer<Placed>({
    id: "points",
    data: placed,
    pickable: true,
    billboard: true,
    antialiasing: true,
    radiusUnits: "pixels",
    lineWidthUnits: "pixels",
    stroked: true,
    getPosition: (d) => d.position,
    getRadius: (d) =>
      d.point.id === pinnedId ? 6.5 : highlightBook != null && d.point.bookId === highlightBook ? 5 : 3.5,
    getFillColor: (d) => {
      const dimmed = highlightBook != null && d.point.bookId !== highlightBook;
      return [...colour(d.point.bookId), dimmed ? 35 : 225];
    },
    getLineColor: [255, 255, 255, 230],
    getLineWidth: (d) => (d.point.id === pinnedId ? 2 : 0),
    updateTriggers: {
      getRadius: [pinnedId, highlightBook],
      getFillColor: [highlightBook, colour],
      getLineWidth: [pinnedId],
    },
  });

  return (
    <div ref={wrapRef} className="vectors-deck">
      {size && size.w > 0 && (
        <DeckGL
          views={view}
          initialViewState={initialViewState}
          controller={{ inertia: dims === 3 }}
          layers={[layer]}
          onError={(e) => onError(e instanceof Error ? e : new Error(String(e)))}
          onClick={(info: PickingInfo<Placed>) => onPick(info.object?.point ?? null)}
          getCursor={({ isHovering, isDragging }) => (isDragging ? "grabbing" : isHovering ? "pointer" : "grab")}
          getTooltip={({ object }: PickingInfo<Placed>) =>
            object == null
              ? null
              : {
              text: [object.point.title, object.point.section, object.point.page != null ? `p. ${object.point.page}` : null]
                .filter(Boolean)
                .join("\n"),
                  className: "vectors-tooltip",
                }
          }
        />
      )}
    </div>
  );
}
