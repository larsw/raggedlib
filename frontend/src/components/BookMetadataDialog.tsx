import { Button, Callout, Classes, Dialog, DialogBody, DialogFooter, FormGroup, InputGroup, Intent, TextArea } from "@blueprintjs/core";
import { useEffect, useState } from "react";
import { api, errorMessage } from "../api/client";
import type { Book, BookDetails, BookMetadataFields } from "../api/types";
import { BookCover } from "./BookCover";

interface Props {
  isOpen: boolean;
  book: Book;
  onClose: () => void;
  onSaved: (details: BookDetails) => void;
}

interface Draft {
  title: string;
  authors: string;
  tags: string;
  series: string;
  seriesIndex: string;
  publisher: string;
  pubdate: string;
  languages: string;
}

function draftFrom(book: Book): Draft {
  return {
    title: book.title,
    authors: book.authors.join("\n"),
    tags: book.tags.join("\n"),
    series: book.series ?? "",
    seriesIndex: book.seriesIndex?.toString() ?? "",
    publisher: book.publisher ?? "",
    pubdate: book.pubdate?.slice(0, 10) ?? "",
    languages: book.languages.join("\n"),
  };
}

function lines(value: string): string[] {
  return value.split(/\r?\n/).map((part) => part.trim()).filter(Boolean);
}

function changedFields(initial: Draft, draft: Draft): BookMetadataFields {
  return {
    ...(draft.title.trim() !== initial.title ? { title: draft.title.trim() } : {}),
    ...(draft.authors !== initial.authors ? { authors: lines(draft.authors) } : {}),
    ...(draft.tags !== initial.tags ? { tags: lines(draft.tags) } : {}),
    ...(draft.series !== initial.series ? { series: draft.series.trim() || null } : {}),
    ...(draft.seriesIndex !== initial.seriesIndex
      ? { seriesIndex: draft.seriesIndex.trim() === "" ? null : Number(draft.seriesIndex) }
      : {}),
    ...(draft.publisher !== initial.publisher ? { publisher: draft.publisher.trim() || null } : {}),
    ...(draft.pubdate !== initial.pubdate ? { pubdate: draft.pubdate || null } : {}),
    ...(draft.languages !== initial.languages ? { languages: lines(draft.languages) } : {}),
  };
}

export function BookMetadataDialog({ isOpen, book, onClose, onSaved }: Props) {
  const [initial, setInitial] = useState(() => draftFrom(book));
  const [draft, setDraft] = useState(() => draftFrom(book));
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!isOpen) return;
    const next = draftFrom(book);
    setInitial(next);
    setDraft(next);
    setError(null);
    setSaving(false);
  }, [book, isOpen]);

  const fields = changedFields(initial, draft);
  const validSeriesIndex = fields.seriesIndex == null || (Number.isFinite(fields.seriesIndex) && fields.seriesIndex >= 0);

  const save = async () => {
    if (!draft.title.trim()) return setError("A book needs a title.");
    if (!validSeriesIndex) return setError("Series number must be a non-negative number.");
    setSaving(true);
    setError(null);
    try {
      onSaved(await api.updateBookMetadata(book.id, fields));
    } catch (err) {
      setError(errorMessage(err));
      setSaving(false);
    }
  };

  return (
    <Dialog isOpen={isOpen} onClose={onClose} title="Edit metadata" icon="edit" className="metadata-editor-dialog">
      <form
        onSubmit={(e) => {
          e.preventDefault();
          void save();
        }}
      >
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
              <FormGroup label="Authors" labelFor="metadata-authors" helperText="One author per line.">
                <TextArea
                  id="metadata-authors"
                  fill
                  rows={2}
                  value={draft.authors}
                  onChange={(e) => setDraft({ ...draft, authors: e.currentTarget.value })}
                />
              </FormGroup>
              <FormGroup label="Tags" labelFor="metadata-tags" helperText="One tag per line.">
                <TextArea
                  id="metadata-tags"
                  fill
                  rows={3}
                  value={draft.tags}
                  onChange={(e) => setDraft({ ...draft, tags: e.currentTarget.value })}
                />
              </FormGroup>
              <div className="metadata-editor-row">
                <FormGroup label="Series" labelFor="metadata-series">
                  <InputGroup id="metadata-series" value={draft.series} onChange={(e) => setDraft({ ...draft, series: e.currentTarget.value })} />
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
                <InputGroup id="metadata-publisher" value={draft.publisher} onChange={(e) => setDraft({ ...draft, publisher: e.currentTarget.value })} />
              </FormGroup>
              <div className="metadata-editor-row">
                <FormGroup label="Published" labelFor="metadata-pubdate">
                  <InputGroup id="metadata-pubdate" type="date" value={draft.pubdate} onChange={(e) => setDraft({ ...draft, pubdate: e.currentTarget.value })} />
                </FormGroup>
                <FormGroup label="Languages" labelFor="metadata-languages" helperText="One ISO language code per line.">
                  <TextArea
                    id="metadata-languages"
                    fill
                    rows={2}
                    value={draft.languages}
                    onChange={(e) => setDraft({ ...draft, languages: e.currentTarget.value })}
                  />
                </FormGroup>
              </div>
              <p className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL} metadata-editor-hint`}>
                Only changed fields are sent to Calibre. Changes made to other fields outside raggedlib are preserved.
              </p>
              {error && (
                <Callout intent={Intent.DANGER} icon="error" compact>
                  {error}
                </Callout>
              )}
            </div>
          </div>
        </DialogBody>
        <DialogFooter
          actions={
            <>
              <Button text="Cancel" onClick={onClose} disabled={saving} />
              <Button
                type="submit"
                intent={Intent.PRIMARY}
                icon="floppy-disk"
                text="Save"
                loading={saving}
                disabled={saving || Object.keys(fields).length === 0 || !validSeriesIndex}
              />
            </>
          }
        />
      </form>
    </Dialog>
  );
}
