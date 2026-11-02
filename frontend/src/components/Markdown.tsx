import { memo, useMemo } from "react";
import ReactMarkdown, { type Components, type Options } from "react-markdown";
import rehypeKatex from "rehype-katex";
import remarkGfm from "remark-gfm";
import remarkMath from "remark-math";
import type { SourceChunk } from "../api/types";
import { normalizeMath } from "../lib/math";
import { remarkCitations } from "../lib/remarkCitations";
import { CitationChip } from "./CitationChip";

const REHYPE_PLUGINS: Options["rehypePlugins"] = [[rehypeKatex, { throwOnError: false, strict: "ignore", output: "htmlAndMathml" }]];

interface Props {
  text: string;
  sources?: SourceChunk[];
  onCite?: (ref: number) => void;
}

/**
 * Renders assistant markdown (GFM tables, code, LaTeX math via KaTeX) with [n] markers turned into citation chips.
 * A formula KaTeX can't parse is shown as its source in red instead of breaking the answer.
 */
export const Markdown = memo(function Markdown({ text, sources = [], onCite }: Props) {
  const byRef = useMemo(() => new Map(sources.map((s) => [s.ref, s])), [sources]);

  const remarkPlugins = useMemo<Options["remarkPlugins"]>(
    () => [remarkGfm, remarkMath, [remarkCitations, { isValid: (r: number) => byRef.has(r) }]],
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
      <ReactMarkdown remarkPlugins={remarkPlugins} rehypePlugins={REHYPE_PLUGINS} components={components}>
        {normalizeMath(text)}
      </ReactMarkdown>
    </div>
  );
});
