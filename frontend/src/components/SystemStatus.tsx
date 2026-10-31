import { Classes, Icon, Intent, Tooltip } from "@blueprintjs/core";
import { useApp } from "../AppContext";
import { errorMessage } from "../api/client";
import { isUnreachable } from "./ErrorState";

function Row({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <tr>
      <th>{label}</th>
      <td>{value}</td>
    </tr>
  );
}

/** Navbar dot: backend + ollama health, details in a tooltip. */
export function SystemStatus() {
  const { system, systemError, systemLoading } = useApp();

  let intent: Intent = Intent.NONE;
  let label = "Checking…";
  let content: string | React.JSX.Element = "Checking backend…";

  if (systemError) {
    intent = Intent.DANGER;
    label = isUnreachable(systemError) ? "Backend down" : "Backend error";
    content = isUnreachable(systemError)
      ? "Backend unreachable on :8080 — start it to use raggedlib."
      : `Backend error: ${errorMessage(systemError)}`;
  } else if (system) {
    intent = system.ollamaReachable ? Intent.SUCCESS : Intent.WARNING;
    label = system.chatModel;
    content = (
      <table className="status-table">
        <tbody>
          <Row label="Ollama" value={system.ollamaReachable ? "reachable" : "UNREACHABLE"} />
          <Row label="Ollama URL" value={system.ollamaUrl} />
          <Row label="Chat model" value={system.chatModel} />
          <Row label="Embeddings" value={`${system.embeddingModel} (${system.embeddingDimensions}d)`} />
          <Row label="Models" value={system.availableChatModels.length ? system.availableChatModels.join(", ") : "—"} />
          <Row label="Library" value={system.libraryPath} />
        </tbody>
      </table>
    );
  } else if (!systemLoading) {
    label = "Unknown";
  }

  return (
    <Tooltip content={content} placement="bottom-end">
      <span className="system-status" tabIndex={0} role="status" aria-label={`System status: ${label}`}>
        <Icon icon="full-circle" size={8} intent={intent} className={`status-dot status-${intent}`} />
        <span className={`${Classes.TEXT_SMALL} ${Classes.TEXT_MUTED} system-status-label`}>{label}</span>
      </span>
    </Tooltip>
  );
}
