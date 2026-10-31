import { useMemo } from "react";
import { highlightParts } from "../lib/highlight";

export function HighlightedText({ text, terms }: { text: string; terms: string[] }) {
  const parts = useMemo(() => highlightParts(text, terms), [text, terms]);
  return (
    <>
      {parts.map((p, i) => (p.match ? <mark key={i}>{p.text}</mark> : <span key={i}>{p.text}</span>))}
    </>
  );
}
