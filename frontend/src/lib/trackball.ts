/**
 * Trackball rotation for the 3-D plot: dragging turns the data about the screen's own axes (a horizontal drag about
 * the vertical axis, a vertical drag about the horizontal one, a diagonal drag about the axis between), so every
 * orientation is reachable — unlike an orbit camera, whose tilt stops at the poles and which can't roll.
 *
 * Rotations are 3×3 matrices, row-major. The camera looks down -z with +y up and +x right, so screen axes are
 * world axes and a drag's rotation is applied on the left (in screen space), after whatever came before.
 */
export type Rotation = readonly number[]; // 9 numbers, row-major

export const IDENTITY: Rotation = [1, 0, 0, 0, 1, 0, 0, 0, 1];

/** Rotation by [angle] radians about the unit vector ([x], [y], [z]) (Rodrigues). */
export function axisAngle(x: number, y: number, z: number, angle: number): Rotation {
  const c = Math.cos(angle);
  const s = Math.sin(angle);
  const t = 1 - c;
  return [
    t * x * x + c, t * x * y - s * z, t * x * z + s * y,
    t * x * y + s * z, t * y * y + c, t * y * z - s * x,
    t * x * z - s * y, t * y * z + s * x, t * z * z + c,
  ];
}

export function multiply(a: Rotation, b: Rotation): Rotation {
  const out = new Array<number>(9);
  for (let r = 0; r < 3; r++) {
    for (let c = 0; c < 3; c++) {
      out[r * 3 + c] = a[r * 3]! * b[c]! + a[r * 3 + 1]! * b[3 + c]! + a[r * 3 + 2]! * b[6 + c]!;
    }
  }
  return out;
}

/** Applies [m] to the vector [v]. */
export function apply(m: Rotation, v: readonly [number, number, number]): [number, number, number] {
  return [
    m[0]! * v[0] + m[1]! * v[1] + m[2]! * v[2],
    m[3]! * v[0] + m[4]! * v[1] + m[5]! * v[2],
    m[6]! * v[0] + m[7]! * v[1] + m[8]! * v[2],
  ];
}

/** Gram-Schmidt on the rows: keeps thousands of small drags from drifting away from a pure rotation. */
function orthonormalize(m: Rotation): Rotation {
  const r0 = [m[0]!, m[1]!, m[2]!];
  const n0 = Math.hypot(r0[0]!, r0[1]!, r0[2]!) || 1;
  const a = r0.map((v) => v / n0);
  const r1 = [m[3]!, m[4]!, m[5]!];
  const d = a[0]! * r1[0]! + a[1]! * r1[1]! + a[2]! * r1[2]!;
  const b0 = r1.map((v, i) => v - d * a[i]!);
  const n1 = Math.hypot(b0[0]!, b0[1]!, b0[2]!) || 1;
  const b = b0.map((v) => v / n1);
  // the third row is the cross product: right-handed by construction
  const c = [a[1]! * b[2]! - a[2]! * b[1]!, a[2]! * b[0]! - a[0]! * b[2]!, a[0]! * b[1]! - a[1]! * b[0]!];
  return [...a, ...b, ...c];
}

/**
 * [m] turned by a pointer drag of ([dx], [dy]) CSS pixels (y down, as pointer events have it), at
 * [radiansPerPixel]: the point under the pointer on the front of the plot follows it.
 */
export function dragRotate(m: Rotation, dx: number, dy: number, radiansPerPixel: number): Rotation {
  const length = Math.hypot(dx, dy);
  if (length === 0) return m;
  // screen-down drag: the front (+z) moves to -y, a positive turn about +x; screen-right: front to +x, about +y
  const turn = axisAngle(dy / length, dx / length, 0, length * radiansPerPixel);
  return orthonormalize(multiply(turn, m));
}

/** [m] as the column-major 4×4 model matrix deck.gl takes. */
export function modelMatrix(m: Rotation): Float64Array {
  return Float64Array.of(m[0]!, m[3]!, m[6]!, 0, m[1]!, m[4]!, m[7]!, 0, m[2]!, m[5]!, m[8]!, 0, 0, 0, 0, 1);
}

/** The starting view: tilted towards the viewer a little and turned, so depth is visible from the start. */
export const INITIAL_ROTATION: Rotation = multiply(axisAngle(1, 0, 0, (20 * Math.PI) / 180), axisAngle(0, 1, 0, (-30 * Math.PI) / 180));

/** A pointer move during a drag: [dx], [dy] pixels, ending at time [t] (ms). */
export interface DragSample {
  t: number;
  dx: number;
  dy: number;
}

/** How far back from the release the drag's speed is measured, in ms: a pause longer than this means no coasting. */
export const RELEASE_WINDOW_MS = 80;

/** Coasting slows to 1/e of its speed in this many ms (as deck.gl's own orbit inertia defaults to 300). */
export const INERTIA_MS = 300;

/** Below this speed (pixels/ms) coasting stops. */
export const MIN_COAST_SPEED = 0.02;

/**
 * The pointer's velocity (pixels/ms) as it was let go at [now]: the moves of the last [RELEASE_WINDOW_MS] over that
 * span, so one jittery final event doesn't decide it. Zero when it had stopped before letting go.
 */
export function releaseVelocity(samples: readonly DragSample[], now: number): { vx: number; vy: number } {
  const first = samples.findIndex((s) => now - s.t <= RELEASE_WINDOW_MS);
  if (first < 0) return { vx: 0, vy: 0 };
  // a move covers the time since the one before it; callers start with a zero sample at the press, so a quick
  // flick is measured from the press (and a move after holding still, from where it was held)
  const from = samples[Math.max(first - 1, 0)]!.t;
  const span = Math.max(now - from, 1);
  let dx = 0;
  let dy = 0;
  for (let i = first; i < samples.length; i++) {
    dx += samples[i]!.dx;
    dy += samples[i]!.dy;
  }
  return { vx: dx / span, vy: dy / span };
}

/**
 * One frame of coasting at velocity ([vx], [vy]) for [dt] ms, slowing exponentially: the pixels to turn by (exact
 * for the decay, so the total glide doesn't depend on the frame rate) and the velocity left.
 */
export function coast(vx: number, vy: number, dt: number): { dx: number; dy: number; vx: number; vy: number } {
  const keep = Math.exp(-dt / INERTIA_MS);
  const travel = INERTIA_MS * (1 - keep);
  return { dx: vx * travel, dy: vy * travel, vx: vx * keep, vy: vy * keep };
}
