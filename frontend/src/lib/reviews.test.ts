import { describe, expect, test } from "bun:test";
import { canSaveReview, formatRating, nextRating, starFill } from "./reviews";

describe("reviews", () => {
  test("clicking the current star clears the rating", () => {
    expect(nextRating(null, 3)).toBe(3);
    expect(nextRating(3, 4)).toBe(4);
    expect(nextRating(4, 4)).toBeNull();
  });

  test("a rating, a text, or both", () => {
    expect(canSaveReview(null, "")).toBe(false);
    expect(canSaveReview(null, "   ")).toBe(false);
    expect(canSaveReview(5, "")).toBe(true);
    expect(canSaveReview(null, "good")).toBe(true);
    expect(canSaveReview(0, "")).toBe(false);
    expect(canSaveReview(6, "")).toBe(false);
  });

  test("star fill", () => {
    expect(starFill(null)).toBe(0);
    expect(starFill(5)).toBe(100);
    expect(starFill(4.5)).toBe(90);
    expect(starFill(3.33)).toBe(66.6);
    expect(starFill(9)).toBe(100);
    expect(starFill(-1)).toBe(0);
  });

  test("rating format", () => {
    expect(formatRating(4)).toBe("4");
    expect(formatRating(4.5)).toBe("4.5");
    expect(formatRating(3.67)).toBe("3.7");
  });
});
