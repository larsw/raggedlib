import { Button, Callout, Classes, Dialog, HTMLSelect, Intent, Spinner, Tag } from "@blueprintjs/core";
import { useEffect, useMemo, useState } from "react";
import { api, errorMessage } from "../api/client";
import type { Book, BookUploadChoice, BookUploadItem, Library } from "../api/types";
import { useUploadOptions } from "../hooks/useUploadOptions";
import { canEditBooksIn } from "../lib/auth";
import { formatOf, validateBookUploadFiles } from "../lib/bookUpload";
import { formatAuthors } from "../lib/format";
import { StatusTag } from "./StatusTag";

type UploadResultItem = BookUploadItem & { resolvedChoice?: BookUploadChoice };

interface Props {
  isOpen: boolean;
  /** a new array starts a new upload */
  files: File[];
  initialLibraryId: number | null;
  libraries: Library[];
  /** hide the dialog, keeping the upload: duplicates are still waiting for a choice */
  onHide: () => void;
  /** the upload is over */
  onDone: () => void;
  onPendingChange: (count: number) => void;
  onUploaded: (libraryId: number) => void;
  onOpenBook: (book: Book, libraryId: number) => void;
}

/** The upload's name for an item: the uploaded file name without its extension. */
function itemName(item: BookUploadItem): string {
  return item.book?.title ?? item.files[0]?.replace(/\.[^.]+$/, "") ?? "Book";
}

/** What a decision did, for screen readers. */
function outcome(choice: BookUploadChoice, name: string): string {
  switch (choice) {
    case "SKIP": return `Skipped ${name}.`;
    case "ADD_SEPARATE": return `Added ${name} as a separate book.`;
    case "ADD_FORMAT": return `Added the formats of ${name} to the book in the library.`;
    case "REPLACE_FORMAT": return `Replaced the formats of ${name} in the library.`;
  }
}

