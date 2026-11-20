import type { Book, BookMetadataFields, BookMetadataOriginalFields, CalibreExtras, MetadataCandidate, MetadataCoverCandidate } from "../../api/types";

/** The metadata editor's form state, and how it maps to and from the API's fields. Pure, for testing. */

export interface Draft {
  title: string;
  authors: string[];
  tags: string[];
  series: string;
  seriesIndex: string;
  publisher: string;
  pubdate: string;
  languages: string[];
  titleSort: string;
  authorSort: string;
  description: string;
  identifiers: string;
  rating: string;
  addedAt: string;
}

export type MetadataLookupField = "title" | "authors" | "publisher" | "pubdate" | "identifiers" | "languages" | "tags" | "description";

export const METADATA_LOOKUP_FIELDS: MetadataLookupField[] = [
  "title", "authors", "publisher", "pubdate", "identifiers", "languages", "tags", "description",
];

export const METADATA_LOOKUP_LABELS: Record<MetadataLookupField, string> = {
  title: "Title",
  authors: "Authors",
  publisher: "Publisher",
  pubdate: "Published",
  identifiers: "Identifiers",
  languages: "Languages",
  tags: "Tags",
  description: "Description",
};

export function localDateTime(value: string | null | undefined): string {
  if (!value) return "";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "" : new Date(date.getTime() - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
}

export function draftFrom(book: Book, calibre: CalibreExtras | null): Draft {
  return {
    title: book.title,
    authors: book.authors,
    tags: book.tags,
    series: book.series ?? "",
    seriesIndex: book.seriesIndex?.toString() ?? "",
    publisher: calibre?.publisher ?? book.publisher ?? "",
    pubdate: book.pubdate?.slice(0, 10) ?? "",
    languages: book.languages,
    titleSort: calibre?.titleSort ?? "",
    authorSort: calibre?.authorSort ?? "",
    description: calibre?.description ?? "",
    identifiers: Object.entries(calibre?.identifiers ?? {}).map(([type, value]) => `${type}: ${value}`).join("\n"),
    rating: calibre?.rating?.toString() ?? "",
    addedAt: localDateTime(calibre?.addedAt),
  };
}

export function metadataValuesFrom(book: Book, calibre: CalibreExtras | null): Record<string, unknown> {
  return {
    title: book.title,
    authors: book.authors,
    tags: book.tags,
    series: book.series,
    seriesIndex: book.seriesIndex,
    publisher: calibre?.publisher ?? book.publisher ?? null,
    pubdate: book.pubdate?.slice(0, 10) ?? null,
    languages: book.languages,
    titleSort: calibre?.titleSort ?? null,
    authorSort: calibre?.authorSort ?? null,
    description: calibre?.description ?? null,
    identifiers: calibre?.identifiers ?? {},
    rating: calibre?.rating ?? null,
    addedAt: calibre?.addedAt ?? null,
  };
}

export const METADATA_FIELD_LABELS: Record<keyof BookMetadataFields, string> = {
  title: "Title",
  authors: "Authors",
  tags: "Tags",
  series: "Series",
  seriesIndex: "Series number",
  publisher: "Publisher",
  pubdate: "Published",
  languages: "Languages",
  titleSort: "Title sort",
  authorSort: "Author sort",
  description: "Description",
  identifiers: "Identifiers",
  rating: "Rating",
  addedAt: "Date added",
};

export function isMetadataField(field: string): field is keyof BookMetadataFields {
  return Object.prototype.hasOwnProperty.call(METADATA_FIELD_LABELS, field);
}

export function metadataConflicts(details: unknown): Partial<Record<keyof BookMetadataFields, unknown>> | null {
  if (typeof details !== "object" || details === null || Array.isArray(details)) return null;
  const rawConflicts = (details as { conflicts?: unknown }).conflicts;
  if (typeof rawConflicts !== "object" || rawConflicts === null || Array.isArray(rawConflicts)) return null;
  const result: Partial<Record<keyof BookMetadataFields, unknown>> = {};
  for (const [field, value] of Object.entries(rawConflicts)) {
    if (isMetadataField(field)) result[field] = value;
  }
  return result;
}

export function stringValue(value: unknown): string {
  return typeof value === "string" ? value : "";
}

export function stringList(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === "string") : [];
}

export function stringMap(value: unknown): Record<string, string> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return {};
  const result: Record<string, string> = {};
  for (const [key, item] of Object.entries(value)) {
    if (typeof item === "string") result[key] = item;
  }
  return result;
}

export function draftWithValue(draft: Draft, field: keyof BookMetadataFields, value: unknown): Draft {
  switch (field) {
    case "title": return { ...draft, title: stringValue(value) };
    case "authors": return { ...draft, authors: stringList(value) };
    case "tags": return { ...draft, tags: stringList(value) };
    case "series": return { ...draft, series: stringValue(value) };
    case "seriesIndex": return { ...draft, seriesIndex: typeof value === "number" ? String(value) : "" };
    case "publisher": return { ...draft, publisher: stringValue(value) };
    case "pubdate": return { ...draft, pubdate: stringValue(value).slice(0, 10) };
    case "languages": return { ...draft, languages: stringList(value) };
    case "titleSort": return { ...draft, titleSort: stringValue(value) };
    case "authorSort": return { ...draft, authorSort: stringValue(value) };
    case "description": return { ...draft, description: stringValue(value) };
    case "identifiers": return {
      ...draft,
      identifiers: Object.entries(stringMap(value)).map(([type, identifier]) => `${type}: ${identifier}`).join("\n"),
    };
    case "rating": return { ...draft, rating: typeof value === "number" ? String(value) : "" };
    case "addedAt": return { ...draft, addedAt: localDateTime(stringValue(value)) };
  }
}

