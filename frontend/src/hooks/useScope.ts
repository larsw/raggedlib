import { useEffect, useState } from "react";
import { loadJson, saveJson, STORAGE_KEYS } from "../lib/storage";

/** Minimal book identity kept in the scope (enough to render a tag without refetching). */
export interface BookRef {
  id: number;
  title: string;
  authors: string[];
}

export interface Scope {
  books: BookRef[];
  tags: string[];
  /** "" = backend default chat model */
  model: string;
  topK: number;
  /** Cross-encoder reranking of the retrieved passages (a preference: "Reset scope" keeps it). */
  rerank: boolean;
}

export const DEFAULT_TOP_K = 8;
export const MAX_TOP_K = 50;

export const EMPTY_SCOPE: Scope = { books: [], tags: [], model: "", topK: DEFAULT_TOP_K, rerank: true };

function sanitize(raw: Partial<Scope> | null): Scope {
  if (!raw || typeof raw !== "object") return EMPTY_SCOPE;
  return {
    books: Array.isArray(raw.books) ? raw.books.filter((b) => b && typeof b.id === "number") : [],
    tags: Array.isArray(raw.tags) ? raw.tags.filter((t) => typeof t === "string") : [],
    model: typeof raw.model === "string" ? raw.model : "",
    topK:
      typeof raw.topK === "number" && raw.topK >= 1 && raw.topK <= MAX_TOP_K ? Math.round(raw.topK) : DEFAULT_TOP_K,
    rerank: typeof raw.rerank === "boolean" ? raw.rerank : true,
  };
}

/** Retrieval scope shared by Ask and Search, persisted across reloads. */
export function useScope(): [Scope, React.Dispatch<React.SetStateAction<Scope>>] {
  const [scope, setScope] = useState<Scope>(() => sanitize(loadJson<Partial<Scope> | null>(STORAGE_KEYS.scope, null)));
  useEffect(() => saveJson(STORAGE_KEYS.scope, scope), [scope]);
  return [scope, setScope];
}

export function scopeIsEmpty(scope: Scope): boolean {
  return scope.books.length === 0 && scope.tags.length === 0;
}
