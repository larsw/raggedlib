import { Button, Classes, HTMLSelect, HTMLTable, NonIdealState, Spinner, Tag } from "@blueprintjs/core";
import { useState } from "react";
import { api } from "../../api/client";
import type { BookActivityAction } from "../../api/types";
import { useApp } from "../../AppContext";
import { useAsync } from "../../hooks/useAsync";
import { formatDateTime, formatNumber } from "../../lib/format";
import { ErrorState } from "../ErrorState";

const PAGE_SIZE = 25;

const ACTIONS: { value: BookActivityAction; label: string }[] = [
  { value: "UPLOAD", label: "Upload" },
  { value: "ADD_FORMAT", label: "Add format" },
  { value: "REMOVE_FORMAT", label: "Remove format" },
  { value: "REPLACE_FORMAT", label: "Replace format" },
  { value: "EDIT_METADATA", label: "Edit" },
  { value: "EDIT_COVER", label: "Cover" },
  { value: "DELETE_BOOK", label: "Delete" },
  { value: "PAUSE_EDITING", label: "Pause editing" },
  { value: "RESUME_EDITING", label: "Resume editing" },
];

function actionLabel(action: BookActivityAction) {
  return ACTIONS.find((item) => item.value === action)?.label ?? action;
}

function displayValue(value: unknown): string {
  if (value == null) return "—";
  if (Array.isArray(value)) return value.join(", ") || "—";
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}

export function ActivityTab({ onOpenBook }: { onOpenBook: (bookId: number, libraryId: number) => void }) {
  const { me } = useApp();
  const libraries = me.libraries.filter((library) => library.role === "ADMIN");
  const [libraryId, setLibraryId] = useState<number | null>(null);
  const [user, setUser] = useState("");
  const [action, setAction] = useState<BookActivityAction | "">("");
  const [page, setPage] = useState(0);
  const activity = useAsync(
    (signal) =>
      api.activity({
        libraryId: libraryId ?? undefined,
        user: user || undefined,
        action: action || undefined,
        page,
        size: PAGE_SIZE,
      }, signal),
    [libraryId, user, action, page],
  );
  const actors = activity.data?.actors ?? [];
  const pageCount = Math.max(1, Math.ceil((activity.data?.total ?? 0) / PAGE_SIZE));

  return (
    <div className="admin-tab">
      <div className="admin-toolbar">
        <HTMLSelect
          aria-label="Filter by library"
          value={libraryId ?? ""}
          onChange={(e) => {
            const value = e.currentTarget.value;
            setLibraryId(value ? Number(value) : null);
            setPage(0);
          }}
          options={[
            { value: "", label: "All libraries" },
            ...libraries.map((library) => ({ value: library.id, label: library.name })),
          ]}
        />
        <HTMLSelect
          aria-label="Filter by user"
          value={user}
          onChange={(e) => {
            setUser(e.currentTarget.value);
            setPage(0);
          }}
          options={[
            { value: "", label: "All users" },
            ...actors.map((actor) => ({ value: actor, label: actor })),
          ]}
        />
        <HTMLSelect
          aria-label="Filter by action"
          value={action}
          onChange={(e) => {
            setAction(e.currentTarget.value as BookActivityAction | "");
            setPage(0);
          }}
          options={[
            { value: "", label: "All actions" },
            ...ACTIONS.map((item) => ({ value: item.value, label: item.label })),
          ]}
        />
        <span className="spacer" />
        {activity.loading && activity.data && <Spinner size={14} />}
        {activity.data && (
          <span className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL}`}>
            {formatNumber(activity.data.total)} {activity.data.total === 1 ? "entry" : "entries"}
          </span>
        )}
      </div>
      <p className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL} admin-hint`}>
        Changes made through raggedlib. Edits in desktop Calibre aren't recorded.
      </p>
      {activity.error && !activity.data ? (
        <ErrorState error={activity.error} onRetry={activity.reload} title="Couldn't load activity" />
      ) : !activity.data ? (
        <NonIdealState icon={<Spinner size={24} />} />
      ) : activity.data.items.length === 0 ? (
        <NonIdealState icon="history" title="No activity" />
      ) : (
        <>
          <div className="admin-table-wrap">
            <HTMLTable compact striped className={`admin-table activity-table${activity.loading ? " is-stale" : ""}`}>
              <thead>
                <tr>
                  <th>When</th>
                  <th>Who</th>
                  <th>Action</th>
                  <th>Library</th>
                  <th>Book</th>
                  <th>Changes</th>
                </tr>
              </thead>
              <tbody>
                {activity.data.items.map((entry) => (
                  <tr key={entry.id}>
                    <td>{formatDateTime(entry.at)}</td>
                    <td>{entry.actor}</td>
                    <td>
                      <Tag minimal>{actionLabel(entry.action)}</Tag>
                    </td>
                    <td>{entry.libraryName}</td>
                    <td>
                      {entry.bookId != null && entry.bookTitle != null ? (
                        <BookLink entry={entry} onOpenBook={onOpenBook} />
                      ) : (
                        entry.bookTitle ?? entry.libraryName
                      )}
                    </td>
                    <td>
                      {Object.entries(entry.changes).map(([field, change]) => (
                        <div key={field}>
                          <strong>{field}</strong>: {displayValue(change.old)} → {displayValue(change.new)}
                        </div>
                      ))}
                    </td>
                  </tr>
                ))}
              </tbody>
            </HTMLTable>
          </div>
          <div className="admin-toolbar activity-pagination">
            <span className="spacer" />
            <Button icon="chevron-left" aria-label="Previous page" disabled={page === 0} onClick={() => setPage(page - 1)} />
            <Button variant="minimal" disabled text={`Page ${page + 1} / ${pageCount}`} />
            <Button icon="chevron-right" aria-label="Next page" disabled={page >= pageCount - 1} onClick={() => setPage(page + 1)} />
          </div>
        </>
      )}
    </div>
  );
}

function BookLink({
  entry,
  onOpenBook,
}: {
  entry: { bookId: number | null; libraryId: number; bookTitle: string | null };
  onOpenBook: (bookId: number, libraryId: number) => void;
}) {
  const { bookId, bookTitle, libraryId } = entry;
  if (bookId == null || bookTitle == null) return bookTitle;
  return (
    <a
      href={`#/library?bookId=${bookId}&libraryId=${libraryId}`}
      onClick={(e) => {
        e.preventDefault();
        onOpenBook(bookId, libraryId);
      }}
    >
      {bookTitle}
    </a>
  );
}
