import { useState } from "react";
import { formatRating, nextRating, starFill } from "../../lib/reviews";

const FIVE = "★★★★★";

/** A read-only 0–5 star bar; partial stars for averages. */
export function Stars({ value, className }: { value: number | null | undefined; className?: string }) {
  const label = value == null ? "Not rated" : `Rated ${formatRating(value)} of 5`;
  return (
    <span className={`stars${className ? ` ${className}` : ""}`} role="img" aria-label={label} title={label}>
      <span className="stars-empty" aria-hidden>
        {FIVE}
      </span>
      <span className="stars-fill" style={{ width: `${starFill(value)}%` }} aria-hidden>
        {FIVE}
      </span>
    </span>
  );
}

/** 1–5 star input; clicking the current star clears it (null: no rating). */
export function StarRating({
  value,
  onChange,
  disabled,
  label = "Your rating",
}: {
  value: number | null;
  onChange: (rating: number | null) => void;
  disabled?: boolean;
  label?: string;
}) {
  const [hover, setHover] = useState<number | null>(null);
  const shown = hover ?? value ?? 0;
  return (
    <span className="star-rating" role="radiogroup" aria-label={label} onMouseLeave={() => setHover(null)}>
      {[1, 2, 3, 4, 5].map((n) => (
        <button
          key={n}
          type="button"
          role="radio"
          aria-checked={value === n}
          aria-label={value === n ? `${n} of 5 stars (click to clear)` : `${n} of 5 stars`}
          title={value === n ? "Click again to clear the rating" : `${n} of 5`}
          className={`star-rating-star${n <= shown ? " is-on" : ""}`}
          disabled={disabled}
          onMouseEnter={() => setHover(n)}
          onFocus={() => setHover(n)}
          onBlur={() => setHover(null)}
          onClick={() => onChange(nextRating(value, n))}
        >
          ★
        </button>
      ))}
    </span>
  );
}
