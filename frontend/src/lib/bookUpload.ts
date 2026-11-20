import type { UploadOptions } from "../api/types";

/** The Calibre format of a file name (`Novel.epub` → `EPUB`), or "" without an extension. */
export function formatOf(name: string): string {
  const base = name.split(/[\\/]/).at(-1) ?? "";
  const dot = base.lastIndexOf(".");
  return dot > 0 ? base.slice(dot + 1).toUpperCase() : "";
}

/** A file input's `accept` for the backend's formats; undefined (anything) until they're known. */
export function uploadAccept(options: UploadOptions | null | undefined): string | undefined {
  return options?.formats.map((format) => `.${format.toLowerCase()}`).join(",");
}

/**
 * Why one file can't be uploaded as a book format, or null. Without the options (not loaded) only the name is
 * checked; the backend checks again either way.
 */
export function validateFormatFile(file: File, options: UploadOptions | null | undefined): string | null {
  const format = formatOf(file.name);
  if (!format || (options && !options.formats.includes(format))) return `${file.name} is not a supported Calibre ebook format.`;
  if (options && file.size > options.maxFormatSize) {
    return `${file.name} is larger than the ${options.maxFormatSizeLabel} limit for one file.`;
  }
  return null;
}

/** Why [files] can't be uploaded together as books, or null: their formats, sizes, and one file per format per book. */
export function validateBookUploadFiles(files: File[], options: UploadOptions | null | undefined): string | null {
  if (files.length === 0) return "Choose at least one book file.";
  const groupedFormats = new Map<string, Set<string>>();
  for (const file of files) {
    const invalid = validateFormatFile(file, options);
    if (invalid) return invalid;
    const name = file.name.split(/[\\/]/).at(-1)!;
    const base = name.slice(0, name.lastIndexOf("."));
    const format = formatOf(name);
    const formats = groupedFormats.get(base.toLowerCase()) ?? new Set<string>();
    if (formats.has(format)) return `Only one ${format} file can be uploaded for "${base}".`;
    formats.add(format);
    groupedFormats.set(base.toLowerCase(), formats);
  }
  const total = files.reduce((sum, file) => sum + file.size, 0);
  if (options && total > options.maxRequestSize) {
    return `These files add up to more than the ${options.maxRequestSizeLabel} limit for one upload; add them in smaller batches.`;
  }
  return null;
}
