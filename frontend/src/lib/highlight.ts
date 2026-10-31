const STOPWORDS = new Set(
  "a an and are as at be by can do does for from how i in is it of on or the to what when where which who why with vs".split(" "),
);

/** Extracts distinct search terms from a free-text query (quoted phrases kept together). */
export function queryTerms(query: string): string[] {
  const terms: string[] = [];
  const re = /"([^"]+)"|(\S+)/g;
  for (const m of query.matchAll(re)) {
    const raw = (m[1] ?? m[2] ?? "").trim();
    const term = m[1] ? raw : raw.replace(/^[^\p{L}\p{N}]+|[^\p{L}\p{N}]+$/gu, "");
    if (term.length < 2 || STOPWORDS.has(term.toLowerCase())) continue;
    if (!terms.some((t) => t.toLowerCase() === term.toLowerCase())) terms.push(term);
  }
  // longest first so "buffer overflow" wins over "buffer"
  return terms.sort((a, b) => b.length - a.length);
}

const escapeRe = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

export type HighlightPart = { text: string; match: boolean };

/** Splits text into matching / non-matching parts for the given terms (case-insensitive). */
export function highlightParts(text: string, terms: string[]): HighlightPart[] {
  if (terms.length === 0 || !text) return [{ text, match: false }];
  const re = new RegExp(terms.map(escapeRe).join("|"), "giu");
  const parts: HighlightPart[] = [];
  let last = 0;
  for (const m of text.matchAll(re)) {
    if (m[0].length === 0) continue;
    if (m.index! > last) parts.push({ text: text.slice(last, m.index), match: false });
    parts.push({ text: m[0], match: true });
    last = m.index! + m[0].length;
  }
  if (last < text.length) parts.push({ text: text.slice(last), match: false });
  return parts;
}
