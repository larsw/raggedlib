import type { Book } from "foliate-js/view.js";
import { findWords, matchWords, normalizeSegments, passageProbes, tocMatches, type MatchText } from "../../lib/reader";

/** A found place in the book: spine section index and a range inside that section's document. */
export interface Located {
  index: number;
  range: Range;
}

/** Block-level elements: a text node inside a different one than the previous node starts a new word. */
const BLOCK =
  "p,div,li,dd,dt,td,th,caption,h1,h2,h3,h4,h5,h6,blockquote,pre,figcaption,section,article,aside,header,footer,tr";
const HEADING = "h1,h2,h3,h4,h5,h6,[role='heading']";

interface DocText {
  nodes: Text[];
  match: MatchText;
}

function docText(doc: Document): DocText {
  const nodes: Text[] = [];
  const breaks: boolean[] = [];
  const root = doc.body ?? doc.documentElement;
  const walker = doc.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
    acceptNode: (n) =>
      n.parentElement?.closest("script,style,noscript") ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT,
  });
  let prevBlock: Element | null = null;
  for (let n = walker.nextNode(); n; n = walker.nextNode()) {
    const block = n.parentElement?.closest(BLOCK) ?? null;
    breaks.push(nodes.length > 0 && block !== prevBlock);
    prevBlock = block;
    nodes.push(n as Text);
  }
  return { nodes, match: normalizeSegments(nodes.map((n) => n.data), breaks) };
}

function toRange(doc: Document, t: DocText, from: number, to: number): Range {
  const { seg, start, end } = t.match;
  const range = doc.createRange();
  range.setStart(t.nodes[seg[from]!]!, start[from]!);
  range.setEnd(t.nodes[seg[to - 1]!]!, end[to - 1]!);
  return range;
}

/** Spine indexes in search order: those the TOC points at for `heading` first, then the rest in reading order. */
function searchOrder(book: Book, heading: string | null | undefined): { order: number[]; tocIndexes: number[] } {
  const tocIndexes: number[] = [];
  for (const href of tocMatches(book.toc, heading)) {
    try {
      const index = book.resolveHref?.(href)?.index;
      if (index != null && index >= 0 && !tocIndexes.includes(index)) tocIndexes.push(index);
    } catch {
      /* unresolvable TOC entry */
    }
  }
  // a heading's text usually continues into the following spine items (a chapter split across files)
  const preferred: number[] = [];
  for (const i of tocIndexes) for (let k = i; k < i + 3 && k < book.sections.length; k++) preferred.push(k);
  const order = [...new Set([...preferred, ...book.sections.keys()])];
  return { order, tocIndexes };
}

async function loadDoc(book: Book, index: number): Promise<Document | null> {
  try {
    return (await book.sections[index]?.createDocument?.()) ?? null;
  } catch {
    return null;
  }
}

export interface PassageResult {
  /** the passage, or null when it couldn't be found */
  found: Located | null;
  /** spine index the TOC gives for the passage's section heading, as a fallback */
  sectionIndex: number | null;
}

/**
 * Finds a passage of the index in the book: a place where its first words occur and its last words follow, looking
 * in the section its heading names first. Opening sentences can repeat (boilerplate, summaries), so a place where
 * only the start or only the end matches is used only when no complete match exists. `signal` stops the search
 * between spine items.
 */
export async function locatePassage(
  book: Book,
  passage: { text: string; section: string | null },
  signal?: AbortSignal,
): Promise<PassageResult> {
  const { heads, tails } = passageProbes(passage.text);
  const { order, tocIndexes } = searchOrder(book, passage.section);
  const sectionIndex = tocIndexes[0] ?? null;
  const maxSpan = matchWords(passage.text).join(" ").length * 2 + 200;
  let partial: Located | null = null;

  for (const index of order) {
    if (signal?.aborted) break;
    const doc = await loadDoc(book, index);
    if (!doc) continue;
    const t = docText(doc);
    const hay = t.match.text;

    for (const head of heads) {
      for (let at = findWords(hay, head); at >= 0; at = findWords(hay, head, at + 1)) {
        // a passage short enough to have no tail runs is complete with its head
        if (tails.length === 0) return { found: { index, range: toRange(doc, t, at, at + head.length) }, sectionIndex };
        for (const tail of tails) {
          const tailAt = findWords(hay, tail, at + head.length);
          if (tailAt >= 0 && tailAt + tail.length - at <= maxSpan) {
            return { found: { index, range: toRange(doc, t, at, tailAt + tail.length) }, sectionIndex };
          }
        }
        partial ??= { index, range: toRange(doc, t, at, at + head.length) };
      }
    }
    // the start differs (e.g. a list number), but the end may still be there
    if (!partial) {
      for (const tail of tails) {
        const at = findWords(hay, tail);
        if (at >= 0) {
          partial = { index, range: toRange(doc, t, at, at + tail.length) };
          break;
        }
      }
    }
  }
  return { found: partial, sectionIndex };
}

/**
 * Finds a section heading: the TOC entry naming it, or else the first heading element with its words.
 * Returns a TOC href or a located range, or null.
 */
export async function locateHeading(
  book: Book,
  title: string,
  signal?: AbortSignal,
): Promise<{ href: string } | Located | null> {
  const [href] = tocMatches(book.toc, title);
  if (href) return { href };
  const want = matchWords(title).join(" ");
  if (!want) return null;
  for (const index of book.sections.keys()) {
    if (signal?.aborted) break;
    const doc = await loadDoc(book, index);
    if (!doc) continue;
    const t = docText(doc);
    for (let at = findWords(t.match.text, want); at >= 0; at = findWords(t.match.text, want, at + 1)) {
      const range = toRange(doc, t, at, at + want.length);
      const el = range.startContainer.parentElement;
      if (el?.closest(HEADING)) return { index, range };
    }
  }
  return null;
}
