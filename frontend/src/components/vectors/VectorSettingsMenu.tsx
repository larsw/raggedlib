import { Button, Classes, FormGroup, Intent, NumericInput, Popover, Tooltip } from "@blueprintjs/core";
import { useState } from "react";
import type { LayoutParams, VectorMethod } from "../../api/types";
import {
  DEFAULT_VECTOR_SETTINGS,
  sameVectorSettings,
  validSetting,
  VECTOR_SETTING_LIMITS,
  type VectorSettingKey,
  type VectorSettings,
  type VectorSettingsDraft,
} from "../../lib/vectorSettings";

interface FieldMeta {
  key: VectorSettingKey;
  label: string;
  help: string;
}

const UMAP_FIELDS: FieldMeta[] = [
  { key: "neighbors", label: "Neighbours", help: "Larger keeps more of the global shape; smaller shows finer local groups." },
  { key: "minDist", label: "Minimum distance", help: "How tightly points may pack: 0 makes dense clumps, larger spreads them out." },
  { key: "epochs", label: "Epochs", help: "How long the layout is optimised. More settles further but takes longer." },
];

const TSNE_FIELDS: FieldMeta[] = [
  { key: "perplexity", label: "Perplexity", help: "About how many neighbours each point balances. Larger shows broader structure." },
  { key: "iterations", label: "Iterations", help: "How long the layout is optimised. Each one is O(n²), so more is slower." },
];

const TOPIC_FIELDS: FieldMeta[] = [
  { key: "minClusterSize", label: "Minimum topic size", help: "The smallest group that counts as a topic. Blank: about 2.5 % of the sample (5–50)." },
  { key: "minSamples", label: "Minimum samples", help: "Higher is stricter: fewer, denser topics and more unclustered points. Blank: the topic size." },
];

const METHOD_NAMES: Record<VectorMethod, string> = { pca: "PCA", umap: "UMAP", tsne: "t-SNE" };

type Draft = Record<VectorSettingKey, string>;

const toDraft = (s: VectorSettings): Draft =>
  Object.fromEntries(Object.entries(s).map(([k, v]) => [k, v == null ? "" : String(v)])) as Draft;

const parse = (text: string): number | null => (text.trim() === "" ? null : Number(text));

/**
 * The Vectors toolbar's gear: the active method's parameters and topic clustering's, edited as a draft and applied
 * together, as every change recomputes the layout (seconds for a large sample).
 */
export function VectorSettingsMenu({
  method,
  settings,
  onApply,
  used,
  usedTopics,
}: {
  method: VectorMethod;
  settings: VectorSettings;
  onApply: (s: VectorSettings) => void;
  /** the parameters the current layout was computed with (capped for a small sample) */
  used?: LayoutParams;
  /** the HDBSCAN parameters the current topics were computed with (auto resolved) */
  usedTopics?: { minClusterSize: number; minSamples: number };
}) {
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState<Draft>(() => toDraft(settings));

  const parsed = Object.fromEntries(Object.entries(draft).map(([k, v]) => [k, parse(v)])) as VectorSettingsDraft;
  const invalid = (key: VectorSettingKey) => !validSetting(key, parsed[key]);
  const anyInvalid = (Object.keys(draft) as VectorSettingKey[]).some(invalid);
  const changed = !anyInvalid && !sameVectorSettings(parsed, settings);
  const customised = !sameVectorSettings(settings, DEFAULT_VECTOR_SETTINGS);

  const apply = () => {
    if (anyInvalid) return;
    onApply(parsed as VectorSettings); // valid: every required setting has a number
    setOpen(false);
  };

  /** what the server made of a setting, when that differs from what was asked for */
  const note = (key: VectorSettingKey): string | undefined => {
    if (key === "perplexity" && used?.perplexity != null && used.perplexity < settings.perplexity) {
      return `Capped at ${used.perplexity.toFixed(1)} for this sample.`;
    }
    if (key === "neighbors" && used?.neighbors != null && used.neighbors < settings.neighbors) {
      return `Capped at ${used.neighbors} for this sample.`;
    }
    return undefined;
  };

  const placeholder = (key: VectorSettingKey): string | undefined => {
    if (key === "minClusterSize") return usedTopics ? `auto (${usedTopics.minClusterSize})` : "auto";
    if (key === "minSamples") return "same as topic size";
    return undefined;
  };

  const field = ({ key, label, help }: FieldMeta) => {
    const limit = VECTOR_SETTING_LIMITS[key];
    const id = `vector-setting-${key}`;
    const bad = invalid(key);
    return (
      <FormGroup
        key={key}
        label={label}
        labelFor={id}
        labelInfo={`${limit.min}–${limit.max}`}
        helperText={bad ? `Between ${limit.min} and ${limit.max}${limit.integer ? ", a whole number" : ""}.` : (note(key) ?? help)}
        intent={bad ? Intent.DANGER : Intent.NONE}
      >
        <NumericInput
          id={id}
          fill
          size="small"
          value={draft[key]}
          min={limit.min}
          max={limit.max}
          stepSize={limit.step}
          minorStepSize={limit.integer ? null : 0.01}
          majorStepSize={limit.step * 10}
          placeholder={placeholder(key)}
          intent={bad ? Intent.DANGER : Intent.NONE}
          onValueChange={(_, text) => setDraft((d) => ({ ...d, [key]: text }))}
          onKeyDown={(e) => {
            if (e.key === "Enter") apply();
          }}
        />
      </FormGroup>
    );
  };

  const methodFields = method === "umap" ? UMAP_FIELDS : method === "tsne" ? TSNE_FIELDS : [];

  const content = (
    <div className="settings-menu vector-settings">
      <div className={`${Classes.HEADING} settings-menu-title`}>{METHOD_NAMES[method]} settings</div>
      {methodFields.length > 0 ? (
        methodFields.map(field)
      ) : (
        <p className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL}`}>PCA has nothing to tune: it is a fixed linear projection.</p>
      )}
      <div className="settings-menu-section">Topics</div>
      {TOPIC_FIELDS.map(field)}
      <div className="settings-menu-footer vector-settings-footer">
        <Button
          variant="minimal"
          size="small"
          icon="reset"
          text="Defaults"
          disabled={sameVectorSettings(parsed, DEFAULT_VECTOR_SETTINGS) && !anyInvalid}
          onClick={() => setDraft(toDraft(DEFAULT_VECTOR_SETTINGS))}
        />
        <span className="spacer" />
        <Button size="small" text="Cancel" variant="minimal" onClick={() => setOpen(false)} />
        <Button size="small" intent={Intent.PRIMARY} text="Apply" disabled={!changed} onClick={apply} />
      </div>
    </div>
  );

  return (
    <Popover
      content={content}
      isOpen={open}
      placement="bottom-end"
      popoverClassName="settings-popover"
      onInteraction={(next) => {
        if (next) setDraft(toDraft(settings)); // start from what's applied, not an abandoned draft
        setOpen(next);
      }}
    >
      <Tooltip content={customised ? "Projection and topic settings (changed from the defaults)" : "Projection and topic settings"} placement="bottom">
        <Button
          icon="cog"
          aria-label="Projection and topic settings"
          intent={customised ? Intent.PRIMARY : Intent.NONE}
          active={open}
        />
      </Tooltip>
    </Popover>
  );
}
