import { splitCitations } from "./citations";

// Minimal mdast shapes (avoids depending on @types/mdast directly).
interface MdNode {
  type: string;
  value?: string;
  children?: MdNode[];
  data?: { hName?: string; hProperties?: Record<string, unknown> };
}

/** Node types whose text must not be touched. */
const SKIP = new Set(["code", "inlineCode", "link", "linkReference", "definition", "html"]);

/**
 * remark plugin: turns "[3]" / "[2][5]" in text into `<cite data-refs="2,5">` elements,
 * which the Markdown component renders as clickable citation chips.
 */
export function remarkCitations(options: { isValid: (ref: number) => boolean }) {
  const transform = (node: MdNode) => {
    if (!node.children) return;
    const out: MdNode[] = [];
    for (const child of node.children) {
      if (child.type === "text" && child.value) {
        for (const seg of splitCitations(child.value, options.isValid)) {
          if (seg.kind === "text") out.push({ type: "text", value: seg.text });
          else
            out.push({
              type: "citation",
              data: { hName: "cite", hProperties: { dataRefs: seg.refs.join(",") } },
              children: [{ type: "text", value: seg.raw }],
            });
        }
      } else {
        if (!SKIP.has(child.type)) transform(child);
        out.push(child);
      }
    }
    node.children = out;
  };
  return (tree: MdNode) => transform(tree);
}
