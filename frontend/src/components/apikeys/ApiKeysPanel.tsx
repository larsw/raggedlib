import { Button, Classes, Code, Intent, NonIdealState, Spinner, Tag, Tooltip } from "@blueprintjs/core";
import { useState } from "react";
import { api } from "../../api/client";
import type { ApiKey, CreatedApiKey } from "../../api/types";
import { useApp } from "../../AppContext";
import { useAsync } from "../../hooks/useAsync";
import { isExpired, keyLibrariesLabel } from "../../lib/apiKeys";
import { formatDate, formatRelative } from "../../lib/format";
import { ErrorCallout } from "../ErrorState";
import { toastError, toastSuccess } from "../toaster";
import { ApiKeyForm } from "./ApiKeyForm";
import { CreatedKeyView } from "./CreatedKeyView";

interface Props {
  /** System-wide keys (global admins), instead of the caller's own. */
  system?: boolean;
}

/** List, create and revoke API keys: the caller's own, or (system) the system-wide ones. */
export function ApiKeysPanel({ system = false }: Props) {
  const keys = useAsync((signal) => (system ? api.systemApiKeys(signal) : api.apiKeys(signal)), [system]);
  const [creating, setCreating] = useState(false);
  const [created, setCreated] = useState<CreatedApiKey | null>(null);

  if (created) {
    return (
      <CreatedKeyView
        created={created}
        onDone={() => {
          setCreated(null);
          setCreating(false);
          keys.reload();
        }}
      />
    );
  }
  if (creating) return <ApiKeyForm system={system} onCancel={() => setCreating(false)} onCreated={setCreated} />;

  const revoke = async (key: ApiKey) => {
    try {
      await (system ? api.revokeSystemApiKey(key.id) : api.revokeApiKey(key.id));
      keys.setData((ks) => ks?.filter((k) => k.id !== key.id));
      toastSuccess(`Revoked “${key.name}”`);
    } catch (err) {
      toastError("Couldn't revoke the key", err);
    }
  };

  const origin = window.location.origin;
  return (
    <div className="api-keys">
      <div className="api-keys-intro">
        <p className={Classes.TEXT_MUTED}>
          {system ? (
            <>
              System-wide keys act as no user: for shared bots and integrations. They can only have library scopes,
              and reach every library or just the ones you pick.
            </>
          ) : (
            <>
              A key lets an agent or a script use raggedlib as you, with the scopes you pick: over MCP at{" "}
              <Code>{origin}/mcp</Code>, or the REST API. It never does more than your own roles allow.
            </>
          )}
        </p>
        <Button intent={Intent.PRIMARY} icon="plus" text="New key" onClick={() => setCreating(true)} />
      </div>
      {keys.error && !keys.data ? (
        <ErrorCallout error={keys.error} title="Couldn't load the keys" />
      ) : !keys.data ? (
        <NonIdealState icon={<Spinner size={24} />} />
      ) : keys.data.length === 0 ? (
        <NonIdealState
          icon="key"
          title={system ? "No system-wide keys" : "No API keys yet"}
          description={system ? undefined : "Create one to connect Claude Code or another MCP client."}
        />
      ) : (
        <ul className="api-key-list">
          {keys.data.map((k) => (
            <ApiKeyRow key={k.id} apiKey={k} showCreator={system} onRevoke={() => revoke(k)} />
          ))}
        </ul>
      )}
    </div>
  );
}

function ApiKeyRow({ apiKey: k, showCreator, onRevoke }: { apiKey: ApiKey; showCreator: boolean; onRevoke: () => Promise<void> }) {
  const { libraries } = useApp();
  const [confirming, setConfirming] = useState(false);
  const [revoking, setRevoking] = useState(false);
  const expired = isExpired(k);

  return (
    <li className={`api-key-row${expired ? " is-expired" : ""}`}>
      <div className="api-key-main">
        <div className="api-key-title">
          <strong>{k.name}</strong>
          <Code className="api-key-prefix">{k.keyPrefix}…</Code>
          {expired && (
            <Tag minimal intent={Intent.DANGER}>
              expired
            </Tag>
          )}
        </div>
        <div className="api-key-scopes">
          {k.scopes.map((s) => (
            <Tag key={s} minimal className="api-key-scope">
              {s}
            </Tag>
          ))}
          <Tag minimal icon="book" intent={k.libraryIds ? Intent.PRIMARY : Intent.NONE}>
            {keyLibrariesLabel(k.libraryIds, libraries)}
          </Tag>
        </div>
        <div className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL}`}>
          Created {formatDate(k.createdAt)}
          {showCreator && k.createdBy && ` by ${k.createdBy}`} · last used {k.lastUsedAt ? formatRelative(k.lastUsedAt) : "never"}
          {k.expiresAt && ` · ${expired ? "expired" : "expires"} ${formatDate(k.expiresAt)}`}
        </div>
      </div>
      <div className="api-key-actions">
        {confirming ? (
          <>
            <Button
              size="small"
              intent={Intent.DANGER}
              icon="trash"
              text="Revoke"
              loading={revoking}
              onClick={async () => {
                setRevoking(true);
                await onRevoke();
                setRevoking(false);
                setConfirming(false);
              }}
            />
            <Button size="small" variant="minimal" text="Cancel" onClick={() => setConfirming(false)} />
          </>
        ) : (
          <Tooltip content="Revoke: it stops working at once" placement="top">
            <Button
              size="small"
              variant="minimal"
              intent={Intent.DANGER}
              icon="trash"
              aria-label={`Revoke ${k.name}`}
              onClick={() => setConfirming(true)}
            />
          </Tooltip>
        )}
      </div>
    </li>
  );
}
