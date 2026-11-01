import { AnchorButton, Button, Card, Classes, Tooltip } from "@blueprintjs/core";
import { useEffect, useState } from "react";
import { bookFileUrl } from "../api/client";
import type { SourceChunk } from "../api/types";
import { useApp } from "../AppContext";
import { formatAuthors, formatLocation } from "../lib/format";
import { BookCover } from "./BookCover";
import { HighlightedText } from "./HighlightedText";

interface Props {
  source: SourceChunk;
  /** DOM id so citation clicks can scroll here */
  id?: string;
  highlighted?: boolean;
  /** dimmed = retrieved but not cited in the answer */
  dimmed?: boolean;
  terms?: string[];
  defaultExpanded?: boolean;
  showScore?: boolean;
}

const CLAMP_CHARS = 320;

export function SourceCard({ source, id, highlighted, dimmed, terms, defaultExpanded, showScore }: Props) {
  const { scope, addBookToScope } = useApp();
  const [expanded, setExpanded] = useState(!!defaultExpanded);
  // Jumping here from a citation expands the passage (user can still collapse it).
  useEffect(() => {
    if (highlighted) setExpanded(true);
  }, [highlighted]);
  const isExpanded = expanded;
  const loc = formatLocation(source.page, source.section);
  const inScope = scope.books.some((b) => b.id === source.bookId);
  const long = source.text.length > CLAMP_CHARS;

  return (
    <Card
      id={id}
      compact
      className={`source-card${highlighted ? " is-highlighted" : ""}${dimmed ? " is-dimmed" : ""}`}
    >
      <div className="source-card-body">
        <BookCover bookId={source.bookId} width={40} title={source.title} />
        <div className="source-card-main">
          <div className="source-card-head">
            <span className="ref-badge" aria-label={`Source ${source.ref}`}>
              {source.ref}
            </span>
            <div className="source-card-titles">
              <div className="source-title" title={source.title}>
                {source.title}
              </div>
              <div className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL} source-meta`}>
                <span className={Classes.TEXT_OVERFLOW_ELLIPSIS}>{formatAuthors(source.authors)}</span>
                {loc && <span className="source-loc" title={loc}>{loc}</span>}
                {showScore && (
                  <span
                    className="source-score"
                    title={source.scoreKind === "RERANK" ? "Reranker relevance" : "Fused rank score (relative to this query)"}
                  >
                    {source.scoreKind === "RERANK" ? `relevance ${Math.round(source.score * 100)}%` : `score ${source.score.toFixed(3)}`}
                  </span>
                )}
              </div>
            </div>
          </div>
          <div className={`passage-text${isExpanded || !long ? "" : " is-clamped"}`}>
            {terms?.length ? <HighlightedText text={source.text} terms={terms} /> : source.text}
          </div>
          <div className="source-card-actions">
            {long && (
              <Button
                size="small"
                variant="minimal"
                icon={isExpanded ? "chevron-up" : "chevron-down"}
                text={isExpanded ? "Less" : "More"}
                onClick={() => setExpanded(!isExpanded)}
              />
            )}
            {source.page != null && (
              <AnchorButton
                size="small"
                variant="minimal"
                icon="document-open"
                text={`Open p. ${source.page}`}
                href={bookFileUrl(source.bookId, "PDF", source.page)}
                target="_blank"
                rel="noreferrer"
              />
            )}
            <Tooltip content={inScope ? "Already in scope" : "Restrict retrieval to this book"} placement="top">
              <Button
                size="small"
                variant="minimal"
                icon="filter"
                text="Limit to book"
                disabled={inScope}
                onClick={() => addBookToScope({ id: source.bookId, title: source.title, authors: source.authors })}
              />
            </Tooltip>
          </div>
        </div>
      </div>
    </Card>
  );
}
