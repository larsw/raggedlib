import { AnchorButton, Button, ControlGroup, Tooltip } from "@blueprintjs/core";
import { useApp } from "../../AppContext";
import { scopeIsEmpty } from "../../hooks/useScope";
import { BookMultiSelect } from "./BookMultiSelect";
import { TagMultiSelect } from "./TagMultiSelect";

/** Retrieval scope controls shared by Ask and Search (state lives in AppContext; model and top-k are in Settings). */
export function ScopeBar() {
  const { scope, setScope, system } = useApp();

  // unknown (system info still loading) counts as available, so a stored "on" isn't shown as off meanwhile
  const rerankUnavailable = system !== undefined && !system.rerankEnabled;
  const rerankOn = scope.rerank && !rerankUnavailable;

  return (
    <div className="scope-bar">
      <div className="scope-bar-books">
        <BookMultiSelect selected={scope.books} onChange={(books) => setScope((s) => ({ ...s, books }))} />
      </div>
      <div className="scope-bar-tags">
        <TagMultiSelect selected={scope.tags} onChange={(tags) => setScope((s) => ({ ...s, tags }))} />
      </div>
      <ControlGroup className="scope-bar-options">
        <Tooltip
          content={
            !system
              ? "Checking whether reranking is available…"
              : !system.rerankEnabled
                ? "Reranking is not configured on the server"
                : !system.rerankerReachable
                  ? `Reranker unreachable (${system.rerankerUrl}) — results use the fused order`
                  : scope.rerank
                    ? "Reranking on: a cross-encoder re-scores the passages and drops irrelevant ones"
                    : "Reranking off: fused vector + full-text order"
          }
          placement="top"
        >
          {/* AnchorButton: unlike a disabled Button it still gets hover/focus, so the tooltip can say why */}
          <AnchorButton
            icon="sort"
            aria-label="Rerank passages"
            aria-pressed={rerankOn}
            active={rerankOn}
            disabled={rerankUnavailable}
            tabIndex={0}
            onClick={() => setScope((s) => ({ ...s, rerank: !s.rerank }))}
          />
        </Tooltip>
        <Tooltip content="Reset scope" placement="top">
          <Button
            icon="filter-remove"
            aria-label="Reset scope"
            disabled={scopeIsEmpty(scope)}
            onClick={() => setScope((s) => ({ ...s, books: [], tags: [] }))}
          />
        </Tooltip>
      </ControlGroup>
    </div>
  );
}
