import { Button, ButtonGroup, Callout, Classes, Dialog, DialogBody, DialogFooter, FormGroup, InputGroup, Intent, TextArea } from "@blueprintjs/core";
import { useEffect, useState } from "react";
import { api, errorMessage } from "../api/client";
import type { Book, BookDetails, BookMetadataFields, CalibreExtras, MetadataSuggestions } from "../api/types";
import { BookCover } from "./BookCover";
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

interface Draft {
  title: string;
  authors: string[];
  tags: string[];
  series: string;
  seriesIndex: string;
  publisher: string;
  pubdate: string;
  languages: string[];
  titleSort: string;
  authorSort: string;
  description: string;
  identifiers: string;
  rating: string;
  addedAt: string;
}

function localDateTime(value: string | null | undefined): string {
  if (!value) return "";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "" : new Date(date.getTime() - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
}

function draftFrom(book: Book, calibre: CalibreExtras | null): Draft {
  return {
    title: book.title,
    authors: book.authors,
    tags: book.tags,
    series: book.series ?? "",
    seriesIndex: book.seriesIndex?.toString() ?? "",
    publisher: book.publisher ?? calibre?.publisher ?? "",
    pubdate: book.pubdate?.slice(0, 10) ?? "",
    languages: book.languages,
    titleSort: calibre?.titleSort ?? "",
    authorSort: calibre?.authorSort ?? "",
    description: calibre?.description ?? "",
    identifiers: Object.entries(calibre?.identifiers ?? {}).map(([type, value]) => `${type}: ${value}`).join("\n"),
    rating: calibre?.rating?.toString() ?? "",
    addedAt: localDateTime(calibre?.addedAt),
  };
}

function identifiers(value: string): Record<string, string> {
  return Object.fromEntries(
    value.split(/\r?\n/).map((line) => line.trim()).filter(Boolean).map((line) => {
      const colon = line.indexOf(":");
      return [line.slice(0, colon).trim(), line.slice(colon + 1).trim()];
    }),
  );
}

function sameValues(a: string[], b: string[]): boolean {
  return a.length === b.length && a.every((value, index) => value === b[index]);
}

function changedFields(initial: Draft, draft: Draft): BookMetadataFields {
  return {
    ...(draft.title.trim() !== initial.title ? { title: draft.title.trim() } : {}),
    ...(!sameValues(draft.authors, initial.authors) ? { authors: draft.authors } : {}),
    ...(!sameValues(draft.tags, initial.tags) ? { tags: draft.tags } : {}),
    ...(draft.series !== initial.series ? { series: draft.series.trim() || null } : {}),
    ...(draft.seriesIndex !== initial.seriesIndex
      ? { seriesIndex: draft.seriesIndex.trim() === "" ? null : Number(draft.seriesIndex) }
      : {}),
    ...(draft.publisher !== initial.publisher ? { publisher: draft.publisher.trim() || null } : {}),
    ...(draft.pubdate !== initial.pubdate ? { pubdate: draft.pubdate || null } : {}),
    ...(!sameValues(draft.languages, initial.languages) ? { languages: draft.languages } : {}),
    ...(draft.titleSort !== initial.titleSort ? { titleSort: draft.titleSort.trim() || null } : {}),
    ...(draft.authorSort !== initial.authorSort ? { authorSort: draft.authorSort.trim() || null } : {}),
    ...(draft.description !== initial.description ? { description: draft.description } : {}),
    ...(draft.identifiers !== initial.identifiers ? { identifiers: identifiers(draft.identifiers) } : {}),
    ...(draft.rating !== initial.rating ? { rating: draft.rating.trim() === "" ? null : Number(draft.rating) } : {}),
    ...(draft.addedAt !== initial.addedAt
      ? { addedAt: draft.addedAt ? new Date(draft.addedAt).toISOString() : null }
      : {}),
  };
}

export function BookMetadataDialog({ isOpen, book, calibre, navigation, onClose, onSaved }: Props) {
  const [initial, setInitial] = useState(() => draftFrom(book, calibre));
  const [draft, setDraft] = useState(() => draftFrom(book, calibre));
  const [suggestions, setSuggestions] = useState<MetadataSuggestions>();
  const [suggestionError, setSuggestionError] = useState<string>();
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [navigating, setNavigating] = useState(false);
  const [confirmClose, setConfirmClose] = useState(false);

  useEffect(() => {
    if (!isOpen) return;
    const next = draftFrom(book, calibre);
    setInitial(next);
    setDraft(next);
    setError(null);
    setSaving(false);
    setNavigating(false);
    setConfirmClose(false);
    setSuggestions(undefined);
    setSuggestionError(undefined);
    const ctrl = new AbortController();
    api.metadataSuggestions(book.libraryId, ctrl.signal)
      .then(setSuggestions)
      .catch((err) => {
        if (!ctrl.signal.aborted) setSuggestionError(errorMessage(err));
      });
    return () => ctrl.abort();
  }, [book, calibre, isOpen]);

  const fields = changedFields(initial, draft);
  const validSeriesIndex = fields.seriesIndex == null || (Number.isFinite(fields.seriesIndex) && fields.seriesIndex >= 0);
  const validRating = fields.rating == null || (Number.isFinite(fields.rating) && fields.rating >= 0 && fields.rating <= 5 && fields.rating * 2 % 1 === 0);
  const validAddedAt = fields.addedAt === undefined ||
    (typeof fields.addedAt === "string" && Number.isFinite(new Date(fields.addedAt).getTime()));
  const identifierLines = draft.identifiers.split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
  const validIdentifiers = fields.identifiers === undefined ||
    identifierLines.every((line) => /^[A-Za-z0-9_.-]+:\s*[^,]+$/.test(line));

  const save = async (close: boolean): Promise<boolean> => {
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
      onSaved(await api.updateBookMetadata(book.id, fields), close);
      setConfirmClose(false);
      return true;
    } catch (err) {
      setError(errorMessage(err));
      return false;
    } finally {
      setSaving(false);
    }
  };

  const requestClose = () => {
    if (saving || navigating) return;
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
                  <Button type="button" text="Keep editing" onClick={() => setConfirmClose(false)} disabled={saving} />
                  <Button type="button" text="Discard" onClick={onClose} disabled={saving} />
                  <Button type="button" intent={Intent.PRIMARY} text="Save" loading={saving} onClick={() => void save(true)} disabled={saving} />
                </>
              }
            />
          </>
        ) : (
          <>
          <DialogBody>
          <div className="metadata-editor-layout">
            <div className="metadata-editor-cover">
              <BookCover bookId={book.id} hasCover={book.hasCover} width={176} title={book.title} natural />
              <p className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL}`}>Cover editing comes later.</p>
            </div>
            <div className="metadata-editor-fields">
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
                disabled={!navigation.previous || saving || navigating}
                onClick={() => void step(-1)}
              />
              <Button
                type="button"
                icon="chevron-right"
                aria-label="Next book"
                title="Save changes and open the next book"
                disabled={!navigation.next || saving || navigating}
                onClick={() => void step(1)}
              />
            </ButtonGroup>
          )}
          </DialogBody>
          <DialogFooter
            actions={
              <>
              <Button type="button" text="Cancel" onClick={requestClose} disabled={saving || navigating} />
              <Button
                type="submit"
                intent={Intent.PRIMARY}
                icon="floppy-disk"
                text="Save"
                loading={saving}
                disabled={saving || navigating || Object.keys(fields).length === 0 || !validSeriesIndex || !validRating || !validAddedAt || !validIdentifiers}
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
