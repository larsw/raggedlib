import { Button, Callout, Checkbox, Classes, FormGroup, HTMLSelect, InputGroup, Intent, Radio, RadioGroup, Spinner } from "@blueprintjs/core";
import { useState } from "react";
import { api, errorMessage } from "../../api/client";
import type { ApiScope, CreatedApiKey } from "../../api/types";
import { useApp } from "../../AppContext";
import { useAsync } from "../../hooks/useAsync";
import { EXPIRY_OPTIONS, expiresAt, groupScopes, type ExpiryChoice } from "../../lib/apiKeys";
import { ErrorCallout } from "../ErrorState";

interface Props {
  system: boolean;
  onCancel: () => void;
  onCreated: (key: CreatedApiKey) => void;
}

const DAY_MS = 86_400_000;

/** A new API key: name, scopes (grouped), an optional library restriction, and an expiry. */
export function ApiKeyForm({ system, onCancel, onCreated }: Props) {
  const { libraries } = useApp();
  const scopes = useAsync((signal) => api.apiKeyScopes(signal), []);
  const [name, setName] = useState("");
  const [selected, setSelected] = useState<Set<ApiScope>>(() => new Set(["library:read"]));
  const [restricted, setRestricted] = useState(false);
  const [libraryIds, setLibraryIds] = useState<Set<number>>(new Set());
  const [expiry, setExpiry] = useState<ExpiryChoice>("never");
  const [customDate, setCustomDate] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const groups = scopes.data ? groupScopes(scopes.data, system) : [];
  const toggle = <T,>(set: Set<T>, value: T): Set<T> => {
    const next = new Set(set);
    if (!next.delete(value)) next.add(value);
    return next;
  };

  const submit = async () => {
    setError(null);
    if (!name.trim()) return setError("Give the key a name.");
    if (selected.size === 0) return setError("Pick at least one scope.");
    if (restricted && libraryIds.size === 0) return setError("Pick at least one library, or allow every library.");
    const expires = expiresAt(expiry, customDate);
    if (expires === "invalid") return setError(customDate ? "The expiry date must be in the future." : "Pick an expiry date.");
    setSubmitting(true);
    try {
      const body = {
        name: name.trim(),
        // in the server's order, so the key lists them as the form showed them
        scopes: (scopes.data ?? []).map((s) => s.scope).filter((s) => selected.has(s)),
        ...(restricted ? { libraryIds: [...libraryIds] } : {}),
        ...(expires ? { expiresAt: expires } : {}),
      };
      onCreated(await (system ? api.createSystemApiKey(body) : api.createApiKey(body)));
    } catch (err) {
      setError(errorMessage(err));
      setSubmitting(false);
    }
  };

  return (
    <form
      className="api-key-form"
      onSubmit={(e) => {
        e.preventDefault();
        void submit();
      }}
    >
      <FormGroup label="Name" labelFor="api-key-name" helperText="Where the key is used, so you can tell keys apart.">
        <InputGroup
          id="api-key-name"
          autoFocus
          maxLength={100}
          value={name}
          placeholder={system ? "Wiki bot" : "Claude Code on my laptop"}
          onChange={(e) => setName(e.currentTarget.value)}
        />
      </FormGroup>

      <FormGroup label="Scopes" helperText="Grant only what the client needs.">
        {scopes.error ? (
          <ErrorCallout error={scopes.error} title="Couldn't load the scopes" />
        ) : !scopes.data ? (
          <Spinner size={20} />
        ) : (
          <div className="scope-groups">
            {groups.map((g) => (
              <fieldset key={g.label} className="scope-group">
                <legend className="scope-group-label">{g.label}</legend>
                {g.scopes.map((s) => (
                  <Checkbox
                    key={s.scope}
                    checked={selected.has(s.scope)}
                    onChange={() => setSelected((sel) => toggle(sel, s.scope))}
                    labelElement={
                      <span className="scope-option">
                        <code>{s.scope}</code>
                        <span className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL}`}>{s.description}</span>
                      </span>
                    }
                  />
                ))}
              </fieldset>
            ))}
          </div>
        )}
      </FormGroup>

      {libraries.length > 0 && (
        <FormGroup label="Libraries">
          <RadioGroup
            selectedValue={restricted ? "some" : "all"}
            onChange={(e) => setRestricted(e.currentTarget.value === "some")}
          >
            <Radio value="all" label={system ? "Every library" : "Every library you can see, now and later"} />
            <Radio value="some" label="Only these:" />
          </RadioGroup>
          {restricted && (
            <div className="key-libraries">
              {libraries.map((l) => (
                <Checkbox
                  key={l.id}
                  label={l.name}
                  checked={libraryIds.has(l.id)}
                  onChange={() => setLibraryIds((ids) => toggle(ids, l.id))}
                />
              ))}
            </div>
          )}
        </FormGroup>
      )}

      <FormGroup label="Expires" helperText="An expired key is refused; revoking one works at once, whenever.">
        <div className="key-expiry">
          <HTMLSelect
            aria-label="Expires"
            value={expiry}
            options={EXPIRY_OPTIONS}
            onChange={(e) => setExpiry(e.currentTarget.value as ExpiryChoice)}
          />
          {expiry === "custom" && (
            <InputGroup
              type="date"
              aria-label="Expiry date"
              value={customDate}
              min={new Date(Date.now() + DAY_MS).toISOString().slice(0, 10)}
              onChange={(e) => setCustomDate(e.currentTarget.value)}
            />
          )}
        </div>
      </FormGroup>

      {error && (
        <Callout intent={Intent.DANGER} icon="error" compact className="api-key-form-error">
          {error}
        </Callout>
      )}

      <div className="api-key-form-actions">
        <Button text="Back" onClick={onCancel} disabled={submitting} />
        <Button
          type="submit"
          intent={Intent.PRIMARY}
          icon="key"
          text="Create key"
          loading={submitting}
          disabled={!scopes.data || !name.trim() || selected.size === 0}
        />
      </div>
    </form>
  );
}
