import { describe, expect, test } from "bun:test";
import {
  apply,
  coast,
  dragRotate,
  IDENTITY,
  INERTIA_MS,
  INITIAL_ROTATION,
  modelMatrix,
  multiply,
  RELEASE_WINDOW_MS,
  releaseVelocity,
  type Rotation,
} from "./trackball";

const close = (a: readonly number[], b: readonly number[]) => a.forEach((v, i) => expect(v).toBeCloseTo(b[i]!, 9));
const QUARTER = Math.PI / 2;

/** R Rᵀ = I and det R = 1 */
function expectRotation(m: Rotation) {
  const t = [m[0]!, m[3]!, m[6]!, m[1]!, m[4]!, m[7]!, m[2]!, m[5]!, m[8]!];
  close(multiply(m, t), IDENTITY);
  const det =
    m[0]! * (m[4]! * m[8]! - m[5]! * m[7]!) - m[1]! * (m[3]! * m[8]! - m[5]! * m[6]!) + m[2]! * (m[3]! * m[7]! - m[4]! * m[6]!);
  expect(det).toBeCloseTo(1, 9);
}

describe("trackball", () => {
  test("the front of the plot follows the pointer", () => {
    // a quarter turn's drag to the right brings the front (+z) to the right (+x)
    close(apply(dragRotate(IDENTITY, 100, 0, QUARTER / 100), [0, 0, 1]), [1, 0, 0]);
    // downwards (screen y grows): the front goes down (-y)
    close(apply(dragRotate(IDENTITY, 0, 100, QUARTER / 100), [0, 0, 1]), [0, -1, 0]);
  });

  test("tilting carries on past the poles, unlike an orbit camera", () => {
    // three quarter turns downwards: the top (+y) has come over the front, gone under and round to the back (-z)
    const m = dragRotate(IDENTITY, 0, 300, QUARTER / 100);
    close(apply(m, [0, 1, 0]), [0, 0, -1]);
    // a full turn is back where it started
    close(dragRotate(IDENTITY, 0, 400, QUARTER / 100), IDENTITY);
  });

  test("a circular drag rolls the plot about the line of sight", () => {
    // right, down, left, up: a small loop leaves a net turn about z, which an orbit camera can't do
    let m: Rotation = IDENTITY;
    for (const [dx, dy] of [[1, 0], [0, 1], [-1, 0], [0, -1]] as const) {
      for (let k = 0; k < 20; k++) m = dragRotate(m, dx, dy, 0.01);
    }
    const x = apply(m, [1, 0, 0]);
    expect(Math.abs(x[1])).toBeGreaterThan(0.01); // +x moved within the screen plane
    expect(Math.abs(x[2])).toBeLessThan(Math.abs(x[1]));
  });

  test("many drags stay a pure rotation", () => {
    let m: Rotation = INITIAL_ROTATION;
    for (let k = 0; k < 5000; k++) m = dragRotate(m, Math.sin(k) * 7, Math.cos(k * 1.3) * 5, 0.01);
    expectRotation(m);
  });

  test("the model matrix is the rotation, column-major", () => {
    const m = dragRotate(INITIAL_ROTATION, 30, -12, 0.01);
    const mm = modelMatrix(m);
    const v: [number, number, number] = [0.3, -0.5, 0.8];
    // column-major: out_r = Σ_c mm[c * 4 + r] v_c
    const out = [0, 1, 2].map((r) => mm[r]! * v[0] + mm[4 + r]! * v[1] + mm[8 + r]! * v[2]);
    close(out, apply(m, v));
    expect(Array.from(mm.slice(12))).toEqual([0, 0, 0, 1]);
  });

  describe("inertia", () => {
    const press = { t: 1000, dx: 0, dy: 0 };

    test("the release velocity is the recent moves over their time", () => {
      // 10 px right every 10 ms: 1 px/ms
      const samples = [press, ...Array.from({ length: 20 }, (_, k) => ({ t: 1010 + 10 * k, dx: 10, dy: -5 }))];
      const v = releaseVelocity(samples, 1200);
      expect(v.vx).toBeCloseTo(1, 1);
      expect(v.vy).toBeCloseTo(-0.5, 1);
    });

    test("a pause before letting go means no coasting", () => {
      const samples = [press, { t: 1010, dx: 30, dy: 0 }];
      expect(releaseVelocity(samples, 1010 + RELEASE_WINDOW_MS + 1)).toEqual({ vx: 0, vy: 0 });
    });

    test("a short flick counts from the press", () => {
      const v = releaseVelocity([press, { t: 1020, dx: 40, dy: 0 }], 1020);
      expect(v.vx).toBeCloseTo(2, 9);
    });

    test("coasting slows down, and glides as far whatever the frame rate", () => {
      const glide = (dt: number) => {
        let vx = 1;
        let total = 0;
        for (let t = 0; t < 5000; t += dt) {
          const s = coast(vx, 0, dt);
          total += s.dx;
          expect(s.vx).toBeLessThan(vx);
          vx = s.vx;
        }
        return total;
      };
      // the whole glide is v·τ, at 60 Hz or 144 Hz alike
      expect(glide(1000 / 60)).toBeCloseTo(INERTIA_MS, 3);
      expect(glide(1000 / 144)).toBeCloseTo(INERTIA_MS, 3);
    });
  });
});
