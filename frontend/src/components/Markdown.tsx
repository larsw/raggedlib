import { memo, useMemo } from "react";
import ReactMarkdown, { type Components, type Options } from "react-markdown";
import remarkGfm from "remark-gfm";
import type { SourceChunk } from "../api/types";
import { remarkCitations } from "../lib/remarkCitations";
import { CitationChip } from "./CitationChip";

interface Props {
  text: string;
  sources?: SourceChunk[];
  onCite?: (ref: number) => void;
}

/** Renders assistant markdown (GFM tables, code) with [n] markers turned into citation chips. */
export const Markdown = memo(function Markdown({ text, sources = [], onCite }: Props) {
  const byRef = useMemo(() => new Map(sources.map((s) => [s.ref, s])), [sources]);

  const remarkPlugins = useMemo<Options["remarkPlugins"]>(
    () => [remarkGfm, [remarkCitations, { isValid: (r: number) => byRef.has(r) }]],
    [byRef],
  );

  const components = useMemo<Components>(
    () => ({
      cite: ({ node }) => {
        const refs = String(node?.properties?.dataRefs ?? "")
          .split(",")
          .map(Number)
          .filter((r) => byRef.has(r));
        return (
          <span className="citation-group">
            {refs.map((r) => (
              <CitationChip key={r} source={byRef.get(r)!} onClick={onCite} />
            ))}
          </span>
        );
      },
      a: ({ node: _node, ...props }) => <a {...props} target="_blank" rel="noreferrer noopener" />,
      table: ({ node: _node, ...props }) => (
        <div className="md-table-wrap">
          <table {...props} />
        </div>
      ),
    }),
    [byRef, onCite],
  );

  return (
    <div className="markdown">
      <ReactMarkdown remarkPlugins={remarkPlugins} components={components}>
        {text}
      </ReactMarkdown>
    </div>
  );
});