export function BookUploadDialog({
  isOpen,
  files,
  initialLibraryId,
  libraries,
  onHide,
  onDone,
  onPendingChange,
  onUploaded,
  onOpenBook,
}: Props) {
  const options = useUploadOptions(isOpen);
  const [chosenLibraryId, setChosenLibraryId] = useState<number | "">("");
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [items, setItems] = useState<UploadResultItem[] | null>(null);
  const [resultLibraryId, setResultLibraryId] = useState<number | null>(null);
  const [resolvingIds, setResolvingIds] = useState<Set<string>>(() => new Set());
  const [decisionErrors, setDecisionErrors] = useState<Record<string, string>>({});
  const [announcement, setAnnouncement] = useState("");
  const [confirmingClose, setConfirmingClose] = useState(false);
  const busy = uploading || resolvingIds.size > 0;
  const editableLibraries = useMemo(() => libraries.filter(canEditBooksIn), [libraries]);
  const preferred = editableLibraries.find((library) => library.id === initialLibraryId);
  const target = preferred
    ?? editableLibraries.find((library) => library.id === chosenLibraryId)
    ?? (editableLibraries.length === 1 ? editableLibraries[0] : undefined);
  const pendingCount = items?.filter((item) => item.pendingId).length ?? 0;

  // a new upload starts afresh; hiding and showing the dialog keeps where it was
  useEffect(() => {
    setChosenLibraryId("");
    setError(null);
    setItems(null);
    setResultLibraryId(null);
    setResolvingIds(new Set());
    setDecisionErrors({});
    setAnnouncement("");
    setConfirmingClose(false);
  }, [files]);

  useEffect(() => onPendingChange(pendingCount), [pendingCount, onPendingChange]);
  useEffect(() => {
    if (pendingCount === 0) setConfirmingClose(false);
  }, [pendingCount]);

  const upload = async () => {
    const invalid = validateBookUploadFiles(files, options);
    if (invalid) {
      setError(invalid);
      return;
    }
    if (!target) {
      setError("Choose a writable library where editing is not paused.");
      return;
    }
    setUploading(true);
    setError(null);
    try {
      const result = await api.uploadBooks(target.id, files);
      setItems(result.items);
      setResultLibraryId(target.id);
      const added = result.items.filter((item) => item.book && !item.alreadyInLibrary).length;
      const waiting = result.items.filter((item) => item.pendingId).length;
      setAnnouncement(
        [`Added ${added} ${added === 1 ? "book" : "books"}.`, waiting > 0 ? `${waiting} already in the library.` : ""].join(" ").trim(),
      );
      onUploaded(target.id);
      const only = result.items.length === 1 ? result.items[0] : undefined;
      if (only?.book && !only.alreadyInLibrary) {
        onOpenBook(only.book, target.id);
        onDone();
      }
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setUploading(false);
    }
  };

  /** Makes the choice for item [index]; false if it failed (the error shows with the item). */
  const decide = async (index: number, choice: BookUploadChoice, bookId?: number): Promise<boolean> => {
    const item = items?.[index];
    const pendingId = item?.pendingId;
    if (!item || !pendingId || resultLibraryId === null) return false;
    setResolvingIds((current) => new Set(current).add(pendingId));
    setDecisionErrors((current) => {
      const next = { ...current };
      delete next[pendingId];
      return next;
    });
    try {
      const result = await api.decideBookUpload(resultLibraryId, pendingId, choice, bookId);
      setItems((current) => current?.map((candidate, candidateIndex) => candidateIndex === index
        ? {
            ...candidate,
            book: result.book,
            alreadyInLibrary: false,
            duplicateBooks: [],
            pendingId: null,
            resolvedChoice: choice,
          }
        : candidate) ?? null);
      setAnnouncement(outcome(choice, itemName(item)));
      onUploaded(resultLibraryId);
      return true;
    } catch (e) {
      setDecisionErrors((current) => ({ ...current, [pendingId]: errorMessage(e) }));
      return false;
    } finally {
      setResolvingIds((current) => {
        const next = new Set(current);
        next.delete(pendingId);
        return next;
      });
    }
  };

  /** Skips every waiting duplicate; true if all of them were skipped. */
  const skipRemaining = async () => {
    const results = await Promise.all(items?.map((item, index) => (item.pendingId ? decide(index, "SKIP") : true)) ?? []);
    return results.every(Boolean);
  };

  /** Closing with duplicates still waiting asks first: skip them, or keep them to decide later. */
  const requestClose = () => {
    if (busy) return;
    if (pendingCount > 0) setConfirmingClose(true);
    else onDone();
  };

  const openBook = (book: Book) => {
    onOpenBook(book, book.libraryId);
    if (pendingCount > 0) onHide();
    else onDone();
  };

  return (
    <Dialog
      title="Add books"
      isOpen={isOpen}
      onClose={requestClose}
      canEscapeKeyClose={!busy}
      canOutsideClickClose={!busy}
      role="dialog"
      aria-label="Add books"
    >
      <div className={Classes.DIALOG_BODY}>
        <div className="visually-hidden" role="status" aria-live="polite">{announcement}</div>
        {items ? (
          <>
            {pendingCount > 1 && (
              <div className="book-upload-skip-remaining">
                <Button
                  variant="minimal"
                  text={`Skip remaining (${pendingCount})`}
                  disabled={busy}
                  onClick={() => void skipRemaining()}
                />
              </div>
            )}
            <ol className="book-upload-results" aria-label="Upload results">
            {items.map((item, index) => {
              const book = item.book;
              const pendingId = item.pendingId ?? null;
              const duplicateBooks = item.duplicateBooks ?? [];
              const name = itemName(item);
              return (
                <li key={`${item.files.join("|")}-${index}`}>
                  <div className="book-upload-result">
                    <div className="book-upload-summary">
                      <strong>{name}</strong>
                      {book && <div className={Classes.TEXT_MUTED}>{formatAuthors(book.authors)}</div>}
                    </div>
                    {book ? (
                      <>
                        <StatusTag status={book.status} error={book.error} />
                        <Button variant="minimal" icon="edit" text="Edit" aria-label={`Edit ${book.title}`} onClick={() => openBook(book)} />
                      </>
                    ) : item.resolvedChoice === "SKIP" ? (
                      <Tag intent={Intent.NONE}>Skipped</Tag>
                    ) : pendingId ? (
                      <Tag intent={Intent.WARNING}>Already in the library</Tag>
                    ) : null}
                  </div>
                  {item.files.length > 1 && <div className={`book-upload-files ${Classes.TEXT_MUTED}`}>{item.files.join(", ")}</div>}
                  {pendingId && (
                    <div className="book-upload-duplicate-options" role="group" aria-label={`What to do with ${name}`}>
                      {duplicateBooks.map((duplicate) => {
                        const uploadedFormats = item.files.map(formatOf).filter(Boolean);
                        const replacedFormats = uploadedFormats.filter((format) => duplicate.formats.includes(format));
                        const missingFormats = uploadedFormats.filter((format) => !duplicate.formats.includes(format));
                        const formatAction = replacedFormats.length > 0
                          ? `Replace its ${replacedFormats.join(", ")}${missingFormats.length > 0 ? ` and add ${missingFormats.join(", ")}` : ""}`
                          : `Add as ${uploadedFormats.length === 1 ? "a format" : "formats"} of this book`;
                        return (
                          <div className="book-upload-duplicate" key={duplicate.id}>
                            <div>
                              Already in the library as <strong>{duplicate.title}</strong>, by {formatAuthors(duplicate.authors)}
                              {" "}({duplicate.formats.join(", ") || "no formats"})
                            </div>
                            <Button
                              text={formatAction}
                              aria-label={`${formatAction}: ${duplicate.title}, by ${formatAuthors(duplicate.authors)}`}
                              disabled={busy}
                              onClick={() => void decide(index, replacedFormats.length > 0 ? "REPLACE_FORMAT" : "ADD_FORMAT", duplicate.id)}
                            />
                          </div>
                        );
                      })}
                      {item.duplicateLookupError && <Callout intent={Intent.WARNING}>{item.duplicateLookupError}</Callout>}
                      <div className="book-upload-duplicate-actions">
                        <Button
                          text="Add as a separate book"
                          aria-label={`Add as a separate book: ${name}`}
                          disabled={busy}
                          onClick={() => void decide(index, "ADD_SEPARATE")}
                        />
                        <Button
                          variant="minimal"
                          text="Skip"
                          aria-label={`Skip ${name}`}
                          disabled={busy}
                          onClick={() => void decide(index, "SKIP")}
                        />
                      </div>
                      {decisionErrors[pendingId] && (
                        <Callout intent={Intent.DANGER} role="alert">{decisionErrors[pendingId]}</Callout>
                      )}
                    </div>
                  )}
                </li>
              );
            })}
            </ol>
          </>
        ) : (
          <>
            <p>
              Files sharing a name, such as <code>Novel.epub</code> and <code>Novel.pdf</code>, are added as formats of one book.
              {options
                ? ` Each file can be up to ${options.maxFormatSizeLabel}, and one upload up to ${options.maxRequestSizeLabel}.`
                : " The server checks each file's size."}
            </p>
            {preferred ? (
              <p>Adding to <strong>{preferred.name}</strong>.</p>
            ) : editableLibraries.length > 1 && (
              <label className="book-upload-library">
                <span>Upload to</span>
                <HTMLSelect
                  aria-label="Upload to library"
                  value={chosenLibraryId}
                  disabled={uploading}
                  onChange={(event) => setChosenLibraryId(event.currentTarget.value ? Number(event.currentTarget.value) : "")}
                  options={[
                    { value: "", label: "Choose a library" },
                    ...editableLibraries.map((library) => ({ value: library.id, label: library.name })),
                  ]}
                />
              </label>
            )}
            {!preferred && editableLibraries.length === 1 && <p>Adding to <strong>{editableLibraries[0]!.name}</strong>.</p>}
            {editableLibraries.length === 0 && <Callout intent={Intent.WARNING}>No writable library is available for upload.</Callout>}
            <ul className="book-upload-file-list">
              {files.map((file, index) => <li key={`${file.name}-${index}`}>{file.name}</li>)}
            </ul>
          </>
        )}
        {error && <Callout intent={Intent.DANGER} role="alert">{error}</Callout>}
        {uploading && (
          <div className="book-upload-progress" role="status" aria-live="polite">
            <Spinner size={16} />
            <span>Uploading {files.length} {files.length === 1 ? "file" : "files"} to Calibre…</span>
          </div>
        )}
      </div>
      <div className={Classes.DIALOG_FOOTER}>
        {confirmingClose ? (
          <div className="book-upload-confirm-close" role="group" aria-label="Books still waiting">
            <span>
              {pendingCount === 1 ? "1 book is" : `${pendingCount} books are`} still waiting for a choice.
            </span>
            <div className={Classes.DIALOG_FOOTER_ACTIONS}>
              <Button text="Back" disabled={busy} onClick={() => setConfirmingClose(false)} />
              <Button text="Decide later" disabled={busy} onClick={onHide} />
              <Button
                intent={Intent.WARNING}
                text={`Skip ${pendingCount === 1 ? "it" : "them"}`}
                loading={busy}
                onClick={() => void skipRemaining().then((skipped) => skipped && onDone())}
              />
            </div>
          </div>
        ) : (
          <div className={Classes.DIALOG_FOOTER_ACTIONS}>
            {items ? (
              <Button text="Done" disabled={busy} onClick={requestClose} />
            ) : (
              <>
                <Button text="Cancel" disabled={busy} onClick={onDone} />
                <Button
                  intent={Intent.PRIMARY}
                  text={`Add ${files.length} ${files.length === 1 ? "file" : "files"}`}
                  loading={busy}
                  disabled={busy || !target || files.length === 0}
                  onClick={() => void upload()}
                />
              </>
            )}
          </div>
        )}
      </div>
    </Dialog>
  );
}
