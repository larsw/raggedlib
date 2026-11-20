import { Button, ButtonGroup, Callout, Classes, Dialog, DialogBody, DialogFooter, FormGroup, InputGroup, Intent, TextArea } from "@blueprintjs/core";
import { useCallback, useEffect, useState } from "react";
import { api, ApiError, errorMessage } from "../api/client";
import type { Book, BookDetails, BookMetadataFields, CalibreExtras, MetadataSuggestions } from "../api/types";
import { MetadataCoverPanel } from "./metadata/MetadataCoverPanel";
import {
  changedFields,
  draftFrom,
  draftWithValue,
  isMetadataField,
  METADATA_FIELD_LABELS,
  metadataConflicts,
  metadataValueText,
  metadataValuesFrom,
  originalFields,
} from "./metadata/metadataDraft";
import { MetadataFormatsPanel } from "./metadata/MetadataFormatsPanel";
import { MetadataSourceLookup } from "./metadata/MetadataSourceLookup";
import { MetadataValueSelect } from "./MetadataValueSelect";

interface Props {
  isOpen: boolean;
  book: Book;
  calibre: CalibreExtras | null;
  navigation?: {
    previous: boolean;
    next: boolean;
    step: (direction: -1 | 1) => Promise<void>;
  };
  onClose: () => void;
  onSaved: (details: BookDetails, close: boolean) => void;
}

