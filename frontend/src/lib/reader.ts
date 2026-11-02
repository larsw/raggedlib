/**
 * Pure helpers for the in-app ebook reader (foliate-js): which formats it opens, and how a cited passage or a
 * section heading is found again in the rendered book. Passages carry no position inside the ebook, only their
 * text and nearest heading, so both are matched against the book's own text.
 */

/** Calibre formats foliate-js renders, in the order the reader prefers them. PDFs open in the browser's viewer. */
export const READER_FORMATS = ["EPUB", "KEPUB", "AZW3", "AZW", "MOBI", "PRC", "FB2", "FBZ", "CBZ"] as const;

export function isReaderFormat(format: string): boolean {
  return (READER_FORMATS as readonly string[]).includes(format.toUpperCase());
}

/**
 * The format to open in the reader, or null if none can be. The indexed format comes first, because passages were
 * extracted from it, so its text matches theirs best.
 */
export function readerFormat(formats: readonly string[], sourceFormat?: string | null): string | null {
  const available = new Set(formats.map((f) => f.toUpperCase()));
  if (sourceFormat && available.has(sourceFormat.toUpperCase()) && isReaderFormat(sourceFormat)) {
    return sourceFormat.toUpperCase();
  }
  return READER_FORMATS.find((f) => available.has(f)) ?? null;
}

/** File name handed to foliate-js, which recognises FB2, FBZ and CBZ by extension (EPUB and MOBI by content). */
export function readerFileName(format: string): string {
  return `book.${format.toLowerCase()}`;
}

/** Where to open the book: at a cited passage, at a section heading, or (absent) where the reader left off. */
export type ReaderTarget =
  | { kind: "passage"; text: string; section: string | null }
  | { kind: "section"; title: string };

export interface ReaderRequest {
  bookId: number;
  title: string;
  /** a reader format the book has; chosen from the book's formats when absent */
  format?: string;
  target?: ReaderTarget;
}

// ── text matching ────────────────────────────────────────────────────────────────────────────

const WORD_CHAR = /[\p{L}\p{N}]/u;
/**
 * Ignored entirely rather than read as word breaks: soft hyphens, zero-width spaces and joiners, BOM (invisible
 * inside words), and `*` and `_`, which indexed text and headings can keep as markdown emphasis left over from
 * extraction ("Prog*ramming*"); identifiers like snake_case then read the same on both sides.
 */
const INVISIBLE = /[\p{Cf}*_]/u;
const MARK = /\p{M}/gu;

/**
 * Matching text reduced to lower-case letters and digits with single spaces between words, plus, for every
 * character of it, where it came from: segment index and code-unit offsets [start, end) in that segment.
 */
export interface MatchText {
  text: string;
  seg: number[];
  start: number[];
  end: number[];
}

/**
 * Normalises text segments (the text nodes of a document) for matching: case, accents, ligatures, punctuation and
 * whitespace differences disappear. `breaks[i]` true means a word boundary lies before segment i even without
 * whitespace (a new block element), so "end</p><p>Next" doesn't read as "endnext".
 */
export function normalizeSegments(segments: readonly string[], breaks?: readonly boolean[]): MatchText {
  const chars: string[] = [];
  const seg: number[] = [];
  const start: number[] = [];
  const end: number[] = [];
  let pendingSpace = false;
  let space = { s: 0, a: 0, b: 0 };
  const emit = (c: string, s: number, a: number, b: number) => {
    chars.push(c);
    seg.push(s);
    start.push(a);
    end.push(b);
  };

  for (let s = 0; s < segments.length; s++) {
    const str = segments[s]!;
    if (breaks?.[s] && chars.length > 0 && !pendingSpace) {
      pendingSpace = true;
      space = { s, a: 0, b: 0 };
    }
    let i = 0;
    for (const ch of str) {
      const a = i;
      i += ch.length;
      if (INVISIBLE.test(ch)) continue;
      for (const c of ch.normalize("NFKD").replace(MARK, "").toLowerCase()) {
        if (WORD_CHAR.test(c)) {
          if (pendingSpace) {
            emit(" ", space.s, space.a, space.b);
            pendingSpace = false;
          }
          emit(c, s, a, i);
        } else if (chars.length > 0 && !pendingSpace) {
          pendingSpace = true;
          space = { s, a, b: i };
        }
      }
    }
  }
  return { text: chars.join(""), seg, start, end };
}

