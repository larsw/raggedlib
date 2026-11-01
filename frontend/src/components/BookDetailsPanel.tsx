import { AnchorButton, Button, ButtonGroup, Classes, NonIdealState, Spinner, Tag, Tooltip } from "@blueprintjs/core";
import { useState } from "react";
import { api, bookFileUrl } from "../api/client";
import type { Book, BookDetails } from "../api/types";
import { useAsync } from "../hooks/useAsync";
import { formatBytes, formatDate, formatDateTime, formatNumber, formatYear } from "../lib/format";
import { BookCover } from "./BookCover";
import { ErrorState } from "./ErrorState";
import { isBusyStatus, StatusTag } from "./StatusTag";

const SECTIONS_SHOWN = 12;

interface Props {
  bookId: number;
  /** changes whenever the library list reloads, so status and counts stay current */
  refreshKey: number;
  reindexing: boolean;
  onClose: () => void;
  onSelectBook: (id: number) => void;
  onFilterTag: (tag: string) => void;
  onAsk: (book: Book) => void;
  onSearchIn: (book: Book) => void;
  onReindex: (book: Book) => void;
}

/** Library's right-hand panel: one book's Calibre metadata, indexing state and indexed outline. */
export function BookDetailsPanel(props: Props) {
  const { bookId, refreshKey, onClose } = props;
  const details = useAsync((signal) => api.bookDetails(bookId, signal), [bookId, refreshKey]);
  // keep showing the previous book while the next one loads, but not after an error
  const d = details.data?.book.id === bookId || details.loading ? details.data : undefined;

  return (
    <aside className="book-panel" aria-label="Book details">
      <div className="book-panel-header">
        <span className={`${Classes.HEADING} book-panel-heading`}>Book details</span>
        {details.loading && d && <Spinner size={14} />}
        <span className="spacer" />
        <Tooltip content="Close (Esc)" placement="bottom-end">
          <Button variant="minimal" icon="cross" aria-label="Close book details" onClick={onClose} />
        </Tooltip>
      </div>
      <div className={`book-panel-body${details.loading && d ? " is-stale" : ""}`}>
        {details.error && !d ? (
          <ErrorState error={details.error} onRetry={details.reload} title="Couldn't load the book" />
        ) : !d ? (
          <NonIdealState icon={<Spinner size={24} />} />
        ) : (
          <DetailsContent details={d} {...props} />
        )}
      </div>
    </aside>
  );
}

