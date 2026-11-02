import { describe, expect, test } from "bun:test";
import {
  findWords,
  matchWords,
  normalizeSegments,
  passageProbes,
  readerFileName,
  readerFormat,
  tocMatches,
} from "./reader";

describe("readerFormat", () => {
  test("prefers the indexed format when the reader can open it", () => {
    expect(readerFormat(["PDF", "AZW3", "EPUB"], "AZW3")).toBe("AZW3");
  });
  test("falls back to the preferred reader format", () => {
    expect(readerFormat(["PDF", "MOBI", "EPUB"], "PDF")).toBe("EPUB");
    expect(readerFormat(["FB2", "CBZ"], null)).toBe("FB2");
  });
  test("null when only formats the reader can't open", () => {
    expect(readerFormat(["PDF", "DJVU"], "PDF")).toBeNull();
    expect(readerFormat([], null)).toBeNull();
  });
  test("file names carry the extension foliate-js sniffs", () => {
    expect(readerFileName("CBZ")).toBe("book.cbz");
  });
});

describe("normalizeSegments", () => {
  test("folds case, accents, ligatures, quotes and punctuation into single spaces", () => {
    expect(normalizeSegments(["“Café” — the ﬁnal  word,\n don’t"]).text).toBe("cafe the final word don t");
  });

  test("soft hyphens and zero-width spaces don't split words", () => {
    expect(normalizeSegments(["hy­phen​ated"]).text).toBe("hyphenated");
  });

  test("leftover markdown emphasis and underscores don't split words", () => {
    expect(matchWords("Prog*ramming Languages*")).toEqual(["programming", "languages"]);
    expect(normalizeSegments(["Prog", "ramming Languages"]).text).toBe(matchWords("Prog*ramming Languages*").join(" "));
    expect(matchWords("call snake_case() *now*")).toEqual(["call", "snakecase", "now"]);
  });

  test("inline formatting split across text nodes joins up; block breaks separate words", () => {
    expect(normalizeSegments(["wo", "rd"]).text).toBe("word");
    expect(normalizeSegments(["end.", "Next"], [false, true]).text).toBe("end next");
    expect(normalizeSegments(["end", "Next"], [false, true]).text).toBe("end next");
  });

  test("maps every character back to its segment and offsets", () => {
    const m = normalizeSegments(["  Ab", "c, déf"]);
    expect(m.text).toBe("abc def");
    const at = (i: number) => [m.seg[i], m.start[i], m.end[i]];
    expect(at(0)).toEqual([0, 2, 3]); // "A"
    expect(at(2)).toEqual([1, 0, 1]); // "c"
    expect(at(4)).toEqual([1, 3, 4]); // "d"
    expect(at(5)).toEqual([1, 4, 5]); // "é" (one code unit, folded to "e")
  });

  test("no leading or trailing space", () => {
    expect(normalizeSegments(["  — hi —  "]).text).toBe("hi");
  });
});

describe("findWords", () => {
  test("only at word boundaries", () => {
    expect(findWords("concatenate the cat", "cat")).toBe(16);
    expect(findWords("a cat sat", "cat sat")).toBe(2);
    expect(findWords("cats", "cat")).toBe(-1);
  });
});

describe("passageProbes", () => {
  const words = (n: number) => Array.from({ length: n }, (_, i) => `w${i}`).join(" ");

  test("long passages give head runs from the start and tail runs from the end", () => {
    const { heads, tails } = passageProbes(words(40), 8, 3);
    expect(heads[0]).toBe("w0 w1 w2 w3 w4 w5 w6 w7");
    expect(heads[1]).toBe("w8 w9 w10 w11 w12 w13 w14 w15");
    expect(tails[0]).toBe("w32 w33 w34 w35 w36 w37 w38 w39");
    expect(heads).toHaveLength(3);
    expect(tails).toHaveLength(3);
  });

  test("short passages are looked for whole, then by halves", () => {
    expect(passageProbes("Just a few words here.")).toEqual({ heads: ["just a few words here"], tails: [] });
    const { heads, tails } = passageProbes(words(12), 8);
    expect(heads).toEqual([words(12), "w0 w1 w2 w3 w4 w5 w6 w7", "w4 w5 w6 w7 w8 w9 w10 w11"]);
    expect(tails).toEqual([]);
  });

  test("empty text", () => {
    expect(passageProbes("  — ")).toEqual({ heads: [], tails: [] });
  });
});

describe("tocMatches", () => {
  const toc = [
    { label: "Cover", href: "cover.xhtml" },
    {
      label: "Chapter 3: Threat Models",
      href: "ch03.xhtml",
      subitems: [
        { label: "Attack trees in practice", href: "ch03.xhtml#trees" },
        { label: "Introduction", href: "ch03.xhtml#intro" },
      ],
    },
    { label: "1. Introduction", href: "ch01.xhtml" },
  ];

  test("exact labels first, numbering ignored", () => {
    expect(tocMatches(toc, "Introduction")).toEqual(["ch03.xhtml#intro", "ch01.xhtml"]);
    expect(tocMatches(toc, "Threat models")).toEqual(["ch03.xhtml"]);
  });

  test("partial matches of several words rank below exact ones", () => {
    expect(tocMatches(toc, "Attack trees")).toEqual(["ch03.xhtml#trees"]);
  });

  test("single words never match inside longer labels", () => {
    expect(tocMatches(toc, "Practice")).toEqual([]);
  });

  test("no heading or no toc", () => {
    expect(tocMatches(toc, null)).toEqual([]);
    expect(tocMatches(undefined, "Introduction")).toEqual([]);
  });

  test("matchWords", () => {
    expect(matchWords("Chapter 3: Threat Models")).toEqual(["chapter", "3", "threat", "models"]);
  });
});