/** Normalised words of a plain string, e.g. a passage or a heading. */
export function matchWords(text: string): string[] {
  const { text: t } = normalizeSegments([text]);
  return t ? t.split(" ") : [];
}

/** Index of `needle` (normalised) in `haystack` at word boundaries, from `from` on; -1 if absent. */
export function findWords(haystack: string, needle: string, from = 0): number {
  if (!needle) return -1;
  for (let i = haystack.indexOf(needle, from); i >= 0; i = haystack.indexOf(needle, i + 1)) {
    const before = i === 0 || haystack[i - 1] === " ";
    const after = i + needle.length === haystack.length || haystack[i + needle.length] === " ";
    if (before && after) return i;
  }
  return -1;
}

/** Words per probe: long enough to be unique within a book, short enough to dodge small text differences. */
export const PROBE_WORDS = 8;

/**
 * Word runs of a passage to look for in the book. Heads (from the start) locate where the passage begins, tails
 * (from the end) where it ends. Several of each, because footnote markers, list numbers or table layout can make
 * one run differ between the indexed text and the book.
 */
export function passageProbes(text: string, size = PROBE_WORDS, max = 3): { heads: string[]; tails: string[] } {
  const words = matchWords(text);
  if (words.length <= size * 2) {
    // too short for separate head and tail runs: look for all of it, then for either half
    if (words.length === 0) return { heads: [], tails: [] };
    const heads = [words.join(" ")];
    if (words.length > size) heads.push(words.slice(0, size).join(" "), words.slice(-size).join(" "));
    return { heads, tails: [] };
  }
  const heads: string[] = [];
  const tails: string[] = [];
  for (let k = 0; k < max && (k + 2) * size <= words.length; k++) {
    heads.push(words.slice(k * size, (k + 1) * size).join(" "));
    tails.push(words.slice(words.length - (k + 1) * size, words.length - k * size).join(" "));
  }
  return { heads, tails };
}

// ── table of contents ────────────────────────────────────────────────────────────────────────

export interface TocEntry {
  label?: string;
  href?: string;
  subitems?: TocEntry[] | null;
}

/**
 * Table-of-contents hrefs whose label matches a section heading, best first: the same words (numbering like
 * "Chapter 3" aside), then one starting or ending with the other, then one containing the other.
 */
export function tocMatches(toc: readonly TocEntry[] | undefined, heading: string | null | undefined): string[] {
  const want = matchWords(heading ?? "").join(" ");
  if (!want || !toc) return [];
  const scored: { href: string; score: number; order: number }[] = [];
  let order = 0;
  const visit = (items: readonly TocEntry[]) => {
    for (const item of items) {
      const label = matchWords(item.label ?? "").join(" ");
      if (item.href && label) {
        const score = tocScore(label, want);
        if (score > 0) scored.push({ href: item.href, score, order });
      }
      order++;
      if (item.subitems?.length) visit(item.subitems);
    }
  };
  visit(toc);
  scored.sort((a, b) => b.score - a.score || a.order - b.order);
  return [...new Set(scored.map((s) => s.href))];
}

/** "1", "chapter 3", "part ii", "1 2": numbering in front of a heading's words in a TOC label (or vice versa) */
const NUMBERING = /^(?:(?:chapter|ch|part|section|appendix|book) )?[0-9ivxlc]+(?: [0-9ivxlc]+)*$/;

function tocScore(label: string, heading: string): number {
  if (label === heading) return 3;
  const [shorter, longer] = label.length < heading.length ? [label, heading] : [heading, label];
  if (longer.endsWith(` ${shorter}`) && NUMBERING.test(longer.slice(0, -shorter.length - 1))) return 3;
  // a single word like "Introduction" somewhere inside a longer label is too weak to go on
  if (shorter.length < 6 || !shorter.includes(" ")) return 0;
  if (longer.endsWith(` ${shorter}`) || longer.startsWith(`${shorter} `)) return 2;
  return ` ${longer} `.includes(` ${shorter} `) ? 1 : 0;
}
