/** Clicking the star a rating already has clears it (a review may have text only); any other star sets it. */
export function nextRating(current: number | null, clicked: number): number | null {
  return current === clicked ? null : clicked;
}

/** A review needs a rating, a text, or both. */
export function canSaveReview(rating: number | null, body: string): boolean {
  return (rating != null && rating >= 1 && rating <= 5) || body.trim().length > 0;
}

/** How much of a 5-star bar to fill for a rating (0–100, in %), clamped. */
export function starFill(rating: number | null | undefined): number {
  if (rating == null || !Number.isFinite(rating)) return 0;
  return Math.round((Math.min(5, Math.max(0, rating)) / 5) * 1000) / 10;
}

/** "4.5" or "4": one decimal, none when whole. */
export function formatRating(rating: number): string {
  return Number.isInteger(rating) ? String(rating) : rating.toFixed(1);
}
