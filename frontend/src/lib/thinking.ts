/**
 * Reasoning models (qwen3, deepseek-r1, ...) may stream "<think>...</think>" before the answer.
 * Splits it off so it can be shown collapsed and kept out of the conversation history.
 */
export function splitThinking(text: string): { thinking: string | null; answer: string; thinkingDone: boolean } {
  const m = /^\s*<think>([\s\S]*?)(<\/think>|$)/.exec(text);
  if (!m) return { thinking: null, answer: text, thinkingDone: true };
  const done = m[2] === "</think>";
  return { thinking: m[1]!.trim(), answer: done ? text.slice(m[0].length).trimStart() : "", thinkingDone: done };
}
