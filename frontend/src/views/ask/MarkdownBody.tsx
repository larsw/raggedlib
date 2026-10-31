import type { SourceChunk } from "../../api/types";
import { Markdown } from "../../components/Markdown";

/** Assistant answer body with a blinking caret while tokens stream in. */
export function MarkdownBody({
  text,
  sources,
  streaming,
  onCite,
}: {
  text: string;
  sources: SourceChunk[];
  streaming: boolean;
  onCite: (ref: number) => void;
}) {
  return (
    <div className={streaming ? "answer is-streaming" : "answer"}>
      <Markdown text={text} sources={sources} onCite={onCite} />
    </div>
  );
}
