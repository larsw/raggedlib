import { Button, Classes, NonIdealState, NonIdealStateIconSize, Spinner, Tooltip } from "@blueprintjs/core";
import { useEffect, useMemo } from "react";
import type { SourceChunk } from "../api/types";
import { citedRefs } from "../lib/citations";
import { SourceCard } from "./SourceCard";

interface Props {
  turnId: string | null;
  question?: string;
  sources: SourceChunk[];
  answer: string;
  loading: boolean;
  /** retrieval finished for this turn (an empty list then means "nothing found", not "nothing yet") */
  retrieved?: boolean;
  /** the reranker took part in that retrieval */
  reranked?: boolean;
  /** retrieval searched for this spell-corrected query instead of the question */
  correctedQuery?: string;
  /** asks the question again, retrieving for it as typed (shown with a correction; absent while busy) */
  onAskAsTyped?: () => void;
  /** ref to scroll to & flash; `nonce` changes on every click so repeated clicks re-trigger */
  focus: { ref: number; nonce: number } | null;
  onCollapse: () => void;
}

export const sourceDomId = (turnId: string, ref: number) => `src-${turnId}-${ref}`;

/** Right-hand panel listing the passages retrieved for the selected answer. */
export function SourcesPanel({
  turnId,
  question,
  sources,
  answer,
  loading,
  retrieved,
  reranked,
  correctedQuery,
  onAskAsTyped,
  focus,
  onCollapse,
}: Props) {
  const cited = useMemo(
    () => new Set(citedRefs(answer, (r) => sources.some((s) => s.ref === r))),
    [answer, sources],
  );

  useEffect(() => {
    if (!focus || !turnId) return;
    const el = document.getElementById(sourceDomId(turnId, focus.ref));
    el?.scrollIntoView({ behavior: "smooth", block: "nearest" });
  }, [focus, turnId]);

  return (
    <aside className="sources-panel" aria-label="Sources">
      <div className="sources-panel-header">
        <div className="sources-panel-title">
          <span className={Classes.HEADING}>Sources</span>
          {sources.length > 0 && (
            <span className={Classes.TEXT_MUTED}>
              {" "}
              · {sources.length} passages{cited.size > 0 && `, ${cited.size} cited`}
            </span>
          )}
          {question && (
            <div className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL} ${Classes.TEXT_OVERFLOW_ELLIPSIS}`} title={question}>
              for “{question}”
            </div>
          )}
          {correctedQuery && (
            <div className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL} sources-corrected`}>
              <span className={Classes.TEXT_OVERFLOW_ELLIPSIS} title={`Spelling corrected: searched for “${correctedQuery}”`}>
                searched for “{correctedQuery}”
              </span>
              {onAskAsTyped && (
                <button type="button" className="correction-original" onClick={onAskAsTyped}>
                  ask as typed
                </button>
              )}
            </div>
          )}
        </div>
        <Tooltip content="Collapse" placement="bottom-end">
          <Button variant="minimal" icon="chevron-right" aria-label="Collapse sources panel" onClick={onCollapse} />
        </Tooltip>
      </div>
      <div className="sources-panel-list">
        {loading && sources.length === 0 ? (
          <NonIdealState icon={<Spinner size={24} />} description="Retrieving passages…" />
        ) : sources.length === 0 && retrieved ? (
          <NonIdealState
            icon="inbox-search"
            iconSize={NonIdealStateIconSize.SMALL}
            description={
              reranked
                ? "No passage was relevant enough to the reranker. Try other terms, widen the scope, or turn reranking off."
                : "No passages matched this question. Try other terms or widen the scope."
            }
          />
        ) : sources.length === 0 ? (
          <NonIdealState icon="manual" iconSize={NonIdealStateIconSize.SMALL} description="Passages used for an answer show up here." />
        ) : (
          sources.map((s) => (
            <SourceCard
              key={s.chunkId || s.ref}
              id={turnId ? sourceDomId(turnId, s.ref) : undefined}
              source={s}
              highlighted={focus?.ref === s.ref}
              dimmed={!loading && cited.size > 0 && !cited.has(s.ref)}
            />
          ))
        )}
      </div>
    </aside>
  );
}
