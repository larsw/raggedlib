/** A piece of answer text: plain text or a group of citation refs, e.g. "[2][5]" -> [2, 5]. */
export type CitationSegment = { kind: "text"; text: string } | { kind: "cite"; refs: number[]; raw: string };

// [3]   [2, 5]   [2,5]   [1-3]  — consecutive groups like [2][5] are matched one by one and merged.
const CITE_RE = /\[(\d{1,3}(?:\s*[,–-]\s*\d{1,3})*)\]/g;

function expand(inner: string): number[] {
  const refs: number[] = [];
  for (const part of inner.split(",")) {
    const range = part.split(/[–-]/).map((p) => Number.parseInt(p.trim(), 10));
    if (range.length === 2 && range[0]! <= range[1]! && range[1]! - range[0]! <= 20) {
      for (let r = range[0]!; r <= range[1]!; r++) refs.push(r);
    } else {
      for (const r of range) refs.push(r);
    }
  }
  return refs;
}

/**
 * Splits text into plain and citation segments. Only refs accepted by `isValid` become citations;
 * anything else (e.g. array indices like "[0]" in prose) stays text.
 */
export function splitCitations(text: string, isValid: (ref: number) => boolean = () => true): CitationSegment[] {
  const out: CitationSegment[] = [];
  let last = 0;
  const pushText = (t: string) => {
    if (!t) return;
    const prev = out[out.length - 1];
    if (prev?.kind === "text") prev.text += t;
    else out.push({ kind: "text", text: t });
  };
  for (const m of text.matchAll(CITE_RE)) {
    const refs = expand(m[1]!);
    if (refs.length === 0 || !refs.every(isValid)) continue;
    const start = m.index!;
    pushText(text.slice(last, start));
    const prev = out[out.length - 1];
    if (prev?.kind === "cite" && last === start) {
      // adjacent group: "[2][5]"
      for (const r of refs) if (!prev.refs.includes(r)) prev.refs.push(r);
      prev.raw += m[0];
    } else {
      out.push({ kind: "cite", refs: [...new Set(refs)], raw: m[0] });
    }
    last = start + m[0].length;
  }
  pushText(text.slice(last));
  return out;
}

/** All refs cited in a text, in order of first appearance. */
export function citedRefs(text: string, isValid?: (ref: number) => boolean): number[] {
  const seen = new Set<number>();
  for (const seg of splitCitations(text, isValid)) if (seg.kind === "cite") for (const r of seg.refs) seen.add(r);
  return [...seen];
}
