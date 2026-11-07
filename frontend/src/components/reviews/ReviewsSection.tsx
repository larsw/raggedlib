import { Button, Classes, Intent, Spinner, Tag, TextArea } from "@blueprintjs/core";
import { useEffect, useState } from "react";
import { api } from "../../api/client";
import type { BookReviews, Review } from "../../api/types";
import type { AsyncState } from "../../hooks/useAsync";
import { formatDate, formatNumber, pluralize } from "../../lib/format";
import { canSaveReview, formatRating } from "../../lib/reviews";
import { ConfirmButton } from "../ConfirmButton";
import { ErrorCallout } from "../ErrorState";
import { toastError, toastSuccess } from "../toaster";
import { StarRating, Stars } from "./Stars";

const MAX_BODY = 20_000;

/** Book details: the average rating, the caller's own review (written and changed here), and everyone else's. */
export function ReviewsSection({ bookId, reviews }: { bookId: number; reviews: AsyncState<BookReviews> }) {
  const r = reviews.data?.bookId === bookId ? reviews.data : undefined;
  const [editing, setEditing] = useState<{ rating: number | null } | null>(null);
  useEffect(() => setEditing(null), [bookId]);

  const removeOther = async (review: Review) => {
    try {
      await api.deleteReview(review.id);
      toastSuccess(`Removed ${review.author}'s review`);
      reviews.reload();
    } catch (err) {
      toastError("Couldn't remove the review", err);
    }
  };

  const others = r?.reviews.filter((x) => !x.mine) ?? [];
  return (
    <section className="book-panel-section" aria-label="Reviews">
      <h4 className="book-panel-subheading">
        Reviews {r && r.count > 0 && <span className={Classes.TEXT_MUTED}>· {formatNumber(r.count)}</span>}
      </h4>
      {reviews.error && !r ? (
        <ErrorCallout error={reviews.error} title="Couldn't load the reviews" />
      ) : !r ? (
        <Spinner size={16} />
      ) : (
        <>
          {r.averageRating != null && (
            <div className="review-average">
              <Stars value={r.averageRating} />
              <strong>{formatRating(r.averageRating)}</strong>
              <span className={Classes.TEXT_MUTED}>from {pluralize(r.ratings, "rating")}</span>
            </div>
          )}

          {editing ? (
            <ReviewEditor
              bookId={bookId}
              mine={r.mine}
              initialRating={editing.rating}
              onCancel={() => setEditing(null)}
              onSaved={() => {
                setEditing(null);
                reviews.reload();
              }}
            />
          ) : r.mine ? (
            <ReviewItem
              review={r.mine}
              actions={
                <>
                  <Button size="small" variant="minimal" icon="edit" text="Edit" onClick={() => setEditing({ rating: r.mine!.rating })} />
                  <ConfirmButton
                    confirmText="Delete"
                    aria-label="Delete your review"
                    onConfirm={async () => {
                      try {
                        await api.deleteMyReview(bookId);
                        toastSuccess("Your review is deleted");
                        reviews.reload();
                      } catch (err) {
                        toastError("Couldn't delete your review", err);
                      }
                    }}
                  />
                </>
              }
            />
          ) : (
            <div className="review-prompt">
              <span className={Classes.TEXT_MUTED}>Your rating</span>
              <StarRating value={null} onChange={(rating) => setEditing({ rating })} />
              <Button size="small" variant="minimal" icon="edit" text="Write a review" onClick={() => setEditing({ rating: null })} />
            </div>
          )}

          {others.length > 0 && (
            <ul className="review-list">
              {others.map((x) => (
                <li key={x.id}>
                  <ReviewItem
                    review={x}
                    actions={
                      r.canModerate && (
                        <ConfirmButton confirmText="Remove" aria-label={`Remove ${x.author}'s review`} onConfirm={() => removeOther(x)} />
                      )
                    }
                  />
                </li>
              ))}
            </ul>
          )}
          {r.count === 0 && !editing && <p className={`${Classes.TEXT_MUTED} review-empty`}>No reviews yet.</p>}
        </>
      )}
    </section>
  );
}

function ReviewItem({ review, actions }: { review: Review; actions?: React.ReactNode }) {
  const edited = review.updatedAt !== review.createdAt;
  return (
    <article className={`review${review.mine ? " is-mine" : ""}`}>
      <header className="review-head">
        <span className="review-author">{review.mine ? "Your review" : review.author}</span>
        {review.rating != null && <Stars value={review.rating} className="review-stars" />}
        <span className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL}`} title={edited ? `edited ${formatDate(review.updatedAt)}` : undefined}>
          {formatDate(review.createdAt)}
          {edited && " · edited"}
        </span>
        <span className="spacer" />
        {actions}
      </header>
      {review.body && <p className="review-body">{review.body}</p>}
    </article>
  );
}

function ReviewEditor({
  bookId,
  mine,
  initialRating,
  onCancel,
  onSaved,
}: {
  bookId: number;
  mine: Review | null;
  initialRating: number | null;
  onCancel: () => void;
  onSaved: () => void;
}) {
  const [rating, setRating] = useState<number | null>(initialRating);
  const [body, setBody] = useState(mine?.body ?? "");
  const [saving, setSaving] = useState(false);
  const valid = canSaveReview(rating, body);

  const save = async () => {
    setSaving(true);
    try {
      await api.writeReview(bookId, { rating, body: body.trim() });
      toastSuccess(mine ? "Your review is updated" : "Thanks for the review");
      onSaved();
    } catch (err) {
      toastError("Couldn't save your review", err);
      setSaving(false);
    }
  };

  return (
    <form
      className="review-editor"
      onSubmit={(e) => {
        e.preventDefault();
        if (valid) void save();
      }}
    >
      <div className="review-editor-rating">
        <StarRating value={rating} onChange={setRating} disabled={saving} />
        {rating == null && <span className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL}`}>no rating</span>}
      </div>
      <TextArea
        fill
        autoResize
        autoFocus={initialRating == null}
        maxLength={MAX_BODY}
        placeholder="What did you think of it? (optional with a rating)"
        aria-label="Review"
        value={body}
        disabled={saving}
        onChange={(e) => setBody(e.currentTarget.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter" && (e.metaKey || e.ctrlKey) && valid) {
            e.preventDefault();
            void save();
          }
        }}
      />
      <div className="review-editor-actions">
        {!valid && (
          <Tag minimal className={Classes.TEXT_MUTED}>
            a rating, a text, or both
          </Tag>
        )}
        <span className="spacer" />
        <Button size="small" variant="minimal" text="Cancel" onClick={onCancel} disabled={saving} />
        <Button size="small" type="submit" intent={Intent.PRIMARY} icon="tick" text="Save" loading={saving} disabled={!valid} />
      </div>
    </form>
  );
}
