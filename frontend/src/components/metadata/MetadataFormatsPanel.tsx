import { Button, Callout, Classes, Intent } from "@blueprintjs/core";
import { useEffect, useRef, useState } from "react";
import { api, ApiError, errorMessage } from "../../api/client";
import type { Book, BookDetails, CalibreExtras } from "../../api/types";
import { useUploadOptions } from "../../hooks/useUploadOptions";
import { formatOf, uploadAccept, validateFormatFile } from "../../lib/bookUpload";
import { formatBytes } from "../../lib/format";

interface Props {
  book: Book;
  calibre: CalibreExtras | null;
  /** the rest of the editor is busy */
  disabled: boolean;
  onSaved: (details: BookDetails) => void;
  onBusyChange: (busy: boolean) => void;
}

/** The metadata editor's formats: add, replace and remove a book's files. Saved at once. */
export function MetadataFormatsPanel({ book, calibre, disabled, onSaved, onBusyChange }: Props) {
  const options = useUploadOptions();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const input = useRef<HTMLInputElement>(null);
  const off = disabled || busy;

  useEffect(() => onBusyChange(busy), [busy, onBusyChange]);
  useEffect(() => () => onBusyChange(false), [onBusyChange]);

  const change = async (action: () => Promise<BookDetails>, replaceAfterConflict?: () => Promise<BookDetails>) => {
    setBusy(true);
    setError(undefined);
    try {
      try {
        onSaved(await action());
      } catch (err) {
        if (!(err instanceof ApiError && err.status === 409 && replaceAfterConflict && window.confirm(err.message))) throw err;
        onSaved(await replaceAfterConflict());
      }
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const upload = (file: File) => {
    const invalid = validateFormatFile(file, options);
    if (invalid) {
      setError(invalid);
      return;
    }
    const format = formatOf(file.name);
    const exists = book.formats.some((value) => value.toUpperCase() === format);
    if (exists && !window.confirm(`Replace its ${format}?`)) return;
    void change(
      () => api.uploadBookFormat(book.id, file, exists),
      exists ? undefined : () => api.uploadBookFormat(book.id, file, true),
    );
  };

  return (
    <div className="metadata-editor-formats">
      <strong>Formats</strong>
      <input
        ref={input}
        type="file"
        accept={uploadAccept(options)}
        hidden
        onChange={(event) => {
          const file = event.currentTarget.files?.[0];
          event.currentTarget.value = "";
          if (file) upload(file);
        }}
      />
      {book.formats.length === 0 ? (
        <p className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL}`}>No formats</p>
      ) : (
        <ul>
          {book.formats.map((format) => (
            <li key={format}>
              <span>{format} <span className={Classes.TEXT_MUTED}>{formatBytes(calibre?.fileSizes[format])}</span></span>
              <Button
                type="button"
                small
                variant="minimal"
                text="Remove"
                aria-label={`Remove ${format}`}
                disabled={off}
                onClick={() => void change(() => api.removeBookFormat(book.id, format))}
              />
            </li>
          ))}
        </ul>
      )}
      <Button type="button" small text="Add format" onClick={() => input.current?.click()} disabled={off} />
      {busy && <p className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL}`}>Updating formats…</p>}
      {error && <Callout intent={Intent.DANGER} icon="error" compact>{error}</Callout>}
    </div>
  );
}
