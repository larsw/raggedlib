import { describe, expect, test } from "bun:test";
import { clampPanelWidth, MAIN_MIN_WIDTH, PANEL_MIN_WIDTH } from "./useSidePanel";

describe("clampPanelWidth", () => {
  test("keeps the panel at least its minimum", () => {
    expect(clampPanelWidth(100, 1400)).toBe(PANEL_MIN_WIDTH);
  });
  test("leaves the main column its minimum", () => {
    expect(clampPanelWidth(5000, 1400)).toBe(1400 - MAIN_MIN_WIDTH);
  });
  test("a container too narrow for both still gets the minimum panel", () => {
    expect(clampPanelWidth(500, 500)).toBe(PANEL_MIN_WIDTH);
  });
  test("unknown container width: only the minimum applies; widths are whole pixels", () => {
    expect(clampPanelWidth(612.6, null)).toBe(613);
  });
});
