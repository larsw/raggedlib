import type { LayoutParams, VectorMethod } from "../api/types";

/**
 * The Visualization tab's tunable parameters (persisted in this browser): UMAP's and t-SNE's for the projection, HDBSCAN's
 * for topics. Defaults and ranges are the backend's (LayoutParams, TopicSpec in VectorSpaceService.kt).
 */
export interface VectorSettings {
  /** UMAP: neighbours per point in the fuzzy graph; larger keeps more of the global shape */
  neighbors: number;
  /** UMAP: how tightly points may pack; 0 makes dense clumps, larger spreads them evenly */
  minDist: number;
  /** UMAP: optimisation epochs */
  epochs: number;
  /** t-SNE: about how many neighbours each point balances */
  perplexity: number;
  /** t-SNE: optimisation iterations */
  iterations: number;
  /** HDBSCAN: the smallest group that counts as a topic; null: about 2.5 % of the sample, between 5 and 50 */
  minClusterSize: number | null;
  /** HDBSCAN: how conservative the density estimate is (higher: more noise); null: the same as minClusterSize */
  minSamples: number | null;
}

export type VectorSettingKey = keyof VectorSettings;

export const DEFAULT_VECTOR_SETTINGS: VectorSettings = {
  neighbors: 15,
  minDist: 0.1,
  epochs: 300,
  perplexity: 30,
  iterations: 1000,
  minClusterSize: null,
  minSamples: null,
};

export interface SettingLimit {
  min: number;
  max: number;
  step: number;
  /** whole numbers only */
  integer: boolean;
  /** may be left blank (null): the server picks */
  optional: boolean;
}

export const VECTOR_SETTING_LIMITS: Record<VectorSettingKey, SettingLimit> = {
  neighbors: { min: 2, max: 100, step: 1, integer: true, optional: false },
  minDist: { min: 0, max: 0.99, step: 0.05, integer: false, optional: false },
  epochs: { min: 50, max: 1000, step: 50, integer: true, optional: false },
  perplexity: { min: 2, max: 100, step: 1, integer: false, optional: false },
  iterations: { min: 300, max: 2000, step: 100, integer: true, optional: false },
  minClusterSize: { min: 2, max: 500, step: 1, integer: true, optional: true },
  minSamples: { min: 1, max: 500, step: 1, integer: true, optional: true },
};

const KEYS = Object.keys(DEFAULT_VECTOR_SETTINGS) as VectorSettingKey[];

/** Whether [value] is acceptable for [key] (null only where the setting is optional). */
export function validSetting(key: VectorSettingKey, value: number | null): boolean {
  const limit = VECTOR_SETTING_LIMITS[key];
  if (value == null) return limit.optional;
  return Number.isFinite(value) && value >= limit.min && value <= limit.max && (!limit.integer || Number.isInteger(value));
}

/** Stored settings, field by field: anything missing, malformed or out of range is its default. */
export function sanitizeVectorSettings(raw: unknown): VectorSettings {
  const obj = raw != null && typeof raw === "object" ? (raw as Record<string, unknown>) : {};
  const out = { ...DEFAULT_VECTOR_SETTINGS };
  for (const key of KEYS) {
    const v = obj[key];
    if ((typeof v === "number" || v === null) && validSetting(key, v)) (out as Record<VectorSettingKey, number | null>)[key] = v;
  }
  return out;
}

/** The parameters to send for [method]'s layout: only its own, so other methods' settings don't matter to it. */
export function layoutParams(method: VectorMethod, s: VectorSettings): LayoutParams {
  switch (method) {
    case "umap":
      return { neighbors: s.neighbors, minDist: s.minDist, epochs: s.epochs };
    case "tsne":
      return { perplexity: s.perplexity, iterations: s.iterations };
    case "pca":
      return {};
  }
}

/** Settings as edited, before they're known to be valid: any of them may be blank (null). */
export type VectorSettingsDraft = Record<VectorSettingKey, number | null>;

export function sameVectorSettings(a: VectorSettingsDraft, b: VectorSettingsDraft): boolean {
  return KEYS.every((k) => a[k] === b[k]);
}