function DetailsContent({ details, reindexing, onSelectBook, onFilterTag, onAsk, onSearchIn, onReindex }: Props & { details: BookDetails }) {
  const { book, calibre, duplicateOf, sections } = details;
  const year = formatYear(book.pubdate);
  const isPdf = book.sourceFormat === "PDF";

  return (
    <>
      <div className="book-panel-top">
        <BookCover bookId={book.id} hasCover={book.hasCover} width={96} title={book.title} natural />
        <div className="book-panel-ident">
          <h3 className="book-panel-title">{book.title}</h3>
          {book.authors.length > 0 && <div className="book-panel-authors">{book.authors.join(", ")}</div>}
          {book.series && (
            <div className={Classes.TEXT_MUTED}>
              {book.series}
              {book.seriesIndex != null && ` #${book.seriesIndex}`}
            </div>
          )}
          {calibre?.rating != null && (
            <div className="book-rating" aria-label={`Rated ${calibre.rating} of 5`} title={`${calibre.rating} / 5`}>
              {"★".repeat(Math.round(calibre.rating))}
              <span className="book-rating-empty">{"★".repeat(5 - Math.round(calibre.rating))}</span>
            </div>
          )}
        </div>
      </div>

      <ButtonGroup className="book-panel-actions" fill>
        <Button icon="chat" text="Ask" onClick={() => onAsk(book)} />
        <Button icon="search" text="Search in" onClick={() => onSearchIn(book)} />
        <Button
          icon="repeat"
          text="Reindex"
          loading={reindexing}
          disabled={isBusyStatus(book.status)}
          onClick={() => onReindex(book)}
        />
      </ButtonGroup>

      {calibre?.description && <Description text={calibre.description} />}

      <dl className="book-facts">
        <Fact label="Published">{year || "—"}</Fact>
        {calibre?.publisher && <Fact label="Publisher">{calibre.publisher}</Fact>}
        {book.languages.length > 0 && <Fact label="Language">{book.languages.join(", ")}</Fact>}
        {calibre?.addedAt && <Fact label="Added">{formatDate(calibre.addedAt)}</Fact>}
        {calibre &&
          Object.entries(calibre.identifiers).map(([k, v]) => (
            <Fact key={k} label={k.toUpperCase()}>
              <span className="book-identifier">{v}</span>
            </Fact>
          ))}
        <Fact label="Calibre id">{book.id}</Fact>
      </dl>

      {book.tags.length > 0 && (
        <section className="book-panel-section">
          <h4 className="book-panel-subheading">Tags</h4>
          <div className="book-panel-tags">
            {book.tags.map((t) => (
              <Tooltip key={t} content="Show books with this tag" placement="top">
                <Tag minimal interactive onClick={() => onFilterTag(t)}>
                  {t}
                </Tag>
              </Tooltip>
            ))}
          </div>
        </section>
      )}

      <section className="book-panel-section">
        <h4 className="book-panel-subheading">Index</h4>
        <dl className="book-facts">
          <Fact label="Status">
            <StatusTag status={book.status} error={book.error} />
          </Fact>
          {duplicateOf && (
            <Fact label="Same text as">
              <button type="button" className="link-button" onClick={() => onSelectBook(duplicateOf.id)}>
                {duplicateOf.title}
              </button>
            </Fact>
          )}
          {book.error && book.status !== "DUPLICATE" && <Fact label="Error">{book.error}</Fact>}
          <Fact label="Passages">{book.chunkCount ? formatNumber(book.chunkCount) : "—"}</Fact>
          <Fact label="Indexed">{formatDateTime(book.indexedAt)}</Fact>
          <Fact label="Indexed from">{book.sourceFormat ?? "—"}</Fact>
        </dl>
      </section>

      {book.formats.length > 0 && (
        <section className="book-panel-section">
          <h4 className="book-panel-subheading">Files</h4>
          <ul className="book-files">
            {book.formats.map((f) => (
              <li key={f}>
                <AnchorButton
                  variant="minimal"
                  size="small"
                  icon="document-open"
                  text={f}
                  href={bookFileUrl(book.id, f)}
                  target="_blank"
                  rel="noopener"
                  aria-label={`Open ${f} file`}
                />
                <span className={Classes.TEXT_MUTED}>{formatBytes(calibre?.fileSizes[f])}</span>
              </li>
            ))}
          </ul>
        </section>
      )}

      {sections.length > 0 && <Outline bookId={book.id} sections={sections} linkPages={isPdf} />}
    </>
  );
}

function Fact({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <>
      <dt>{label}</dt>
      <dd>{children}</dd>
    </>
  );
}

function Description({ text }: { text: string }) {
  const [open, setOpen] = useState(false);
  const long = text.length > 420;
  return (
    <section className="book-panel-section">
      <p className={`book-description${long && !open ? " is-clamped" : ""}`}>{text}</p>
      {long && (
        <Button variant="minimal" size="small" text={open ? "Less" : "More"} onClick={() => setOpen((o) => !o)} />
      )}
    </section>
  );
}

function Outline({ bookId, sections, linkPages }: { bookId: number; sections: BookDetails["sections"]; linkPages: boolean }) {
  const [all, setAll] = useState(false);
  const shown = all ? sections : sections.slice(0, SECTIONS_SHOWN);
  return (
    <section className="book-panel-section">
      <h4 className="book-panel-subheading">
        Indexed sections <span className={Classes.TEXT_MUTED}>· {formatNumber(sections.length)}</span>
      </h4>
      <ol className="book-outline">
        {shown.map((s) => (
          <li key={s.title}>
            <span className="book-outline-title" title={s.title}>
              {s.title}
            </span>
            {s.firstPage != null &&
              (linkPages ? (
                <a href={bookFileUrl(bookId, "PDF", s.firstPage)} target="_blank" rel="noopener" className="book-outline-page">
                  p. {s.firstPage}
                </a>
              ) : (
                <span className={`${Classes.TEXT_MUTED} book-outline-page`}>p. {s.firstPage}</span>
              ))}
          </li>
        ))}
      </ol>
      {sections.length > SECTIONS_SHOWN && (
        <Button
          variant="minimal"
          size="small"
          text={all ? "Show fewer" : `Show all ${formatNumber(sections.length)}`}
          onClick={() => setAll((a) => !a)}
        />
      )}
    </section>
  );
}
