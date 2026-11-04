import { useEffect, useState } from "react";
import { api, errorMessage } from "../api/client";
import type { TagCount } from "../api/types";
import { useApp } from "../AppContext";

/** A book attribute the library can be filtered by, with how many books have each value. */
export type Facet = "tags" | "publishers";

export interface FacetLabels {
  icon: "tag" | "office";
  /** the no-filter choice, e.g. "Any tag" */
  any: string;
  /** "Filter tags…" — typed into the dropdown's search box */
  search: string;
  noMatch: string;
  /** the multi-select's accessible name, e.g. "Limit to tags" */
  limitTo: string;
  /** the single-select's accessible name, e.g. "Filter by tag" */
  filterBy: string;
}

export const FACET_LABELS: Record<Facet, FacetLabels> = {
  tags: {
    icon: "tag",
    any: "Any tag",
    search: "Filter tags…",
    noMatch: "No matching tags",
    limitTo: "Limit to tags",
    filterBy: "Filter by tag",
  },
  publishers: {
    icon: "office",
    any: "Any publisher",
    search: "Filter publishers…",
    noMatch: "No matching publishers",
    limitTo: "Limit to publishers",
    filterBy: "Filter by publisher",
  },
};

const cache = new Map<string, Promise<TagCount[]>>();

/** Tags and publishers rarely change: fetched once per (facet, library) until a library sync clears them. */
function loadFacet(facet: Facet, libraryId?: number): Promise<TagCount[]> {
  const key = `${facet}:${libraryId ?? "all"}`;
  let cached = cache.get(key);
  if (!cached) {
    cached = (facet === "tags" ? api.tags(libraryId) : api.publishers(libraryId)).catch((err) => {
      cache.delete(key); // retry on the next use
      throw err;
    });
    cache.set(key, cached);
  }
  return cached;
}

/** Forgets every fetched list, e.g. after a library sync changed the books. */
export function clearFacetCache() {
  cache.clear();
}

/** The values of [facet] in the library chosen in the navbar (or all of them), most books first. */
export function useFacet(facet: Facet) {
  const { library } = useApp();
  const [items, setItems] = useState<TagCount[]>([]);
  const [error, setError] = useState<string>();
  useEffect(() => {
    let alive = true;
    loadFacet(facet, library?.id)
      .then((t) => alive && setItems(t))
      .catch((err) => alive && setError(errorMessage(err)));
    return () => {
      alive = false;
    };
  }, [facet, library?.id]);
  return { items, error };
}
