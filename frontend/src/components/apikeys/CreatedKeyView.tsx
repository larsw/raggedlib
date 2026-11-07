import { Button, Callout, Classes, FormGroup, InputGroup, Intent, Tag, Tooltip } from "@blueprintjs/core";
import type { CreatedApiKey } from "../../api/types";
import { useApp } from "../../AppContext";
import { keyLibrariesLabel, mcpAddCommand } from "../../lib/apiKeys";
import { copyText } from "../../lib/clipboard";
import { formatDate } from "../../lib/format";
import { toastError, toastSuccess } from "../toaster";

async function copy(text: string, what: string) {
  try {
    await copyText(text);
    toastSuccess(`${what} copied`);
  } catch (err) {
    toastError("Couldn't copy", err);
  }
}

/** Shows a new key once, with a ready-to-paste command for Claude Code. */
export function CreatedKeyView({ created, onDone }: { created: CreatedApiKey; onDone: () => void }) {
  const { libraries } = useApp();
  const { apiKey, rawKey } = created;
  const origin = window.location.origin;
  const command = mcpAddCommand(origin, rawKey);

  return (
    <div className="created-key">
      <Callout intent={Intent.WARNING} icon="warning-sign" compact>
        Copy the key now: this is the only time it is shown. If it gets lost, revoke it and create another.
      </Callout>

      <FormGroup label={`Key “${apiKey.name}”`} labelFor="created-key-value" className="created-key-group">
        <InputGroup
          id="created-key-value"
          readOnly
          fill
          value={rawKey}
          className="created-key-value"
          onFocus={(e) => e.currentTarget.select()}
          rightElement={
            <Tooltip content="Copy the key" placement="top">
              <Button variant="minimal" icon="clipboard" aria-label="Copy the key" onClick={() => copy(rawKey, "Key")} />
            </Tooltip>
          }
        />
      </FormGroup>

      <FormGroup
        label="Add it to Claude Code"
        helperText={
          <>
            Other MCP clients: streamable HTTP at <code>{origin}/mcp</code>, with the header{" "}
            <code>Authorization: Bearer &lt;key&gt;</code>.
          </>
        }
      >
        <div className="command-block">
          <pre>{command}</pre>
          <Tooltip content="Copy the command" placement="top">
            <Button variant="minimal" icon="clipboard" aria-label="Copy the command" onClick={() => copy(command, "Command")} />
          </Tooltip>
        </div>
      </FormGroup>

      <div className="created-key-summary">
        {apiKey.scopes.map((s) => (
          <Tag key={s} minimal>
            {s}
          </Tag>
        ))}
        <Tag minimal icon="book">
          {keyLibrariesLabel(apiKey.libraryIds, libraries)}
        </Tag>
        <span className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL}`}>
          {apiKey.expiresAt ? `expires ${formatDate(apiKey.expiresAt)}` : "never expires"}
        </span>
      </div>

      <div className="api-key-form-actions">
        <Button intent={Intent.PRIMARY} text="Done" onClick={onDone} />
      </div>
    </div>
  );
}
