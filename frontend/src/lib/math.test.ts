import { describe, expect, test } from "bun:test";
import { normalizeMath } from "./math";

describe("normalizeMath", () => {
  test("keeps inline math", () => {
    const s = "Lie algebras satisfy $ab = -ba$, and $(a \\cdot b) \\cdot c + (b \\cdot c)a + (c \\cdot a)b = 0$.";
    expect(normalizeMath(s)).toBe(s);
    expect(normalizeMath("growth is $2^n$ here")).toBe("growth is $2^n$ here");
  });
  test("escapes currency and shell variables", () => {
    expect(normalizeMath("costs $5 and $10")).toBe("costs \\$5 and \\$10");
    expect(normalizeMath("set $HOME and $PATH")).toBe("set \\$HOME and \\$PATH");
    expect(normalizeMath("a lone $ sign")).toBe("a lone \\$ sign");
    expect(normalizeMath("price: $5.")).toBe("price: \\$5.");
  });
  test("a closing $ followed by a digit doesn't close", () => {
    expect(normalizeMath("from $x to $2 more")).toBe("from \\$x to \\$2 more");
  });
  test("leaves code alone", () => {
    expect(normalizeMath("run `echo $HOME` now")).toBe("run `echo $HOME` now");
    const block = "```sh\necho $PATH $1\n```\nthen $x$";
    expect(normalizeMath(block)).toBe(block);
  });
  test("display math and escaped dollars", () => {
    expect(normalizeMath("$$\n\\sum_i x_i\n$$")).toBe("$$\n\\sum_i x_i\n$$");
    expect(normalizeMath("literal \\$5")).toBe("literal \\$5");
  });
  test("converts \\( \\) and \\[ \\]", () => {
    expect(normalizeMath("so \\(a^2 + b^2\\) holds")).toBe("so $a^2 + b^2$ holds");
    expect(normalizeMath("\\[ e^{i\\pi} + 1 = 0 \\]")).toBe("$$\ne^{i\\pi} + 1 = 0\n$$");
    expect(normalizeMath("inline \\[x\\] too")).toBe("inline $$x$$ too");
  });
  test("math doesn't span paragraphs; text without math is unchanged", () => {
    expect(normalizeMath("$a\n\nb$")).toBe("\\$a\n\nb\\$");
    expect(normalizeMath("plain text [1].")).toBe("plain text [1].");
  });
});
