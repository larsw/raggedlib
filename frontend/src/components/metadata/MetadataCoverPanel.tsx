import { Button, ButtonGroup, Callout, Classes, Intent } from "@blueprintjs/core";
import { useEffect, useRef, useState } from "react";
import { api, ApiError, errorMessage } from "../../api/client";
import type { Book, BookDetails, MetadataCoverCandidate } from "../../api/types";
import { BookCover } from "../BookCover";
import { coverCandidateFile } from "./metadataDraft";

interface Props {
  book: Book;
  /** covers can be downloaded from Calibre's metadata sources (an admin setting) */
  lookupEnabled: boolean;
  /** the rest of the editor is busy */
  disabled: boolean;
  onSaved: (details: BookDetails) => void;
  onBusyChange: (busy: boolean) => void;
}

/** The metadata editor's cover: upload, remove, download from a source, or take it from a format. Saved at once. */
export function MetadataCoverPanel({ book, lookupEnabled, disabled, onSaved, onBusyChange }: Props) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const [candidates, setCandidates] = useState<MetadataCoverCandidate[]>([]);
  const [lookupLoading, setLookupLoading] = useState(false);
  const [lookupMessage, setLookupMessage] = useState<string>();
  const [lookupWarning, setLookupWarning] = useState(true);
  const input = useRef<HTMLInputElement>(null);
  const lookupAbort = useRef<AbortController | null>(null);
  const off = disabled || busy;

  useEffect(() => onBusyChange(busy), [busy, onBusyChange]);
  useEffect(() => () => {
    lookupAbort.current?.abort();
    onBusyChange(false); // gone (another book, or the editor closed) while a change was running
  }, [onBusyChange]);

  const change = async (action: () => Promise<BookDetails>, onSuccess?: () => void) => {
    setBusy(true);
    setError(undefined);
    try {
      onSaved(await action());
      onSuccess?.();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const lookup = async () => {
    lookupAbort.current?.abort();
    const ctrl = new AbortController();
    lookupAbort.current = ctrl;
    setLookupLoading(true);
    setLookupMessage(undefined);
    setCandidates([]);
    try {
      const result = await api.metadataCoverCandidates(book.id, ctrl.signal);
      if (ctrl.signal.aborted) return;
      setCandidates(result.candidates);
      if (result.candidates.length === 0) {
        setLookupWarning(!result.timedOut);
        setLookupMessage(result.timedOut
          ? "Cover lookup timed out. Try again."
          : result.failedSources.length > 0
            ? `No covers were found. Unavailable sources: ${result.failedSources.join(", ")}.`
            : "No covers were found.");
      } else if (result.timedOut || result.failedSources.length > 0) {
        setLookupWarning(true);
        setLookupMessage(result.timedOut
          ? "Some sources timed out. Showing the covers received so far."
          : `Some sources failed: ${result.failedSources.join(", ")}.`);
      }
    } catch (err) {
      if (!ctrl.signal.aborted) {
        setLookupWarning(err instanceof ApiError && err.status === 504);
        setLookupMessage(err instanceof ApiError && err.status === 504
          ? "Cover lookup timed out. Try again."
          : errorMessage(err));
      }
    } finally {
      if (!ctrl.signal.aborted) setLookupLoading(false);
    }
  };

  return (
    <>
      <div className="metadata-editor-cover-preview">
        <BookCover bookId={book.id} hasCover={book.hasCover} width={176} title={book.title} coverVersion={book.lastModified} natural />
      </div>
      <div className="metadata-editor-cover-actions">
        <input
          ref={input}
          type="file"
          accept="image/jpeg,image/png,image/webp"
          hidden
          onChange={(event) => {
            const file = event.currentTarget.files?.[0];
            event.currentTarget.value = "";
            if (file) void change(() => api.uploadBookCover(book.id, file));
          }}
        />
        <ButtonGroup vertical fill>
          <Button type="button" small text="Browse…" onClick={() => input.current?.click()} disabled={off} />
          <Button
            type="button"
            small
            text="Remove"
            onClick={() => void change(() => api.removeBookCover(book.id))}
            disabled={off || !book.hasCover}
          />
        </ButtonGroup>
        {lookupEnabled && (
          <Button
            type="button"
            small
            icon="cloud-download"
            text="Download cover"
            loading={lookupLoading}
            disabled={off || lookupLoading}
            onClick={() => void lookup()}
          />
        )}
        {lookupLoading && <p className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL}`}>Searching cover sources…</p>}
        {lookupMessage && (
          <Callout intent={lookupWarning ? Intent.WARNING : Intent.DANGER} icon={lookupWarning ? "warning-sign" : "error"} compact>
            {lookupMessage}
          </Callout>
        )}
        {candidates.length > 0 && (
          <section className="metadata-cover-candidates" aria-label="Cover candidates">
            {candidates.map((candidate, index) => (
              <button
                key={`${candidate.source}-${index}`}
                type="button"
                className="metadata-cover-candidate"
                aria-label={`Use ${candidate.source} cover, ${candidate.width} by ${candidate.height} pixels`}
                disabled={off}
                onClick={() => void change(
                  () => api.uploadBookCover(book.id, coverCandidateFile(candidate)),
                  () => {
                    setCandidates([]);
                    setLookupMessage(undefined);
                  },
                )}
              >
                <img src={`data:${candidate.mediaType};base64,${candidate.image}`} alt="" />
                <span>{candidate.source}</span>
                <span className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL}`}>{candidate.width} × {candidate.height}</span>
              </button>
            ))}
          </section>
        )}
        <label className="metadata-editor-cover-format-label" htmlFor="metadata-cover-format">Use cover from file</label>
        <select
          id="metadata-cover-format"
          className="metadata-editor-cover-format"
          value=""
          disabled={off || book.formats.length === 0}
          onChange={(event) => {
            const format = event.currentTarget.value;
            if (format) void change(() => api.useBookCoverFromFormat(book.id, format));
          }}
        >
          <option value="">Choose a format…</option>
          {book.formats.map((format) => <option key={format} value={format}>{format}</option>)}
        </select>
        {busy && <p className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL}`}>Updating cover…</p>}
        {error && <Callout intent={Intent.DANGER} icon="error" compact>{error}</Callout>}
        <p className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL}`}>
          Cover changes are saved immediately, separately from metadata.
        </p>
      </div>
    </>
  );
}
