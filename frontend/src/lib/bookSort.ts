import type { BookQuery, BookSort } from "../api/types";

/**
 * [ascending]/[descending] say what each direction means for the field. [newestFirst]: the API's unprefixed order
 * is descending (and so the one a pick of the field starts with); for the rest it is ascending.
 */
export const SORTS: {
  value: BookSort;
  label: string;
  kind: "alphabetical" | "numerical";
  ascending: string;
  descending: string;
  newestFirst?: boolean;
}[] = [
  { value: "title", label: "Title", kind: "alphabetical", ascending: "A → Z", descending: "Z → A" },
  { value: "author", label: "Author", kind: "alphabetical", ascending: "A → Z", descending: "Z → A" },
  { value: "publisher", label: "Publisher", kind: "alphabetical", ascending: "A → Z", descending: "Z → A" },
  { value: "pubdate", label: "Published", kind: "numerical", ascending: "Oldest first", descending: "Newest first", newestFirst: true },
  { value: "indexedAt", label: "Indexed at", kind: "numerical", ascending: "Oldest first", descending: "Newest first", newestFirst: true },
  { value: "id", label: "ID", kind: "numerical", ascending: "Lowest first", descending: "Highest first" },
];

/** The API's sort parameter: the field, prefixed with "-" for the opposite of its unprefixed order. */
export function sortParam(field: BookSort, descending: boolean): BookQuery["sort"] {
  const unprefixedDescending = SORTS.find((s) => s.value === field)?.newestFirst ?? false;
  return descending === unprefixedDescending ? field : `-${field}`;
}
