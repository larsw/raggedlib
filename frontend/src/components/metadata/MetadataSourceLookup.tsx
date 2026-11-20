import { Button, Callout, Checkbox, Classes, FormGroup, HTMLSelect, Intent } from "@blueprintjs/core";
import { useEffect, useRef, useState } from "react";
import { api, ApiError, errorMessage } from "../../api/client";
import type { MetadataCandidate } from "../../api/types";
import {
  candidateValues,
  type Draft,
  draftValues,
  fieldsToSelect,
  hasValue,
  METADATA_LOOKUP_FIELDS,
  METADATA_LOOKUP_LABELS,
  type MetadataLookupField,
  metadataValueText,
} from "./metadataDraft";

interface Props {
  bookId: number;
  draft: Draft;
  /** the rest of the editor is busy */
  disabled: boolean;
  /** copy these values into the draft (nothing is saved yet) */
  onApply: (values: Partial<Record<MetadataLookupField, unknown>>) => void;
  onLoadingChange: (loading: boolean) => void;
}

/** Looks the book up in Calibre's metadata sources, and copies the chosen fields of one candidate into the draft. */
export function MetadataSourceLookup({ bookId, draft, disabled, onApply, onLoadingChange }: Props) {
  const [candidates, setCandidates] = useState<MetadataCandidate[]>([]);
  const [selectedIndex, setSelectedIndex] = useState(0);
  const [selectedFields, setSelectedFields] = useState<Set<MetadataLookupField>>(new Set());
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();
  const [noResults, setNoResults] = useState(false);
  const abort = useRef<AbortController | null>(null);
  const draftRef = useRef(draft);
  draftRef.current = draft;

  useEffect(() => onLoadingChange(loading), [loading, onLoadingChange]);
  useEffect(() => () => {
    abort.current?.abort();
    onLoadingChange(false);
  }, [onLoadingChange]);

  const lookup = async () => {
    abort.current?.abort();
    const ctrl = new AbortController();
    abort.current = ctrl;
    setLoading(true);
    setError(undefined);
    setNoResults(false);
    setCandidates([]);
    setSelectedFields(new Set());
    try {
      const found = await api.metadataCandidates(bookId, ctrl.signal);
      if (ctrl.signal.aborted) return;
      setCandidates(found);
      if (found.length === 0) {
        setNoResults(true);
        setError("No metadata candidates were found. Try adding an ISBN or checking the title and authors.");
      } else if (found[0]) {
        setSelectedIndex(0);
        setSelectedFields(fieldsToSelect(found[0], draftRef.current));
      }
    } catch (err) {
      if (!ctrl.signal.aborted) {
        setError(err instanceof ApiError && err.status === 504 ? "The metadata lookup timed out. Try again." : errorMessage(err));
      }
    } finally {
      if (!ctrl.signal.aborted) setLoading(false);
    }
  };

  const select = (index: number) => {
    setSelectedIndex(index);
    const candidate = candidates[index];
    if (candidate) setSelectedFields(fieldsToSelect(candidate, draftRef.current));
  };

  const candidate = candidates[selectedIndex];
  const incoming = candidate ? candidateValues(candidate) : {};
  const current = draftValues(draft);
  const availableFields = METADATA_LOOKUP_FIELDS.filter((field) => hasValue(incoming[field]));

  return (
    <section className="metadata-source-lookup" aria-label="Metadata source lookup">
      <Callout intent={Intent.PRIMARY} icon="info-sign" compact>
        Your title, authors and identifiers, including ISBN, are sent to Google Books, Amazon, Open Library
        and other configured Calibre sources. Review each field before applying it; nothing is saved until
        you choose Save.
      </Callout>
      <Button
        type="button"
        small
        icon="cloud-download"
        text="Look up metadata"
        loading={loading}
        disabled={disabled || loading}
        onClick={() => void lookup()}
      />
      {loading && <span className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL}`}>Searching metadata sources…</span>}
      {error && (
        <Callout intent={noResults ? Intent.WARNING : Intent.DANGER} icon={noResults ? "warning-sign" : "error"} compact>
          {error}
        </Callout>
      )}
      {candidates.length > 0 && (
        <div className="metadata-source-candidates">
          <FormGroup label="Candidate edition" labelFor="metadata-candidate">
            <HTMLSelect
              id="metadata-candidate"
              fill
              value={String(selectedIndex)}
              options={candidates.map((item, index) => ({
                value: String(index),
                label: `${item.title || "Untitled"} · ${item.source}`,
              }))}
              onChange={(event) => select(Number(event.currentTarget.value))}
            />
          </FormGroup>
          {candidate && (
            <>
              <p className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL}`}>
                Source: {candidate.source}. Select the fields to copy into this draft.
              </p>
              <div className="metadata-source-comparison-wrap">
                <table className="metadata-source-comparison">
                  <thead>
                    <tr><th scope="col">Field</th><th scope="col">Current draft</th><th scope="col">Source</th></tr>
                  </thead>
                  <tbody>
                    {availableFields.map((field) => (
                      <tr key={field}>
                        <th scope="row">
                          <Checkbox
                            checked={selectedFields.has(field)}
                            label={METADATA_LOOKUP_LABELS[field]}
                            onChange={() => setSelectedFields((selected) => {
                              const next = new Set(selected);
                              if (next.has(field)) next.delete(field);
                              else next.add(field);
                              return next;
                            })}
                          />
                        </th>
                        <td>{metadataValueText(current[field])}</td>
                        <td>{metadataValueText(incoming[field])}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                {availableFields.length === 0 && (
                  <p className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL}`}>
                    This candidate has no supported fields to apply.
                  </p>
                )}
              </div>
              <Button
                type="button"
                small
                intent={Intent.PRIMARY}
                text={`Apply ${selectedFields.size} selected field${selectedFields.size === 1 ? "" : "s"}`}
                disabled={selectedFields.size === 0 || disabled}
                onClick={() => {
                  onApply(Object.fromEntries([...selectedFields].map((field) => [field, incoming[field]])));
                  setSelectedFields(new Set());
                }}
              />
            </>
          )}
        </div>
      )}
    </section>
  );
}
