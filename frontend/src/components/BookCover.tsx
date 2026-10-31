import { Icon } from "@blueprintjs/core";
import { useState } from "react";
import { bookCoverUrl } from "../api/client";

interface Props {
  bookId: number;
  /** false = we know there is no cover; undefined = unknown, try it */
  hasCover?: boolean;
  width?: number;
  title?: string;
}

/** Book cover thumbnail with a neutral placeholder when missing or failing to load. */
export function BookCover({ bookId, hasCover, width = 36, title }: Props) {
  const [failed, setFailed] = useState(false);
  const height = Math.round(width * 1.5);
  const style = { width, height };
  if (hasCover === false || failed) {
    return (
      <div className="book-cover book-cover-placeholder" style={style} aria-hidden>
        <Icon icon="book" size={Math.max(12, Math.round(width / 2.5))} />
      </div>
    );
  }
  return (
    <img
      className="book-cover"
      style={style}
      src={bookCoverUrl(bookId)}
      alt={title ? `Cover of ${title}` : ""}
      loading="lazy"
      decoding="async"
      onError={() => setFailed(true)}
    />
  );
}
