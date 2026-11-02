import { Button, Classes, FormGroup, HTMLSelect, NumericInput, Popover, Spinner, Tooltip } from "@blueprintjs/core";
import { useEffect, useRef, useState } from "react";
import { api } from "../api/client";
import type { ServerSettings } from "../api/types";
import { useApp } from "../AppContext";
import { DEFAULT_TOP_K, MAX_TOP_K } from "../hooks/useScope";
import { toastError } from "./toaster";

/** Navbar gear: retrieval/answer settings shared by Ask and Search (stored in the scope, persisted). */
export function SettingsMenu() {
  const { scope, setScope, system } = useApp();
  const models = system?.availableChatModels ?? [];
  const modelOptions = [
    { value: "", label: system ? `Default (${system.chatModel})` : "Default model" },
    ...models.filter((m) => m !== system?.chatModel).map((m) => ({ value: m, label: m })),
  ];
  // keep a stored model visible even if it's no longer installed
  if (scope.model && !modelOptions.some((o) => o.value === scope.model)) {
    modelOptions.push({ value: scope.model, label: `${scope.model} (unavailable)` });
  }
  const isDefault = scope.model === "" && scope.topK === DEFAULT_TOP_K;

  const content = (
    <div className="settings-menu">
      <div className={`${Classes.HEADING} settings-menu-title`}>Settings</div>
      <FormGroup label="Chat model" labelFor="settings-model" helperText="Writes the answers on the Ask tab.">
        <HTMLSelect
          id="settings-model"
          fill
          value={scope.model}
          options={modelOptions}
          onChange={(e) => setScope((s) => ({ ...s, model: e.currentTarget.value }))}
        />
      </FormGroup>
      <FormGroup
        label="Passages to retrieve"
        labelFor="settings-topk"
        helperText={`Per search, and per answer as context (1–${MAX_TOP_K}).`}
      >
        <NumericInput
          id="settings-topk"
          min={1}
          max={MAX_TOP_K}
          value={scope.topK}
          leftIcon="layers"
          clampValueOnBlur
          minorStepSize={null}
          fill
          onValueChange={(v) => {
            if (Number.isFinite(v) && v >= 1 && v <= MAX_TOP_K) setScope((s) => ({ ...s, topK: Math.round(v) }));
          }}
        />
      </FormGroup>
      <div className="settings-menu-footer">
        <Button
          variant="minimal"
          size="small"
          icon="reset"
          text="Reset to defaults"
          disabled={isDefault}
          onClick={() => setScope((s) => ({ ...s, model: "", topK: DEFAULT_TOP_K }))}
        />
      </div>
      <IndexingSettings />
    </div>
  );

  return (
    <Popover content={content} placement="bottom-end" popoverClassName="settings-popover">
      <Tooltip content="Settings" placement="bottom-end">
        <Button variant="minimal" icon="cog" aria-label="Settings" className="settings-button" />
      </Tooltip>
    </Popover>
  );
}

const SAVE_DELAY_MS = 500;

/**
 * Server-side indexing settings (stored on the server, for everyone using it). Loaded when the menu opens; a change is
 * saved shortly after the last step, and applies to a running indexing at once.
 */
function IndexingSettings() {
  const [server, setServer] = useState<ServerSettings | null>(null);
  const [value, setValue] = useState<number | null>(null);
  const [saving, setSaving] = useState(false);
  const [failed, setFailed] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  useEffect(() => {
    const ctrl = new AbortController();
    api
      .settings(ctrl.signal)
      .then((s) => {
        setServer(s);
        setValue(s.parallelBooks);
      })
      .catch(() => {
        if (!ctrl.signal.aborted) setFailed(true);
      });
    return () => {
      ctrl.abort();
      clearTimeout(timer.current);
    };
  }, []);

  const save = (n: number) => {
    clearTimeout(timer.current);
    timer.current = setTimeout(async () => {
      setSaving(true);
      try {
        const s = await api.updateSettings({ parallelBooks: n });
        setServer(s);
        setValue(s.parallelBooks);
      } catch (err) {
        toastError("Couldn't save the setting", err);
        if (server) setValue(server.parallelBooks);
      } finally {
        setSaving(false);
      }
    }, SAVE_DELAY_MS);
  };

  return (
    <>
      <div className={`${Classes.TEXT_MUTED} settings-menu-section`}>Indexing · server</div>
      <FormGroup
        label="Books indexed in parallel"
        labelFor="settings-parallel-books"
        helperText={
          failed
            ? "Couldn't load the server's settings."
            : server
              ? `Books extracted at the same time (1–${server.parallelBooksMax}, default ${server.parallelBooksDefault}). ` +
                "More uses more CPU; embedding stays one queue on the GPU. Applies to a running indexing at once."
              : "Loading…"
        }
      >
        <NumericInput
          id="settings-parallel-books"
          min={1}
          max={server?.parallelBooksMax ?? 1}
          value={value ?? ""}
          disabled={!server}
          leftIcon="layers"
          rightElement={saving ? <Spinner size={14} className="input-spinner" /> : undefined}
          clampValueOnBlur
          minorStepSize={null}
          fill
          onValueChange={(v) => {
            if (!server || !Number.isFinite(v)) return;
            const n = Math.round(v);
            if (n < 1 || n > server.parallelBooksMax) return;
            setValue(n);
            if (n !== server.parallelBooks) save(n);
          }}
        />
      </FormGroup>
    </>
  );
}
