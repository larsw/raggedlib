import { Button, Classes, Icon } from "@blueprintjs/core";

const EXAMPLES = [
  "How does a ROP chain get around DEP/NX, and what do ASLR and CFI add?",
  "Compare parameterized queries, ORMs and input escaping as SQL injection defenses.",
  "How do Kerberoasting and golden ticket attacks work, and how can they be detected?",
  "What changed in the TLS 1.3 handshake compared to TLS 1.2?",
  "Rust ownership vs. garbage collection: what are the real trade-offs?",
  "When is event sourcing a good idea, and what are the common pitfalls?",
];

export function AskEmptyState({ onPick }: { onPick: (q: string) => void }) {
  return (
    <div className="ask-empty">
      <Icon icon="manual" size={40} className="ask-empty-icon" />
      <h2 className={Classes.HEADING}>Ask your library</h2>
      <p className={Classes.TEXT_MUTED}>
        Answers are grounded in passages retrieved from your Calibre books and cite them like{" "}
        <span className="citation-chip is-static">1</span>. Narrow the scope below to specific books or tags.
      </p>
      <div className="ask-examples">
        {EXAMPLES.map((q) => (
          <Button key={q} variant="outlined" alignText="start" className="ask-example" onClick={() => onPick(q)} text={q} />
        ))}
      </div>
    </div>
  );
}
