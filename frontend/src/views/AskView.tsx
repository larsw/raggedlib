import { Button, Classes, Tooltip } from "@blueprintjs/core";
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import { useApp } from "../AppContext";
import { ScopeBar } from "../components/scope/ScopeBar";
import { SourcesPanel } from "../components/SourcesPanel";
import { useChat, type ChatTurn } from "../hooks/useChat";
import { AskEmptyState } from "./ask/AskEmptyState";
import { ChatInput } from "./ask/ChatInput";
import { ChatTurnView } from "./ask/ChatTurnView";

export function AskView() {
  const { scope, system } = useApp();
  const { turns, busy, send, stop, clear, remove } = useChat();
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [panelOpen, setPanelOpen] = useState(true);
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
    setPanelOpen(true);
  }, []);

  const onCite = useCallback((turnId: string, ref: number) => {
    setSelectedId(turnId);
    setPanelOpen(true);
    setFocus({ ref, nonce: Date.now() });
  }, []);

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
    <div className="ask-view">
      <section className="ask-main" aria-label="Conversation">
        <div className="view-toolbar">
          <span className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL}`}>
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
          {!panelOpen && (
            <Tooltip content="Show sources" placement="bottom-end">
              <Button size="small" variant="minimal" icon="manual" aria-label="Show sources panel" onClick={() => setPanelOpen(true)} />
            </Tooltip>
          )}
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
          <ScopeBar />
          <ChatInput busy={busy} onSend={ask} onStop={stop} />
        </div>
      </section>
      {panelOpen && (
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
          onClose={() => setPanelOpen(false)}
        />
      )}
    </div>
  );
}
