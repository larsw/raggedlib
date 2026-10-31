import { useCallback, useEffect, useRef, useState } from "react";
import { api, ApiError, errorMessage, isAbortError } from "../api/client";
import type { ChatHistoryMessage, ChatRequest, SourceChunk } from "../api/types";
import { loadJson, saveJson, STORAGE_KEYS } from "../lib/storage";
import { splitThinking } from "../lib/thinking";
import type { Scope } from "./useScope";

export type TurnStatus = "pending" | "streaming" | "done" | "error" | "stopped";

export interface ChatTurn {
  id: string;
  question: string;
  answer: string;
  sources: SourceChunk[];
  status: TurnStatus;
  error?: string;
  /** backend unreachable -> show the "start the backend" hint */
  unreachable?: boolean;
  elapsedMs?: number;
  model?: string;
  scopeLabel?: string;
  createdAt: string;
}

/** How many previous Q/A pairs are sent as history (keeps the prompt small for local models). */
export const MAX_HISTORY_TURNS = 6;
const MAX_STORED_TURNS = 100;
const FLUSH_MS = 40;

function restore(): ChatTurn[] {
  const turns = loadJson<ChatTurn[]>(STORAGE_KEYS.conversation, []);
  if (!Array.isArray(turns)) return [];
  return turns
    .filter((t) => t && typeof t.id === "string" && typeof t.question === "string")
    .map((t) =>
      t.status === "pending" || t.status === "streaming" ? { ...t, status: "stopped" as const } : t,
    );
}

export function buildHistory(turns: ChatTurn[]): ChatHistoryMessage[] {
  return turns
    .filter((t) => t.status === "done" || t.status === "stopped")
    .map((t) => ({ question: t.question, answer: splitThinking(t.answer).answer.trim() }))
    .filter((t) => t.answer)
    .slice(-MAX_HISTORY_TURNS)
    .flatMap((t) => [
      { role: "user" as const, content: t.question },
      { role: "assistant" as const, content: t.answer },
    ]);
}

function scopeLabel(scope: Scope): string | undefined {
  const parts: string[] = [];
  if (scope.books.length === 1) parts.push(scope.books[0]!.title);
  else if (scope.books.length > 1) parts.push(`${scope.books.length} books`);
  if (scope.tags.length) parts.push(scope.tags.map((t) => `#${t}`).join(" "));
  return parts.length ? parts.join(" · ") : undefined;
}

export function useChat() {
  const [turns, setTurns] = useState<ChatTurn[]>(restore);
  const ctrlRef = useRef<AbortController | null>(null);
  const turnsRef = useRef(turns);
  turnsRef.current = turns;
  const busy = turns.some((t) => t.status === "pending" || t.status === "streaming");

  // Persist when idle (not on every token).
  useEffect(() => {
    if (!busy) saveJson(STORAGE_KEYS.conversation, turns.slice(-MAX_STORED_TURNS));
  }, [turns, busy]);

  // Abort an in-flight request on unmount.
  useEffect(() => () => ctrlRef.current?.abort(), []);

  const update = useCallback((id: string, patch: (t: ChatTurn) => Partial<ChatTurn>) => {
    setTurns((ts) => ts.map((t) => (t.id === id ? { ...t, ...patch(t) } : t)));
  }, []);

  const send = useCallback(
    async (question: string, scope: Scope) => {
      const q = question.trim();
      if (!q) return;
      ctrlRef.current?.abort();
      const ctrl = new AbortController();
      ctrlRef.current = ctrl;

      const id = crypto.randomUUID();
      const history = buildHistory(turnsRef.current);
      setTurns((ts) => [
          ...ts,
          {
            id,
            question: q,
            answer: "",
            sources: [],
            status: "pending",
            model: scope.model || undefined,
            scopeLabel: scopeLabel(scope),
            createdAt: new Date().toISOString(),
          },
        ]);

      const req: ChatRequest = {
        query: q,
        topK: scope.topK,
        ...(scope.books.length ? { bookIds: scope.books.map((b) => b.id) } : {}),
        ...(scope.tags.length ? { tags: scope.tags } : {}),
        ...(history.length ? { history } : {}),
        ...(scope.model ? { model: scope.model } : {}),
      };

      // Tokens are buffered and flushed at most every FLUSH_MS to keep re-renders cheap.
      let answer = "";
      let timer: ReturnType<typeof setTimeout> | undefined;
      const flush = () => {
        timer = undefined;
        const a = answer;
        update(id, () => ({ answer: a }));
      };
      let finished = false;

      try {
        for await (const ev of api.chat(req, ctrl.signal)) {
          switch (ev.type) {
            case "sources":
              update(id, () => ({ sources: ev.sources, status: "streaming" }));
              break;
            case "token":
              answer += ev.t;
              timer ??= setTimeout(flush, FLUSH_MS);
              break;
            case "done":
              finished = true;
              clearTimeout(timer);
              update(id, () => ({ answer, status: "done", elapsedMs: ev.elapsedMs }));
              break;
            case "error":
              finished = true;
              clearTimeout(timer);
              update(id, () => ({ answer, status: "error", error: ev.error }));
              break;
          }
        }
        if (!finished) {
          clearTimeout(timer);
          if (ctrl.signal.aborted) update(id, () => ({ answer, status: "stopped" }));
          else update(id, () => ({ answer, status: "error", error: "The answer stream ended unexpectedly." }));
        }
      } catch (err) {
        clearTimeout(timer);
        if (isAbortError(err) || ctrl.signal.aborted) {
          update(id, () => ({ answer, status: "stopped" }));
        } else {
          update(id, () => ({
            answer,
            status: "error",
            error: errorMessage(err),
            unreachable: err instanceof ApiError && err.unreachable,
          }));
        }
      } finally {
        if (ctrlRef.current === ctrl) ctrlRef.current = null;
      }
    },
    [update],
  );

  const stop = useCallback(() => ctrlRef.current?.abort(), []);

  const clear = useCallback(() => {
    ctrlRef.current?.abort();
    setTurns([]);
  }, []);

  const remove = useCallback((id: string) => setTurns((ts) => ts.filter((t) => t.id !== id)), []);

  return { turns, busy, send, stop, clear, remove };
}
