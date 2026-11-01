import { AnchorButton, Button, ControlGroup, HTMLSelect, NumericInput, Tooltip } from "@blueprintjs/core";
import { useApp } from "../../AppContext";
import { DEFAULT_TOP_K, MAX_TOP_K, scopeIsEmpty } from "../../hooks/useScope";
import { BookMultiSelect } from "./BookMultiSelect";
import { TagMultiSelect } from "./TagMultiSelect";

/** Retrieval scope controls shared by Ask and Search (state lives in AppContext). */
export function ScopeBar({ showModel = true }: { showModel?: boolean }) {
  const { scope, setScope, system } = useApp();
  const models = system?.availableChatModels ?? [];
  const modelOptions = [
    { value: "", label: system ? `Default (${system.chatModel})` : "Default model" },
    ...models.filter((m) => m !== system?.chatModel).map((m) => ({ value: m, label: m })),
  ];
  // keep a stored model visible even if it's no longer installed
  if (scope.model && !modelOptions.some((o) => o.value === scope.model)) {
    modelOptions.push({ value: scope.model, label: `${scope.model} (unavailable)` });
  }

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
        {showModel && (
          <Tooltip content="Chat model" placement="top">
            <HTMLSelect
              aria-label="Chat model"
              value={scope.model}
              options={modelOptions}
              onChange={(e) => setScope((s) => ({ ...s, model: e.currentTarget.value }))}
            />
          </Tooltip>
        )}
        <Tooltip content={`Passages to retrieve (top-k, 1–${MAX_TOP_K})`} placement="top">
          <NumericInput
            aria-label="Number of passages to retrieve"
            className="topk-input"
            min={1}
            max={MAX_TOP_K}
            value={scope.topK}
            leftIcon="layers"
            clampValueOnBlur
            minorStepSize={null}
            onValueChange={(v) => {
              if (Number.isFinite(v) && v >= 1 && v <= MAX_TOP_K) setScope((s) => ({ ...s, topK: Math.round(v) }));
            }}
          />
        </Tooltip>
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
            disabled={scopeIsEmpty(scope) && scope.topK === DEFAULT_TOP_K}
            onClick={() => setScope((s) => ({ ...s, books: [], tags: [], topK: DEFAULT_TOP_K }))}
          />
        </Tooltip>
      </ControlGroup>
    </div>
  );
}
