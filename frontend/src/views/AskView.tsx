import { Button, Classes, Icon } from "@blueprintjs/core";
import type { Scope } from "../hooks/useScope";
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import { useApp } from "../AppContext";
import { ScopeBar } from "../components/scope/ScopeBar";
import { SourcesPanel } from "../components/SourcesPanel";
import { useChat, type ChatTurn } from "../hooks/useChat";
import { useSidePanel } from "../hooks/useSidePanel";
import { AskEmptyState } from "./ask/AskEmptyState";
import { ChatInput } from "./ask/ChatInput";
import { ChatTurnView } from "./ask/ChatTurnView";

export function AskView() {
  const { scope, system } = useApp();
  const { turns, busy, send, stop, clear, remove } = useChat();
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const viewRef = useRef<HTMLDivElement>(null);
  /** phones: the scope controls fold behind a one-line summary (always shown on wider screens) */
  const [scopeOpen, setScopeOpen] = useState(false);
  const panel = useSidePanel(viewRef);
  const panelOpen = !panel.collapsed;
  const { setCollapsed } = panel;
  const [focus, setFocus] = useState<{ ref: number; nonce: number } | null>(null);

  const scrollRef = useRef<HTMLDivElement>(null);
  const stickToBottom = useRef(true);

  // Follow the latest answer unless the user picked an older one.
  const selected = turns.find((t) => t.id === selectedId) ?? turns[turns.length - 1] ?? null;

  const ask = useCallback(
    (q: string, opts?: { correct?: boolean }) => {
      setSelectedId(null);
      setFocus(null);
      stickToBottom.current = true;
      void send(q, scope, opts);
    },
    [send, scope],
  );

  const onSelect = useCallback((id: string) => {
    setSelectedId(id);
    setCollapsed(false);
  }, [setCollapsed]);

  const onCite = useCallback((turnId: string, ref: number) => {
    setSelectedId(turnId);
    setCollapsed(false);
    setFocus({ ref, nonce: Date.now() });
  }, [setCollapsed]);

  const onRetry = useCallback((turn: ChatTurn) => ask(turn.question), [ask]);

  const newConversation = () => {
    clear();
    setSelectedId(null);
    setFocus(null);
  };

  // Keep the newest tokens in view while streaming (only if already at the bottom).
  useLayoutEffect(() => {
    const el = scrollRef.current;
    if (el && stickToBottom.current) el.scrollTop = el.scrollHeight;
  }, [turns]);

  useEffect(() => {
    if (focus && selected && focus.ref && !selected.sources.some((s) => s.ref === focus.ref)) setFocus(null);
  }, [focus, selected]);

  const onScroll = () => {
    const el = scrollRef.current;
    if (el) stickToBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 48;
  };

  return (
    <div className={`ask-view${panel.dragging ? " is-resizing" : ""}`} ref={viewRef}>
      <section className="ask-main" aria-label="Conversation">
        <div className="view-toolbar">
          <span className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL} ask-toolbar-count`}>
            {turns.length ? `${turns.length} question${turns.length === 1 ? "" : "s"} in this conversation` : ""}
          </span>
          <span className="spacer" />
          <Button
            size="small"
            variant="minimal"
            icon="new-object"
            text="New conversation"
            disabled={turns.length === 0}
            onClick={newConversation}
          />
        </div>
        <div className="chat-scroll" ref={scrollRef} onScroll={onScroll}>
          {turns.length === 0 ? (
            <AskEmptyState onPick={ask} />
          ) : (
            <div className="chat-thread">
              {turns.map((t) => (
                <ChatTurnView
                  key={t.id}
                  turn={t}
                  selected={panelOpen && t.id === selected?.id && turns.length > 1}
                  onSelect={onSelect}
                  onCite={onCite}
                  onRetry={onRetry}
                  onRemove={remove}
                />
              ))}
            </div>
          )}
        </div>
        <div className="composer">
          <button
            type="button"
            className="scope-summary"
            aria-expanded={scopeOpen}
            aria-controls="ask-scope"
            onClick={() => setScopeOpen((o) => !o)}
          >
            <Icon icon="filter" size={12} />
            <span className="scope-summary-text">{scopeSummary(scope, system?.rerankEnabled !== false)}</span>
            <Icon icon={scopeOpen ? "chevron-down" : "chevron-up"} size={12} />
          </button>
          <div id="ask-scope" className={`composer-scope${scopeOpen ? " is-open" : ""}`}>
            <ScopeBar />
          </div>
          <ChatInput busy={busy} onSend={ask} onStop={stop} />
        </div>
      </section>
      {panel.collapsed ? (
        <button
          type="button"
          className="sources-rail"
          onClick={() => panel.setCollapsed(false)}
          aria-label={`Show sources${selected?.sources.length ? ` (${selected.sources.length} passages)` : ""}`}
          title="Show sources"
        >
          <Icon icon="chevron-left" size={14} />
          <span className="sources-rail-label">
            Sources{selected?.sources.length ? ` · ${selected.sources.length}` : ""}
          </span>
        </button>
      ) : (
        <div className="sources-dock" style={{ width: panel.width }}>
          <div className="sources-resize-handle" {...panel.handleProps} />
          <SourcesPanel
          turnId={selected?.id ?? null}
          question={turns.length > 1 ? selected?.question : undefined}
          sources={selected?.sources ?? []}
          answer={selected?.answer ?? ""}
          loading={selected?.status === "pending" || selected?.status === "streaming"}
          retrieved={!!selected?.retrieved}
          reranked={!!selected?.rerank && !!system?.rerankEnabled && !!system.rerankerReachable}
          correctedQuery={selected?.correctedQuery}
          onAskAsTyped={selected && !busy ? () => ask(selected.question, { correct: false }) : undefined}
          focus={focus}
          onCollapse={() => panel.setCollapsed(true)}
          />
        </div>
      )}
    </div>
  );
}

/** One line for the folded scope controls: "All books · reranked", "2 books · #rust · fused order". */
function scopeSummary(scope: Scope, rerankAvailable: boolean): string {
  const parts: string[] = [];
  if (scope.books.length === 1) parts.push(scope.books[0]!.title);
  else if (scope.books.length > 1) parts.push(`${scope.books.length} books`);
  if (scope.tags.length) parts.push(scope.tags.map((t) => `#${t}`).join(" "));
  if (!parts.length) parts.push("All books");
  parts.push(scope.rerank && rerankAvailable ? "reranked" : "fused order");
  return parts.join(" · ");
}
