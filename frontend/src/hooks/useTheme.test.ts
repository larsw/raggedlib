import { describe, expect, test } from "bun:test";
import { nextTheme, parseTheme, THEME_META, THEMES } from "./useTheme";

describe("theme cycle", () => {
  test("cycles dark → light → sepia → dark", () => {
    expect(nextTheme("dark")).toBe("light");
    expect(nextTheme("light")).toBe("sepia");
    expect(nextTheme("sepia")).toBe("dark");
  });

  test("every theme has a distinct icon", () => {
    const icons = THEMES.map((t) => THEME_META[t].icon);
    expect(new Set(icons).size).toBe(THEMES.length);
  });

  test("parses stored values, defaulting to dark", () => {
    expect(parseTheme("light")).toBe("light");
    expect(parseTheme("sepia")).toBe("sepia");
    expect(parseTheme("dark")).toBe("dark");
    expect(parseTheme(null)).toBe("dark");
    expect(parseTheme("solarized")).toBe("dark");
  });
});
