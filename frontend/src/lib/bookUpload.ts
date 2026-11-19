const UPLOADABLE_FORMATS = [
  "AZW", "AZW1", "AZW3", "AZW4", "CB7", "CBC", "CBR", "CBZ", "CHM", "DOCX", "DJVU", "EPUB", "FB2", "FBZ",
  "HTML", "HTMLZ", "IMP", "KEPUB", "LIT", "LRF", "LRX", "MOBI", "ODT", "OEBZIP", "PDB", "PDF", "PML",
  "PMLZ", "POBI", "PRC", "RTF", "SNB", "TPZ", "TXT", "TXTZ", "UPDB", "ZIP",
] as const;

const FORMAT_SET = new Set<string>(UPLOADABLE_FORMATS);

export const BOOK_UPLOAD_ACCEPT = UPLOADABLE_FORMATS.map((format) => `.${format.toLowerCase()}`).join(",");

export function validateBookUploadFiles(files: File[]): string | null {
  if (files.length === 0) return "Choose at least one book file.";
  const groupedFormats = new Map<string, Set<string>>();
  for (const file of files) {
    const name = file.name.split(/[\\/]/).at(-1) ?? "";
    const dot = name.lastIndexOf(".");
    const base = dot > 0 ? name.slice(0, dot).toLowerCase() : "";
    const format = dot > 0 ? name.slice(dot + 1).toUpperCase() : "";
    if (!base || !FORMAT_SET.has(format)) return `${file.name} is not a supported Calibre ebook format.`;
    const formats = groupedFormats.get(base) ?? new Set<string>();
    if (formats.has(format)) return `Only one ${format} file can be uploaded for "${name.slice(0, dot)}".`;
    formats.add(format);
    groupedFormats.set(base, formats);
  }
  return null;
}
