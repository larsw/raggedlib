import { describe, expect, test } from "bun:test";
import { citedRefs, splitCitations } from "./citations";
import { formatBytes, formatDuration } from "./format";
import { highlightParts, queryTerms } from "./highlight";

describe("splitCitations", () => {
  test("single and adjacent citations", () => {
    expect(splitCitations("XSS is bad [3]. See [2][5].")).toEqual([
      { kind: "text", text: "XSS is bad " },
      { kind: "cite", refs: [3], raw: "[3]" },
      { kind: "text", text: ". See " },
      { kind: "cite", refs: [2, 5], raw: "[2][5]" },
      { kind: "text", text: "." },
    ]);
  });

  test("comma lists and ranges", () => {
    expect(citedRefs("a [1, 4] b [6-8] c [2,2]")).toEqual([1, 4, 6, 7, 8, 2]);
  });

  test("invalid refs stay text", () => {
    const valid = (r: number) => r >= 1 && r <= 3;
    expect(splitCitations("arr[0] and [9] but [2]", valid)).toEqual([
      { kind: "text", text: "arr[0] and [9] but " },
      { kind: "cite", refs: [2], raw: "[2]" },
    ]);
  });

  test("text without citations", () => {
    expect(splitCitations("plain")).toEqual([{ kind: "text", text: "plain" }]);
    expect(splitCitations("")).toEqual([]);
  });
});

describe("highlight", () => {
  test("queryTerms drops stopwords, keeps phrases, longest first", () => {
    expect(queryTerms('how does "return oriented" ROP work?')).toEqual(["return oriented", "work", "ROP"]);
  });

  test("highlightParts is case-insensitive and escapes regex chars", () => {
    expect(highlightParts("Use C++ and c++17", ["c++"])).toEqual([
      { text: "Use ", match: false },
      { text: "C++", match: true },
      { text: " and ", match: false },
      { text: "c++", match: true },
      { text: "17", match: false },
    ]);
  });
});

describe("formatDuration", () => {
  test("formats ranges", () => {
    expect(formatDuration(null)).toBe("—");
    expect(formatDuration(42)).toBe("42s");
    expect(formatDuration(725)).toBe("12m 05s");
    expect(formatDuration(3 * 3600 + 20 * 60)).toBe("3h 20m");
    expect(formatDuration(2 * 86400 + 4 * 3600)).toBe("2d 4h");
  });
});

import { splitThinking } from "./thinking";

describe("splitThinking", () => {
  test("no think block", () => {
    expect(splitThinking("Hello [1]")).toEqual({ thinking: null, answer: "Hello [1]", thinkingDone: true });
  });
  test("open and closed think blocks", () => {
    expect(splitThinking("<think>hmm")).toEqual({ thinking: "hmm", answer: "", thinkingDone: false });
    expect(splitThinking("<think>\nhmm\n</think>\n\nAnswer")).toEqual({ thinking: "hmm", answer: "Answer", thinkingDone: true });
  });
});

describe("formatBytes", () => {
  test("picks a unit", () => {
    expect(formatBytes(null)).toBe("—");
    expect(formatBytes(512)).toBe("512 B");
    expect(formatBytes(940 * 1024)).toBe("940 KB");
    expect(formatBytes(12.4 * 1024 * 1024)).toBe("12.4 MB");
    expect(formatBytes(3 * 1024 ** 3)).toBe("3.0 GB");
  });
});
