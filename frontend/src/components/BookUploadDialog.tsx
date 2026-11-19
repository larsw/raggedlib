import { Button, Callout, Classes, Dialog, HTMLSelect, Intent, Spinner, Tag } from "@blueprintjs/core";
import { useEffect, useMemo, useState } from "react";
import { api, errorMessage } from "../api/client";
import type { Book, BookUploadChoice, BookUploadItem, Library } from "../api/types";
import { formatAuthors } from "../lib/format";
import { validateBookUploadFiles } from "../lib/bookUpload";
import { roleAtLeast } from "../lib/auth";
import { StatusTag } from "./StatusTag";

type UploadResultItem = BookUploadItem & { resolvedChoice?: BookUploadChoice };

interface Props {
  isOpen: boolean;
  files: File[];
  initialLibraryId: number | null;
  libraries: Library[];
  onClose: () => void;
  onUploaded: (libraryId: number) => void;
  onOpenBook: (book: Book, libraryId: number) => void;
}

export function BookUploadDialog({
  isOpen,
  files,
  initialLibraryId,
  libraries,
  onClose,
  onUploaded,
  onOpenBook,
}: Props) {
  const [libraryId, setLibraryId] = useState<number | "">("");
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [items, setItems] = useState<UploadResultItem[] | null>(null);
  const [resultLibraryId, setResultLibraryId] = useState<number | null>(null);
  const [resolvingIds, setResolvingIds] = useState<Set<string>>(() => new Set());
  const [decisionErrors, setDecisionErrors] = useState<Record<string, string>>({});
  const busy = uploading || resolvingIds.size > 0;
  const editableLibraries = useMemo(
    () => libraries.filter((library) => library.writable && !library.editingPaused && roleAtLeast(library.role, "CURATOR")),
    [libraries],
  );
  const preferred = editableLibraries.find((library) => library.id === initialLibraryId);
  const target = editableLibraries.find((library) => library.id === libraryId);

  useEffect(() => {
    if (!isOpen) return;
    setLibraryId(preferred?.id ?? (editableLibraries.length === 1 ? editableLibraries[0]!.id : ""));
    setError(null);
    setItems(null);
    setResultLibraryId(null);
    setResolvingIds(new Set());
    setDecisionErrors({});
  }, [isOpen, files, initialLibraryId, preferred?.id, editableLibraries]);

  const upload = async () => {
    const invalid = validateBookUploadFiles(files);
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
      onUploaded(target.id);
      if (result.items.length === 1 && result.items[0]?.book && !result.items[0].alreadyInLibrary) {
        onOpenBook(result.items[0].book, target.id);
        onClose();
      }
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setUploading(false);
    }
  };

  const decide = async (index: number, choice: BookUploadChoice, bookId?: number) => {
    const item = items?.[index];
    const pendingId = item?.pendingId;
    if (!pendingId || resultLibraryId === null) return;
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
      onUploaded(resultLibraryId);
    } catch (e) {
      setDecisionErrors((current) => ({ ...current, [pendingId]: errorMessage(e) }));
    } finally {
      setResolvingIds((current) => {
        const next = new Set(current);
        next.delete(pendingId);
        return next;
      });
    }
  };

  const skipRemaining = () => {
    if (!items) return;
    void Promise.all(items.map((item, index) => item.pendingId ? decide(index, "SKIP") : undefined));
  };

  const pendingCount = items?.filter((item) => item.pendingId).length ?? 0;

  return (
    <Dialog
      title="Add books"
      isOpen={isOpen}
      onClose={onClose}
      canEscapeKeyClose={!busy}
      canOutsideClickClose={!busy}
      role="dialog"
      aria-label="Add books"
    >
      <div className={Classes.DIALOG_BODY}>
        {items ? (
          <>
            {pendingCount > 1 && (
              <div className="book-upload-skip-remaining">
                <Button
                  variant="minimal"
                  text={`Skip remaining (${pendingCount})`}
                  disabled={busy}
                  onClick={skipRemaining}
                />
              </div>
            )}
            <ol className="book-upload-results" aria-label="Upload results">
            {items.map((item, index) => {
              const book = item.book;
              const pendingId = item.pendingId ?? null;
              const duplicateBooks = item.duplicateBooks ?? [];
              return (
                <li key={`${item.files.join("|")}-${index}`}>
                  <div className="book-upload-result">
                    <div className="book-upload-summary">
                      {book ? (
                        <>
                          <strong>{book.title}</strong>
                          <div className={Classes.TEXT_MUTED}>{formatAuthors(book.authors)}</div>
                        </>
                      ) : (
                        <strong>{item.files[0]?.replace(/\.[^.]+$/, "") ?? "Book"}</strong>
                      )}
                    </div>
                    {book ? (
                      <>
                        <StatusTag status={book.status} error={book.error} />
                        <Button variant="minimal" icon="edit" text="Edit" onClick={() => onOpenBook(book, book.libraryId)} />
                      </>
                    ) : item.resolvedChoice === "SKIP" ? (
                      <Tag intent={Intent.NONE}>Skipped</Tag>
                    ) : pendingId ? (
                      <Tag intent={Intent.WARNING}>Already in the library</Tag>
                    ) : null}
                  </div>
                  {item.files.length > 1 && <div className={`book-upload-files ${Classes.TEXT_MUTED}`}>{item.files.join(", ")}</div>}
                  {pendingId && (
                    <div className="book-upload-duplicate-options">
                      {duplicateBooks.map((duplicate) => {
                        const uploadedFormats = item.files.map((name) => name.split(".").at(-1)?.toUpperCase()).filter((format): format is string => !!format);
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
                          disabled={busy}
                          onClick={() => void decide(index, "ADD_SEPARATE")}
                        />
                        <Button
                          variant="minimal"
                          text="Skip"
                          disabled={busy}
                          onClick={() => void decide(index, "SKIP")}
                        />
                      </div>
                      {decisionErrors[pendingId] && <Callout intent={Intent.DANGER}>{decisionErrors[pendingId]}</Callout>}
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
              Each file can be up to 500 MB by default; the server enforces its configured limit.
            </p>
            {preferred ? (
              <p>Adding to <strong>{preferred.name}</strong>.</p>
            ) : (
              <label className="book-upload-library">
                <span>Upload to</span>
                <HTMLSelect
                  aria-label="Upload to library"
                  value={libraryId}
                  disabled={uploading}
                  onChange={(event) => setLibraryId(event.currentTarget.value ? Number(event.currentTarget.value) : "")}
                  options={[
                    { value: "", label: "Choose a library" },
                    ...editableLibraries.map((library) => ({ value: library.id, label: library.name })),
                  ]}
                />
              </label>
            )}
            {editableLibraries.length === 0 && <Callout intent={Intent.WARNING}>No writable library is available for upload.</Callout>}
            <ul className="book-upload-file-list">
              {files.map((file, index) => <li key={`${file.name}-${index}`}>{file.name}</li>)}
            </ul>
          </>
        )}
        {error && <Callout intent={Intent.DANGER}>{error}</Callout>}
        {uploading && (
          <div className="book-upload-progress" role="status" aria-live="polite">
            <Spinner size={16} />
            <span>Uploading {files.length} {files.length === 1 ? "file" : "files"} to Calibre…</span>
          </div>
        )}
      </div>
      <div className={Classes.DIALOG_FOOTER}>
        <div className={Classes.DIALOG_FOOTER_ACTIONS}>
          {items ? (
            <Button text="Done" disabled={busy} onClick={onClose} />
          ) : (
            <>
              <Button text="Cancel" disabled={busy} onClick={onClose} />
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
      </div>
    </Dialog>
  );
}
