import { Button, Classes, FormGroup, HTMLSelect, NumericInput, Popover, Tooltip } from "@blueprintjs/core";
import { useApp } from "../AppContext";
import { DEFAULT_TOP_K, MAX_TOP_K } from "../hooks/useScope";

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
