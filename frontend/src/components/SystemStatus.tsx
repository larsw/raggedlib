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

/** " · writable", or why a library configured writable stays read-only (admins only see the reason). */
function libraryAccess(l: { writable: boolean; readOnlyReason: string | null }): string {
  if (l.writable) return " · writable";
  return l.readOnlyReason ? ` · READ-ONLY: ${l.readOnlyReason}` : "";
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
    const rerankerOk = !system.rerankEnabled || system.rerankerReachable;
    // a library configured writable that isn't (reason shown to admins only)
    const librariesOk = system.libraries.every((l) => !l.readOnlyReason);
    intent = system.ollamaReachable && rerankerOk && librariesOk ? Intent.SUCCESS : Intent.WARNING;
    label = system.chatModel;
    content = (
      <table className="status-table">
        <tbody>
          <Row label="Ollama" value={system.ollamaReachable ? "reachable" : "UNREACHABLE"} />
          <Row label="Ollama URL" value={system.ollamaUrl} />
          <Row label="Chat model" value={system.chatModel} />
          <Row label="Embeddings" value={`${system.embeddingModel} (${system.embeddingDimensions}d)`} />
          <Row label="Models" value={system.availableChatModels.length ? system.availableChatModels.join(", ") : "—"} />
          <Row
            label="Reranker"
            value={
              !system.rerankEnabled
                ? "off"
                : system.rerankerReachable
                  ? `${system.rerankerModel ?? "reachable"} (${system.rerankerUrl})`
                  : `UNREACHABLE (${system.rerankerUrl})`
            }
          />
          {system.libraries.some((l) => l.path != null) ? (
            system.libraries.map((l) => (
              <Row
                key={l.name}
                label={system.libraries.length > 1 ? `Library: ${l.name}` : "Library"}
                value={`${l.path ?? "—"}${libraryAccess(l)}`}
              />
            ))
          ) : (
            // where libraries are on disk is for admins
            <Row
              label={system.libraries.length === 1 ? "Library" : "Libraries"}
              value={system.libraries.map((l) => `${l.name}${l.writable ? " (writable)" : ""}`).join(", ") || "—"}
            />
          )}
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
