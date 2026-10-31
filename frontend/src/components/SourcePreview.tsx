import { Classes } from "@blueprintjs/core";
import type { SourceChunk } from "../api/types";
import { formatAuthors, formatLocation } from "../lib/format";

const MAX_CHARS = 900;

/** Compact passage view used in citation popovers. */
export function SourcePreview({ source }: { source: SourceChunk }) {
  const loc = formatLocation(source.page, source.section);
  const text = source.text.length > MAX_CHARS ? `${source.text.slice(0, MAX_CHARS).trimEnd()}…` : source.text;
  return (
    <div className="source-preview">
      <div className="source-preview-head">
        <span className="ref-badge">{source.ref}</span>
        <div className="source-preview-titles">
          <div className="source-title">{source.title}</div>
          <div className={Classes.TEXT_MUTED}>
            {formatAuthors(source.authors)}
            {loc && <> · {loc}</>}
          </div>
        </div>
      </div>
      <div className="passage-text">{text}</div>
    </div>
  );
}
