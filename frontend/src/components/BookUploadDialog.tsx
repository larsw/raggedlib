import { Button, Callout, Classes, Dialog, HTMLSelect, Intent, Spinner, Tag } from "@blueprintjs/core";
import { useEffect, useMemo, useState } from "react";
import { api, errorMessage } from "../api/client";
import type { Book, BookUploadItem, Library } from "../api/types";
import { formatAuthors } from "../lib/format";
import { validateBookUploadFiles } from "../lib/bookUpload";
import { roleAtLeast } from "../lib/auth";
import { StatusTag } from "./StatusTag";

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
  const [items, setItems] = useState<BookUploadItem[] | null>(null);
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

  return (
    <Dialog
      title="Add books"
      isOpen={isOpen}
      onClose={onClose}
      canEscapeKeyClose={!uploading}
      canOutsideClickClose={!uploading}
      role="dialog"
      aria-label="Add books"
    >
      <div className={Classes.DIALOG_BODY}>
        {items ? (
          <ol className="book-upload-results" aria-label="Upload results">
            {items.map((item, index) => {
              const book = item.book;
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
                    ) : (
                      <Tag intent={Intent.WARNING}>Already in the library</Tag>
                    )}
                  </div>
                  {item.files.length > 1 && <div className={`book-upload-files ${Classes.TEXT_MUTED}`}>{item.files.join(", ")}</div>}
                </li>
              );
            })}
          </ol>
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
            <Button text="Done" onClick={onClose} />
          ) : (
            <>
              <Button text="Cancel" disabled={uploading} onClick={onClose} />
              <Button
                intent={Intent.PRIMARY}
                text={`Add ${files.length} ${files.length === 1 ? "file" : "files"}`}
                loading={uploading}
                disabled={uploading || !target || files.length === 0}
                onClick={() => void upload()}
              />
            </>
          )}
        </div>
      </div>
    </Dialog>
  );
}
