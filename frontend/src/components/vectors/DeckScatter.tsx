import { OrbitView, OrthographicView, type PickingInfo } from "@deck.gl/core";
import { ScatterplotLayer } from "@deck.gl/layers";
import DeckGL from "@deck.gl/react";
import { useEffect, useMemo, useRef, useState } from "react";
import type { VectorPoint } from "../../api/types";
import {
  coast,
  type DragSample,
  dragRotate,
  INITIAL_ROTATION,
  MIN_COAST_SPEED,
  modelMatrix,
  RELEASE_WINDOW_MS,
  releaseVelocity,
  type Rotation,
} from "../../lib/trackball";

/** A point placed in a box of half-width 1 around the origin, so one camera setting fits every layout. */
interface Placed {
  position: [number, number, number];
  point: VectorPoint;
}

type Rgb = [number, number, number];

export interface DeckScatterProps {
  points: VectorPoint[];
  dims: 2 | 3;
  /** Which colour/highlight group a point belongs to — the book id, or (color-by-topic) the topic id. */
  groupId: (p: VectorPoint) => number;
  highlightGroup: number | null;
  pinnedId: string | null;
  onPick: (p: VectorPoint | null) => void;
  colour: (groupId: number) => Rgb;
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
 * GPU scatter plot (deck.gl, WebGL2): a flat zoom/pan view for 2-D layouts; for 3-D ones a trackball — dragging
 * turns the plot about the screen's axes, freely in every direction, and a flick keeps it spinning down to a stop
 * (see trackball.ts) — with deck.gl's camera
 * left to zoom (wheel, pinch) and pan (shift- or right-drag). Hover shows the passage or book, click pins it
 * (click empty space to unpin).
 */
export default function DeckScatter({ points, dims, groupId, highlightGroup, pinnedId, onPick, colour, onError }: DeckScatterProps) {
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

  // 3-D: the plot's orientation, turned by dragging; a new layout starts from the initial one again
  const [rotation, setRotation] = useState<Rotation>(INITIAL_ROTATION);
  // a release while moving keeps it turning, slowing down (inertia); the running frame request, to stop it
  const coasting = useRef<number | null>(null);
  const stopCoasting = () => {
    if (coasting.current != null) cancelAnimationFrame(coasting.current);
    coasting.current = null;
  };
  useEffect(() => {
    stopCoasting();
    setRotation(INITIAL_ROTATION);
  }, [placed]);
  useEffect(() => stopCoasting, []);
  const turned = useMemo(() => modelMatrix(rotation), [rotation]);
  const radiansPerPixel = size ? Math.PI / Math.max(Math.min(size.w, size.h), 100) : 0.01; // across the plot: half a turn

  /**
   * A plain left drag (or one-finger touch) turns the plot. Moves are followed on the window, so the drag carries
   * on outside the plot and deck.gl still sees the presses it needs for clicks; shift/ctrl/meta- and right-drags
   * are deck.gl's (panning), as are multi-touch gestures (a second finger down ends the turn: pinch zoom).
   * Letting go while still moving coasts on (unless the user prefers reduced motion); any press stops that.
   */
  const startRotate = (e: React.PointerEvent) => {
    stopCoasting();
    if (dims !== 3 || e.button !== 0 || e.shiftKey || e.ctrlKey || e.metaKey || !e.isPrimary) return;
    const id = e.pointerId;
    let lastX = e.clientX;
    let lastY = e.clientY;
    const samples: DragSample[] = [{ t: e.timeStamp, dx: 0, dy: 0 }];
    const move = (ev: PointerEvent) => {
      if (ev.pointerId !== id) return;
      const dx = ev.clientX - lastX;
      const dy = ev.clientY - lastY;
      lastX = ev.clientX;
      lastY = ev.clientY;
      samples.push({ t: ev.timeStamp, dx, dy });
      while (samples.length > 2 && ev.timeStamp - samples[1]!.t > RELEASE_WINDOW_MS) samples.shift();
      setRotation((r) => dragRotate(r, dx, dy, radiansPerPixel));
    };
    const end = () => {
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", up);
      window.removeEventListener("pointercancel", cancel);
      window.removeEventListener("pointerdown", other);
    };
    const up = (ev: PointerEvent) => {
      if (ev.pointerId !== id) return;
      end();
      if (!window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
        const { vx, vy } = releaseVelocity(samples, ev.timeStamp);
        startCoasting(vx, vy);
      }
    };
    const cancel = (ev: PointerEvent) => {
      if (ev.pointerId === id) end();
    };
    const other = (ev: PointerEvent) => {
      if (ev.pointerId !== id) end();
    };
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", up);
    window.addEventListener("pointercancel", cancel);
    window.addEventListener("pointerdown", other);
  };

  const startCoasting = (startVx: number, startVy: number) => {
    let vx = startVx;
    let vy = startVy;
    let last: number | null = null;
    const frame = (now: number) => {
      const dt = last == null ? 0 : Math.min(now - last, 100); // a backgrounded tab resumes gently, not with a jump
      last = now;
      const step = coast(vx, vy, dt);
      vx = step.vx;
      vy = step.vy;
      if (step.dx !== 0 || step.dy !== 0) setRotation((r) => dragRotate(r, step.dx, step.dy, radiansPerPixel));
      coasting.current = Math.hypot(vx, vy) >= MIN_COAST_SPEED ? requestAnimationFrame(frame) : null;
    };
    if (Math.hypot(vx, vy) >= MIN_COAST_SPEED) coasting.current = requestAnimationFrame(frame);
  };

  // The camera fits the data when the layout (or its dimensions) changes, not on every resize: a new object here
  // makes deck.gl reset the view, discarding the user's zoom and rotation.
  const fitTo = size ? Math.min(size.w, size.h) : 0;
  const fitRef = useRef(fitTo);
  if (fitTo > 0 && fitRef.current === 0) fitRef.current = fitTo;
  const initialViewState = useMemo(() => {
    const zoom = Math.log2(Math.max(fitRef.current, 100) / 2 * (dims === 3 ? 0.6 : 0.9));
    // 3-D: the camera looks straight down -z (screen axes = world axes); the plot itself is turned (modelMatrix)
    return dims === 3
      ? { target: [0, 0, 0] as [number, number, number], rotationX: 0, rotationOrbit: 0, zoom, minZoom: zoom - 3, maxZoom: zoom + 8 }
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
    modelMatrix: dims === 3 ? turned : null,
    getPosition: (d) => d.position,
    getRadius: (d) =>
      d.point.id === pinnedId ? 6.5 : highlightGroup != null && groupId(d.point) === highlightGroup ? 5 : 3.5,
    getFillColor: (d) => {
      const dimmed = highlightGroup != null && groupId(d.point) !== highlightGroup;
      return [...colour(groupId(d.point)), dimmed ? 35 : 225];
    },
    getLineColor: [255, 255, 255, 230],
    getLineWidth: (d) => (d.point.id === pinnedId ? 2 : 0),
    updateTriggers: {
      getRadius: [pinnedId, highlightGroup, groupId],
      getFillColor: [highlightGroup, colour, groupId],
      getLineWidth: [pinnedId],
    },
  });

  return (
    <div ref={wrapRef} className="vectors-deck" onPointerDown={startRotate}>
      {size && size.w > 0 && (
        <DeckGL
          views={view}
          initialViewState={initialViewState}
          // 3-D: rotating is the trackball's (startRotate); the camera only zooms and pans
          controller={dims === 3 ? { dragRotate: false, touchRotate: false, keyboard: false } : true}
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