export function BookMetadataDialog({ isOpen, book, calibre, navigation, onClose, onSaved }: Props) {
  const [initial, setInitial] = useState(() => draftFrom(book, calibre));
  const [draft, setDraft] = useState(() => draftFrom(book, calibre));
  const [initialValues, setInitialValues] = useState(() => metadataValuesFrom(book, calibre));
  const [conflicts, setConflicts] = useState<Partial<Record<keyof BookMetadataFields, unknown>>>({});
  const [suggestions, setSuggestions] = useState<MetadataSuggestions>();
  const [suggestionError, setSuggestionError] = useState<string>();
  const [metadataLookupEnabled, setMetadataLookupEnabled] = useState(false);
  const [metadataSettingsError, setMetadataSettingsError] = useState<string>();
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [navigating, setNavigating] = useState(false);
  const [coverBusy, setCoverBusy] = useState(false);
  const [formatBusy, setFormatBusy] = useState(false);
  const [metadataLookupLoading, setMetadataLookupLoading] = useState(false);
  const [confirmClose, setConfirmClose] = useState(false);
  const fileBusy = coverBusy || formatBusy;
  // the cover and format panels save on their own; the editor shows what they return
  const savedFromPanel = useCallback((details: BookDetails) => onSaved(details, false), [onSaved]);

  useEffect(() => {
    if (!isOpen) return;
    const next = draftFrom(book, calibre);
    setInitial(next);
    setDraft(next);
    setInitialValues(metadataValuesFrom(book, calibre));
    setConflicts({});
    setError(null);
    setSaving(false);
    setNavigating(false);
    setConfirmClose(false);
    setSuggestions(undefined);
    setSuggestionError(undefined);
    setMetadataLookupEnabled(false);
    setMetadataSettingsError(undefined);
    const ctrl = new AbortController();
    api.metadataSuggestions(book.libraryId, ctrl.signal)
      .then(setSuggestions)
      .catch((err) => {
        if (!ctrl.signal.aborted) setSuggestionError(errorMessage(err));
      });
    api.settings(ctrl.signal)
      .then((settings) => setMetadataLookupEnabled(settings.metadataLookupEnabled))
      .catch((err) => {
        if (!ctrl.signal.aborted) setMetadataSettingsError(errorMessage(err));
      });
    return () => {
      ctrl.abort();
    };
  }, [book.id, isOpen]);

  const fields = changedFields(initial, draft);
  const validSeriesIndex = fields.seriesIndex == null || (Number.isFinite(fields.seriesIndex) && fields.seriesIndex >= 0);
  const validRating = fields.rating == null || (Number.isFinite(fields.rating) && fields.rating >= 0 && fields.rating <= 5 && fields.rating * 2 % 1 === 0);
  const validAddedAt = fields.addedAt === undefined ||
    (typeof fields.addedAt === "string" && Number.isFinite(new Date(fields.addedAt).getTime()));
  const identifierLines = draft.identifiers.split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
  const validIdentifiers = fields.identifiers === undefined ||
    identifierLines.every((line) => /^[A-Za-z0-9_.-]+:\s*[^,]+$/.test(line));

  const save = async (close: boolean): Promise<boolean> => {
    if (fileBusy || metadataLookupLoading) return false;
    if (Object.keys(conflicts).length > 0) {
      setError("Resolve each metadata conflict before saving.");
      return false;
    }
    if (!draft.title.trim()) {
      setError("A book needs a title.");
      return false;
    }
    if (!validSeriesIndex) {
      setError("Series number must be a non-negative number.");
      return false;
    }
    if (!validRating) {
      setError("Rating must be between 0 and 5 in half-star increments.");
      return false;
    }
    if (!validAddedAt) {
      setError("Date added must be valid.");
      return false;
    }
    if (!validIdentifiers) {
      setError("Enter identifiers as one type: value pair per line; values must not contain commas.");
      return false;
    }
    if (Object.keys(fields).length === 0) {
      if (close) onClose();
      return true;
    }
    setSaving(true);
    setError(null);
    try {
      const updated = await api.updateBookMetadata(book.id, fields, originalFields(fields, initialValues));
      onSaved(updated, close);
      const next = draftFrom(updated.book, updated.calibre);
      setInitial(next);
      setDraft(next);
      setInitialValues(metadataValuesFrom(updated.book, updated.calibre));
      setConflicts({});
      setConfirmClose(false);
      return true;
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        const nextConflicts = metadataConflicts(err.details);
        if (nextConflicts && Object.keys(nextConflicts).length > 0) {
          setConflicts(nextConflicts);
          setError(err.message);
          return false;
        }
      }
      setError(errorMessage(err));
      return false;
    } finally {
      setSaving(false);
    }
  };

  const resolveConflict = (field: keyof BookMetadataFields, useMine: boolean) => {
    const currentValue = conflicts[field];
    setInitialValues((previous) => ({ ...previous, [field]: currentValue }));
    if (!useMine) {
      setInitial((previous) => draftWithValue(previous, field, currentValue));
      setDraft((previous) => draftWithValue(previous, field, currentValue));
    }
    setConflicts((previous) => {
      const next = { ...previous };
      delete next[field];
      return next;
    });
    setError(null);
  };

  const requestClose = () => {
    if (saving || navigating || fileBusy || metadataLookupLoading) return;
    if (confirmClose) {
      setConfirmClose(false);
    } else if (Object.keys(fields).length > 0) {
      setConfirmClose(true);
    } else {
      onClose();
    }
  };

  const step = async (direction: -1 | 1) => {
    if (!navigation) return;
    setNavigating(true);
    setError(null);
    try {
      if (Object.keys(fields).length > 0 && !(await save(false))) return;
      await navigation.step(direction);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setNavigating(false);
    }
  };

  return (
    <Dialog isOpen={isOpen} onClose={requestClose} title="Edit metadata" icon="edit" className="metadata-editor-dialog">
      <form
        onSubmit={(e) => {
          e.preventDefault();
          if (!confirmClose) void save(true);
        }}
      >
        {confirmClose ? (
          <>
            <DialogBody>
              <p>Save changes to "{book.title}" before closing?</p>
              {error && <Callout intent={Intent.DANGER} icon="error" compact>{error}</Callout>}
            </DialogBody>
            <DialogFooter
              actions={
                <>
                  <Button type="button" text="Keep editing" onClick={() => setConfirmClose(false)} disabled={saving || fileBusy || metadataLookupLoading} />
                  <Button type="button" text="Discard" onClick={onClose} disabled={saving || fileBusy || metadataLookupLoading} />
                  <Button type="button" intent={Intent.PRIMARY} text="Save" loading={saving} onClick={() => void save(true)} disabled={saving || fileBusy || metadataLookupLoading || Object.keys(conflicts).length > 0} />
                </>
              }
            />
          </>
        ) : (
          <>
          <DialogBody>
          <div className="metadata-editor-layout">
            <div className="metadata-editor-cover">
              <MetadataCoverPanel
                key={`cover-${book.id}`}
                book={book}
                lookupEnabled={metadataLookupEnabled}
                disabled={saving || navigating || fileBusy}
                onSaved={savedFromPanel}
                onBusyChange={setCoverBusy}
              />
              <MetadataFormatsPanel
                key={`formats-${book.id}`}
                book={book}
                calibre={calibre}
                disabled={saving || navigating || fileBusy}
                onSaved={savedFromPanel}
                onBusyChange={setFormatBusy}
              />
            </div>
            <div className="metadata-editor-fields">
              {metadataSettingsError ? (
                <Callout intent={Intent.WARNING} icon="warning-sign" compact>
                  Metadata lookup is unavailable because the server setting could not be loaded: {metadataSettingsError}
                </Callout>
              ) : metadataLookupEnabled && (
                <MetadataSourceLookup
                  key={`lookup-${book.id}`}
                  bookId={book.id}
                  draft={draft}
                  disabled={saving || navigating || fileBusy}
                  onLoadingChange={setMetadataLookupLoading}
                  onApply={(values) => setDraft((current) => Object.entries(values).reduce(
                    (next, [field, value]) => draftWithValue(next, field as keyof BookMetadataFields, value),
                    current,
                  ))}
                />
              )}
              <FormGroup label="Title" labelFor="metadata-title">
                <InputGroup id="metadata-title" autoFocus value={draft.title} onChange={(e) => setDraft({ ...draft, title: e.currentTarget.value })} />
              </FormGroup>
              <FormGroup label="Authors" labelFor="metadata-authors">
                <MetadataValueSelect inputId="metadata-authors" label="Authors" values={draft.authors} suggestions={suggestions?.authors ?? []}
                  onChange={(authors) => setDraft({ ...draft, authors })} />
              </FormGroup>
              <FormGroup label="Tags" labelFor="metadata-tags">
                <MetadataValueSelect inputId="metadata-tags" label="Tags" values={draft.tags} suggestions={suggestions?.tags ?? []}
                  onChange={(tags) => setDraft({ ...draft, tags })} />
              </FormGroup>
              <div className="metadata-editor-row">
                <FormGroup label="Series" labelFor="metadata-series">
                  <InputGroup id="metadata-series" list="metadata-series-values" value={draft.series} onChange={(e) => setDraft({ ...draft, series: e.currentTarget.value })} />
                  <datalist id="metadata-series-values">
                    {(suggestions?.series ?? []).map((value) => <option key={value} value={value} />)}
                  </datalist>
                </FormGroup>
                <FormGroup label="Series number" labelFor="metadata-series-index">
                  <InputGroup
                    id="metadata-series-index"
                    type="number"
                    min="0"
                    step="any"
                    value={draft.seriesIndex}
                    onChange={(e) => setDraft({ ...draft, seriesIndex: e.currentTarget.value })}
                  />
                </FormGroup>
              </div>
              <FormGroup label="Publisher" labelFor="metadata-publisher">
                <InputGroup id="metadata-publisher" list="metadata-publisher-values" value={draft.publisher} onChange={(e) => setDraft({ ...draft, publisher: e.currentTarget.value })} />
                <datalist id="metadata-publisher-values">
                  {(suggestions?.publishers ?? []).map((value) => <option key={value} value={value} />)}
                </datalist>
              </FormGroup>
              <div className="metadata-editor-row">
                <FormGroup label="Published" labelFor="metadata-pubdate">
                  <InputGroup id="metadata-pubdate" type="date" value={draft.pubdate} onChange={(e) => setDraft({ ...draft, pubdate: e.currentTarget.value })} />
                </FormGroup>
                <FormGroup label="Languages" labelFor="metadata-languages" helperText="Choose a language by name; new entries use a 2- or 3-letter ISO code.">
                  <MetadataValueSelect inputId="metadata-languages" label="Languages" values={draft.languages} suggestions={suggestions?.languages ?? []}
                    onChange={(languages) => setDraft({ ...draft, languages })} language />
                </FormGroup>
              </div>
              <div className="metadata-editor-row">
                <FormGroup label="Title sort" labelFor="metadata-title-sort">
                  <InputGroup id="metadata-title-sort" value={draft.titleSort} onChange={(e) => setDraft({ ...draft, titleSort: e.currentTarget.value })} />
                  <Button type="button" small variant="minimal" text="Reset to automatic" disabled={!draft.titleSort} onClick={() => setDraft({ ...draft, titleSort: "" })} />
                </FormGroup>
                <FormGroup label="Author sort" labelFor="metadata-author-sort">
                  <InputGroup id="metadata-author-sort" value={draft.authorSort} onChange={(e) => setDraft({ ...draft, authorSort: e.currentTarget.value })} />
                  <Button type="button" small variant="minimal" text="Reset to automatic" disabled={!draft.authorSort} onClick={() => setDraft({ ...draft, authorSort: "" })} />
                </FormGroup>
              </div>
              <FormGroup label="Identifiers" labelFor="metadata-identifiers" helperText="One identifier per line, for example isbn: 978...">
                <TextArea id="metadata-identifiers" fill rows={3} value={draft.identifiers}
                  onChange={(e) => setDraft({ ...draft, identifiers: e.currentTarget.value })} />
              </FormGroup>
              <div className="metadata-editor-row">
                <FormGroup label="Rating" labelFor="metadata-rating" helperText="0 to 5 stars, in half-star increments.">
                  <InputGroup id="metadata-rating" type="number" min="0" max="5" step="0.5" value={draft.rating}
                    onChange={(e) => setDraft({ ...draft, rating: e.currentTarget.value })} />
                </FormGroup>
                <FormGroup label="Date added" labelFor="metadata-added-at">
                  <InputGroup id="metadata-added-at" type="datetime-local" value={draft.addedAt}
                    onChange={(e) => setDraft({ ...draft, addedAt: e.currentTarget.value })} />
                </FormGroup>
              </div>
              <FormGroup label="Description" labelFor="metadata-description">
                <TextArea id="metadata-description" fill rows={6} value={draft.description}
                  onChange={(e) => setDraft({ ...draft, description: e.currentTarget.value })} />
              </FormGroup>
              {Object.entries(conflicts).map(([field, currentValue]) => {
                if (!isMetadataField(field)) return null;
                return (
                  <Callout key={field} intent={Intent.WARNING} icon="warning-sign" compact>
                    <p>
                      <strong>{METADATA_FIELD_LABELS[field]}</strong> changed in Calibre.
                      Current value: {metadataValueText(currentValue)}. Your value: {metadataValueText(fields[field])}.
                    </p>
                    <ButtonGroup>
                      <Button type="button" small text="Use mine" onClick={() => resolveConflict(field, true)} />
                      <Button type="button" small text="Use theirs" onClick={() => resolveConflict(field, false)} />
                    </ButtonGroup>
                  </Callout>
                );
              })}
              {calibre?.descriptionHasFormatting && fields.description !== undefined && (
                <Callout intent={Intent.WARNING} icon="warning-sign" compact>
                  Saving this description will replace its existing formatting with simple paragraphs and line breaks.
                </Callout>
              )}
              <p className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL} metadata-editor-hint`}>
                Only changed fields are sent to Calibre. Changes made to other fields outside raggedlib are preserved.
              </p>
              {suggestionError && (
                <Callout intent={Intent.WARNING} icon="warning-sign" compact>
                  Library suggestions could not be loaded: {suggestionError}
                </Callout>
              )}
              {error && (
                <Callout intent={Intent.DANGER} icon="error" compact>
                  {error}
                </Callout>
              )}
            </div>
          </div>
          {navigation && (
            <ButtonGroup className="metadata-editor-navigation">
              <Button
                type="button"
                icon="chevron-left"
                aria-label="Previous book"
                title="Save changes and open the previous book"
                disabled={!navigation.previous || saving || navigating || fileBusy || metadataLookupLoading || Object.keys(conflicts).length > 0}
                onClick={() => void step(-1)}
              />
              <Button
                type="button"
                icon="chevron-right"
                aria-label="Next book"
                title="Save changes and open the next book"
                disabled={!navigation.next || saving || navigating || fileBusy || metadataLookupLoading || Object.keys(conflicts).length > 0}
                onClick={() => void step(1)}
              />
            </ButtonGroup>
          )}
          </DialogBody>
          <DialogFooter
            actions={
              <>
              <Button type="button" text="Cancel" onClick={requestClose} disabled={saving || navigating || fileBusy || metadataLookupLoading} />
              <Button
                type="submit"
                intent={Intent.PRIMARY}
                icon="floppy-disk"
                text="Save"
                loading={saving}
                disabled={saving || navigating || fileBusy || metadataLookupLoading || Object.keys(conflicts).length > 0 || Object.keys(fields).length === 0 || !validSeriesIndex || !validRating || !validAddedAt || !validIdentifiers}
              />
            </>
            }
          />
          </>
        )}
      </form>
    </Dialog>
  );
}
