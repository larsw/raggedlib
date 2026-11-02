/**
 * Prepares model output for remark-math, outside code (fenced blocks and inline `code`):
 *  - `\(…\)` and `\[…\]`, which models often write, become `$…$` and `$$…$$`;
 *  - a single `$` only stays a math delimiter by Pandoc's rules: the opening `$` is followed by a non-space, the
 *    closing one is preceded by a non-space and not followed by a digit, both in one paragraph. Any other `$` is
 *    escaped, so "costs $5 and $10" and "$HOME and $PATH" stay text in a library of technical books.
 */
export function normalizeMath(text: string): string {
  if (!text.includes("$") && !text.includes("\\(") && !text.includes("\\[")) return text;
  return splitCode(text)
    .map((seg) => (seg.code ? seg.text : dollars(brackets(seg.text))))
    .join("");
}

interface Segment {
  text: string;
  code: boolean;
}

const FENCE = /^ {0,3}(`{3,}|~{3,})/;

/** Fenced code blocks and inline code spans (left untouched), and everything else. */
function splitCode(text: string): Segment[] {
  const out: Segment[] = [];
  const lines = text.split(/(?<=\n)/);
  let prose = "";
  let fence: string | null = null;
  let block = "";
  const flushProse = () => {
    if (!prose) return;
    // inline code spans: a run of backticks up to the same run
    let last = 0;
    for (const m of prose.matchAll(/(`+)[\s\S]*?\1/g)) {
      if (m.index! > last) out.push({ text: prose.slice(last, m.index), code: false });
      out.push({ text: m[0], code: true });
      last = m.index! + m[0].length;
    }
    if (last < prose.length) out.push({ text: prose.slice(last), code: false });
    prose = "";
  };
  for (const line of lines) {
    const m = FENCE.exec(line);
    if (fence === null) {
      if (m) {
        flushProse();
        fence = m[1]!;
        block = line;
      } else prose += line;
    } else {
      block += line;
      if (m && m[1]!.startsWith(fence[0]!) && m[1]!.length >= fence.length) {
        out.push({ text: block, code: true });
        fence = null;
        block = "";
      }
    }
  }
  if (fence !== null) out.push({ text: block, code: true }); // unclosed fence (still streaming)
  flushProse();
  return out;
}

/** `\[…\]` on lines of its own → a `$$` block; otherwise `$$…$$` inline; `\(…\)` → `$…$`. */
function brackets(text: string): string {
  return text
    .replace(/^([ \t]*)\\\[([\s\S]+?)\\\][ \t]*$/gm, (_, indent: string, body: string) => `${indent}$$\n${body.trim()}\n${indent}$$`)
    .replace(/\\\[([\s\S]+?)\\\]/g, (_, body: string) => `$$${body.trim()}$$`)
    .replace(/\\\(([\s\S]+?)\\\)/g, (_, body: string) => `$${body.trim()}$`);
}

/** Escapes every single `$` that doesn't open or close inline math by Pandoc's rules. */
function dollars(text: string): string {
  let out = "";
  let i = 0;
  while (i < text.length) {
    const c = text[i]!;
    if (c === "\\" && i + 1 < text.length) {
      out += c + text[i + 1];
      i += 2;
      continue;
    }
    if (c !== "$") {
      out += c;
      i++;
      continue;
    }
    if (text[i + 1] === "$") {
      // display math: leave $$ … $$ to remark-math
      const end = text.indexOf("$$", i + 2);
      if (end < 0) {
        out += "\\$\\$";
        i += 2;
      } else {
        out += text.slice(i, end + 2);
        i = end + 2;
      }
      continue;
    }
    const close = closingDollar(text, i);
    if (close < 0) {
      out += "\\$";
      i++;
    } else {
      out += text.slice(i, close + 1);
      i = close + 1;
    }
  }
  return out;
}

/** Index of the `$` closing inline math opened at [open], or -1. */
function closingDollar(text: string, open: number): number {
  const next = text[open + 1];
  if (next === undefined || /\s/.test(next)) return -1;
  for (let j = open + 1; j < text.length; j++) {
    const c = text[j]!;
    if (c === "\\") {
      j++;
      continue;
    }
    if (c === "\n" && text[j + 1] === "\n") return -1; // math stays within a paragraph
    if (c !== "$") continue;
    if (text[j + 1] === "$") return -1;
    const before = text[j - 1]!;
    const after = text[j + 1];
    if (!/\s/.test(before) && !(after !== undefined && /\d/.test(after))) return j;
  }
  return -1;
}
