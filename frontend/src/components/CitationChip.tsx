import { PopoverNext } from "@blueprintjs/core";
import type { SourceChunk } from "../api/types";
import { SourcePreview } from "./SourcePreview";

interface Props {
  source: SourceChunk;
  onClick?: (ref: number) => void;
}

/** Small inline [n] chip; hover shows the passage, click jumps to it in the sources panel. */
export function CitationChip({ source, onClick }: Props) {
  return (
    <PopoverNext
      content={<SourcePreview source={source} />}
      interactionKind="hover"
      hoverOpenDelay={200}
      hoverCloseDelay={120}
      placement="top"
      popoverClassName="citation-popover"
    >
      <button
        type="button"
        className="citation-chip"
        aria-label={`Source ${source.ref}: ${source.title}`}
        onClick={() => onClick?.(source.ref)}
      >
        {source.ref}
      </button>
    </PopoverNext>
  );
}