export function metadataValueText(value: unknown): string {
  if (value === null || value === undefined || value === "") return "(empty)";
  if (Array.isArray(value)) return value.length > 0 ? value.map(String).join(", ") : "(empty)";
  if (typeof value === "object") {
    const entries = Object.entries(value).map(([key, item]) => `${key}: ${String(item)}`);
    return entries.length > 0 ? entries.join(", ") : "(empty)";
  }
  return String(value);
}

export function candidateValues(candidate: MetadataCandidate): Partial<Record<MetadataLookupField, unknown>> {
  return {
    title: candidate.title,
    authors: candidate.authors,
    publisher: candidate.publisher,
    pubdate: candidate.publishedDate,
    identifiers: candidate.identifiers,
    languages: candidate.languages,
    tags: candidate.tags,
    description: candidate.description,
  };
}

export function draftValues(draft: Draft): Record<MetadataLookupField, unknown> {
  return {
    title: draft.title,
    authors: draft.authors,
    publisher: draft.publisher,
    pubdate: draft.pubdate,
    identifiers: identifiers(draft.identifiers),
    languages: draft.languages,
    tags: draft.tags,
    description: draft.description,
  };
}

export function hasValue(value: unknown): boolean {
  if (value === null || value === undefined) return false;
  if (typeof value === "string") return value.trim().length > 0;
  if (Array.isArray(value)) return value.length > 0;
  if (typeof value === "object") return Object.keys(value).length > 0;
  return true;
}

export function sameMetadataValue(field: MetadataLookupField, left: unknown, right: unknown): boolean {
  if (field === "identifiers" && typeof left === "object" && left !== null && typeof right === "object" && right !== null) {
    const sorted = (value: object) => Object.entries(value).sort(([a], [b]) => a.localeCompare(b));
    return JSON.stringify(sorted(left)) === JSON.stringify(sorted(right));
  }
  return JSON.stringify(left) === JSON.stringify(right);
}

export function fieldsToSelect(candidate: MetadataCandidate, draft: Draft): Set<MetadataLookupField> {
  const incoming = candidateValues(candidate);
  const current = draftValues(draft);
  return new Set(
    METADATA_LOOKUP_FIELDS.filter((field) => hasValue(incoming[field]) && !sameMetadataValue(field, incoming[field], current[field])),
  );
}

export function coverCandidateFile(candidate: MetadataCoverCandidate): File {
  const binary = atob(candidate.image);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
  const extension = candidate.mediaType.slice("image/".length);
  return new File([bytes.buffer], `cover.${extension}`, { type: candidate.mediaType });
}

export function originalFields(fields: BookMetadataFields, baseline: Record<string, unknown>): BookMetadataOriginalFields {
  const original: BookMetadataOriginalFields = {};
  for (const field of Object.keys(fields)) {
    if (isMetadataField(field)) Object.assign(original, { [field]: baseline[field] });
  }
  return original;
}

export function identifiers(value: string): Record<string, string> {
  return Object.fromEntries(
    value.split(/\r?\n/).map((line) => line.trim()).filter(Boolean).map((line) => {
      const colon = line.indexOf(":");
      return [line.slice(0, colon).trim(), line.slice(colon + 1).trim()];
    }),
  );
}

export function sameValues(a: string[], b: string[]): boolean {
  return a.length === b.length && a.every((value, index) => value === b[index]);
}

export function changedFields(initial: Draft, draft: Draft): BookMetadataFields {
  return {
    ...(draft.title.trim() !== initial.title ? { title: draft.title.trim() } : {}),
    ...(!sameValues(draft.authors, initial.authors) ? { authors: draft.authors } : {}),
    ...(!sameValues(draft.tags, initial.tags) ? { tags: draft.tags } : {}),
    ...(draft.series !== initial.series ? { series: draft.series.trim() || null } : {}),
    ...(draft.seriesIndex !== initial.seriesIndex
      ? { seriesIndex: draft.seriesIndex.trim() === "" ? null : Number(draft.seriesIndex) }
      : {}),
    ...(draft.publisher !== initial.publisher ? { publisher: draft.publisher.trim() || null } : {}),
    ...(draft.pubdate !== initial.pubdate ? { pubdate: draft.pubdate || null } : {}),
    ...(!sameValues(draft.languages, initial.languages) ? { languages: draft.languages } : {}),
    ...(draft.titleSort !== initial.titleSort ? { titleSort: draft.titleSort.trim() || null } : {}),
    ...(draft.authorSort !== initial.authorSort ? { authorSort: draft.authorSort.trim() || null } : {}),
    ...(draft.description !== initial.description ? { description: draft.description } : {}),
    ...(draft.identifiers !== initial.identifiers ? { identifiers: identifiers(draft.identifiers) } : {}),
    ...(draft.rating !== initial.rating ? { rating: draft.rating.trim() === "" ? null : Number(draft.rating) } : {}),
    ...(draft.addedAt !== initial.addedAt
      ? { addedAt: draft.addedAt ? new Date(draft.addedAt).toISOString() : null }
      : {}),
  };
}
