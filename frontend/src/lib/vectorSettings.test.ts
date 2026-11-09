import { describe, expect, test } from "bun:test";
import {
  DEFAULT_VECTOR_SETTINGS,
  layoutParams,
  sameVectorSettings,
  sanitizeVectorSettings,
  validSetting,
} from "./vectorSettings";

describe("vector settings", () => {
  test("stored settings keep valid fields and default the rest", () => {
    expect(sanitizeVectorSettings(null)).toEqual(DEFAULT_VECTOR_SETTINGS);
    expect(sanitizeVectorSettings("garbage")).toEqual(DEFAULT_VECTOR_SETTINGS);
    const s = sanitizeVectorSettings({
      neighbors: 40,
      minDist: 5, // out of range
      epochs: 12.5, // not a whole number
      perplexity: "50", // not a number
      minClusterSize: 10,
      minSamples: null,
    });
    expect(s).toEqual({ ...DEFAULT_VECTOR_SETTINGS, neighbors: 40, minClusterSize: 10 });
  });

  test("only the topic settings may be blank", () => {
    expect(validSetting("minClusterSize", null)).toBe(true);
    expect(validSetting("neighbors", null)).toBe(false);
    expect(validSetting("minDist", 0)).toBe(true);
    expect(validSetting("iterations", 250)).toBe(false);
  });

  test("a layout gets its own method's parameters only", () => {
    const s = { ...DEFAULT_VECTOR_SETTINGS, neighbors: 30, perplexity: 50 };
    expect(layoutParams("umap", s)).toEqual({ neighbors: 30, minDist: 0.1, epochs: 300 });
    expect(layoutParams("tsne", s)).toEqual({ perplexity: 50, iterations: 1000 });
    expect(layoutParams("pca", s)).toEqual({});
  });

  test("settings compare by value", () => {
    expect(sameVectorSettings(DEFAULT_VECTOR_SETTINGS, { ...DEFAULT_VECTOR_SETTINGS })).toBe(true);
    expect(sameVectorSettings(DEFAULT_VECTOR_SETTINGS, { ...DEFAULT_VECTOR_SETTINGS, minSamples: 3 })).toBe(false);
  });
});
