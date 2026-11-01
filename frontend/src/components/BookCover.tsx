import { Icon } from "@blueprintjs/core";
import { useState } from "react";
import { bookCoverUrl } from "../api/client";

interface Props {
  bookId: number;
  /** false = we know there is no cover; undefined = unknown, try it */
  hasCover?: boolean;
  width?: number;
  title?: string;
  /** keep the image's own aspect ratio (detail views) instead of cropping it to 2:3 (thumbnails in lists) */
  natural?: boolean;
}

/** Book cover thumbnail with a neutral placeholder when missing or failing to load. */
export function BookCover({ bookId, hasCover, width = 36, title, natural }: Props) {
  const [failed, setFailed] = useState(false);
  const height = Math.round(width * 1.5);
  const style = { width, height };
  const imgStyle: React.CSSProperties = natural ? { width, height: "auto", maxHeight: Math.round(width * 1.8), objectFit: "contain" } : style;
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
      style={imgStyle}
      src={bookCoverUrl(bookId)}
      alt={title ? `Cover of ${title}` : ""}
      loading="lazy"
      decoding="async"
      onError={() => setFailed(true)}
    />
  );
}
