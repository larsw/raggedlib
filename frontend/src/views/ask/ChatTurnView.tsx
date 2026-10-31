import { Button, Callout, Classes, Collapse, Intent, Spinner, Tag, Tooltip } from "@blueprintjs/core";
import { memo, useState } from "react";
import { MarkdownBody } from "./MarkdownBody";
import type { ChatTurn } from "../../hooks/useChat";
import { splitThinking } from "../../lib/thinking";

interface Props {
  turn: ChatTurn;
  selected: boolean;
  onSelect: (id: string) => void;
  onCite: (turnId: string, ref: number) => void;
  onRetry: (turn: ChatTurn) => void;
  onRemove: (id: string) => void;
}

function copy(text: string) {
  navigator.clipboard?.writeText(text).catch(() => {});
}

export const ChatTurnView = memo(function ChatTurnView({ turn, selected, onSelect, onCite, onRetry, onRemove }: Props) {
  const [showThinking, setShowThinking] = useState(false);
  const { thinking, answer, thinkingDone } = splitThinking(turn.answer);
  const live = turn.status === "pending" || turn.status === "streaming";

  return (
    <article className={`chat-turn${selected ? " is-selected" : ""}`} aria-busy={live}>
      <div className="chat-question">
        <div className="chat-question-text">{turn.question}</div>
        {(turn.scopeLabel || turn.model) && (
          <div className="chat-question-meta">
            {turn.scopeLabel && (
              <Tag minimal icon="filter" className="meta-tag">
                {turn.scopeLabel}
              </Tag>
            )}
            {turn.model && (
              <Tag minimal icon="predictive-analysis" className="meta-tag">
                {turn.model}
              </Tag>
            )}
          </div>
        )}
      </div>

      <div className="chat-answer" onClick={() => onSelect(turn.id)}>
        {turn.status === "pending" && (
          <div className="chat-progress">
            <Spinner size={16} /> <span className={Classes.TEXT_MUTED}>Searching the library…</span>
          </div>
        )}
        {turn.status === "streaming" && !turn.answer && (
          <div className="chat-progress">
            <Spinner size={16} />{" "}
            <span className={Classes.TEXT_MUTED}>Found {turn.sources.length} passages, generating answer…</span>
          </div>
        )}

        {thinking !== null && (
          <div className="chat-thinking">
            <Button
              size="small"
              variant="minimal"
              icon={thinkingDone ? "lightbulb" : <Spinner size={12} />}
              endIcon={showThinking ? "chevron-up" : "chevron-down"}
              text={thinkingDone ? "Reasoning" : "Reasoning…"}
              onClick={(e) => {
                e.stopPropagation();
                setShowThinking((v) => !v);
              }}
            />
            <Collapse isOpen={showThinking}>
              <div className="chat-thinking-text">{thinking}</div>
            </Collapse>
          </div>
        )}

        {answer && (
          <MarkdownBody
            text={answer}
            sources={turn.sources}
            streaming={turn.status === "streaming"}
            onCite={(ref) => onCite(turn.id, ref)}
          />
        )}

        {turn.status === "error" && (
          <Callout
            intent={turn.unreachable ? Intent.WARNING : Intent.DANGER}
            icon={turn.unreachable ? "offline" : "error"}
            title={turn.unreachable ? "Backend unreachable" : "The answer failed"}
            compact
            className="chat-error"
          >
            {turn.unreachable ? "Start the backend on localhost:8080 and retry." : turn.error}
          </Callout>
        )}
        {turn.status === "stopped" && <div className={`${Classes.TEXT_MUTED} chat-stopped`}>Stopped.</div>}
      </div>

      {!live && (
        <div className="chat-turn-footer">
          {turn.sources.length > 0 && (
            <Button
              size="small"
              variant="minimal"
              icon="manual"
              active={selected}
              text={`${turn.sources.length} sources`}
              onClick={() => onSelect(turn.id)}
            />
          )}
          {turn.elapsedMs != null && (
            <span className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL}`}>{(turn.elapsedMs / 1000).toFixed(1)}s</span>
          )}
          <span className="spacer" />
          {answer && (
            <Tooltip content="Copy answer" placement="top">
              <Button size="small" variant="minimal" icon="duplicate" aria-label="Copy answer" onClick={() => copy(answer)} />
            </Tooltip>
          )}
          <Tooltip content="Ask again" placement="top">
            <Button size="small" variant="minimal" icon="refresh" aria-label="Ask this question again" onClick={() => onRetry(turn)} />
          </Tooltip>
          <Tooltip content="Remove from conversation" placement="top">
            <Button size="small" variant="minimal" icon="trash" aria-label="Remove this question and answer" onClick={() => onRemove(turn.id)} />
          </Tooltip>
        </div>
      )}
    </article>
  );
});
