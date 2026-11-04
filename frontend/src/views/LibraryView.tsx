import {
  Button,
  ButtonGroup,
  Classes,
  ControlGroup,
  HTMLSelect,
  HTMLTable,
  InputGroup,
  Intent,
  NonIdealState,
  Spinner,
  Tag,
  Tooltip,
} from "@blueprintjs/core";
import { useEffect, useMemo, useRef, useState } from "react";
import { api, bookFileUrl } from "../api/client";
import { BOOK_STATUSES, type Book, type BookSort, type BookStatus } from "../api/types";
import { useApp } from "../AppContext";
import { BookCover } from "../components/BookCover";
import { BookDetailsPanel } from "../components/BookDetailsPanel";
import { ErrorState } from "../components/ErrorState";
import { isBusyStatus, StatusTag } from "../components/StatusTag";
import { FacetFilterSelect } from "../components/FacetFilterSelect";
import { toastError, toastSuccess } from "../components/toaster";
import { useAsync } from "../hooks/useAsync";
import { columnWidth, tableMinWidth, useColumnWidths, type ColumnSpec } from "../hooks/useColumnWidths";
import { useDebounced } from "../hooks/useDebounced";
import { useSidePanel } from "../hooks/useSidePanel";
import { STORAGE_KEYS } from "../lib/storage";
import { usePageVisible } from "../hooks/usePageVisible";
import { useSyncLibrary } from "../hooks/useSyncLibrary";
import { formatAuthors, formatDateTime, formatNumber, formatYear } from "../lib/format";
import { readerFormat } from "../lib/reader";
import { SORTS, sortParam } from "../lib/bookSort";

const PAGE_SIZE = 50;
/** Resizable columns, between the cover and the actions (which aren't). */
const COLUMNS: (ColumnSpec & { className?: string })[] = [
  { id: "title", label: "Title", defaultWidth: 420, flexible: true },
  { id: "authors", label: "Authors", defaultWidth: 200 },
  { id: "publisher", label: "Publisher", defaultWidth: 170 },
  { id: "library", label: "Library", defaultWidth: 110 },
  { id: "tags", label: "Tags", defaultWidth: 240 },
  { id: "format", label: "Format", defaultWidth: 80 },
  { id: "status", label: "Status", defaultWidth: 100 },
  { id: "chunks", label: "Chunks", defaultWidth: 80, className: "num" },
  { id: "indexed", label: "Indexed", defaultWidth: 170 },
];
const COVER_WIDTH = 36;
const ACTIONS_WIDTH = 112;

function TagList({ tags }: { tags: string[] }) {
  if (tags.length === 0) return <span className={Classes.TEXT_MUTED}>—</span>;
  const shown = tags.slice(0, 3);
  return (
    <div className="tag-list" title={tags.join(", ")}>
      {shown.map((t) => (
        <Tag key={t} minimal className="tag-chip">
          {t}
        </Tag>
      ))}
      {tags.length > shown.length && <span className={Classes.TEXT_MUTED}>+{tags.length - shown.length}</span>}
    </div>
  );
}

export function LibraryView({ active }: { active: boolean }) {
  const { setScope, navigate, openReader, library, libraries } = useApp();
  const visible = usePageVisible();
  const [q, setQ] = useState("");
  const query = useDebounced(q.trim(), 300);
  const [status, setStatus] = useState<BookStatus | "">("");
  const [tag, setTag] = useState("");
  const [publisher, setPublisher] = useState("");
  const [sort, setSort] = useState<BookSort>("title");
  const [descending, setDescending] = useState(false);
  const sortMeta = SORTS.find((s) => s.value === sort)!;
  // a newly chosen field starts in its natural direction: A → Z, or newest first for dates
  const chooseSort = (field: BookSort) => {
    setSort(field);
    setDescending(SORTS.find((s) => s.value === field)?.newestFirst ?? false);
  };
  const [page, setPage] = useState(0);
  const [reindexing, setReindexing] = useState<Set<number>>(new Set());
  /** the book shown in the details panel */
  const [selectedId, setSelectedId] = useState<number | null>(null);
  /** bumped on every list reload, so the open panel refreshes too */
  const [refreshKey, setRefreshKey] = useState(0);
  const bodyRef = useRef<HTMLDivElement>(null);
  const panel = useSidePanel(bodyRef, { storageKey: STORAGE_KEYS.bookPanel, onSnapClose: () => setSelectedId(null) });
  const { sync, syncing } = useSyncLibrary();
  const columns = useColumnWidths(STORAGE_KEYS.libraryColumns, COLUMNS);

  // back to the first page whenever the filter changes
  useEffect(() => setPage(0), [query, status, tag, publisher, sort, descending, library?.id]);

  const books = useAsync(
    (signal) =>
      api.books(
        {
          q: query || undefined,
          status: status || undefined,
          tag: tag || undefined,
          publisher: publisher || undefined,
          libraryId: library?.id,
          sort: sortParam(sort, descending),
          page,
          size: PAGE_SIZE,
        },
        signal,
      ),
    [query, status, tag, publisher, sort, descending, page, library?.id],
  );

  // Refresh when coming back to the tab (statuses change while indexing runs).
  const wasActive = useRef(active);
  useEffect(() => {
    if (active && !wasActive.current) books.reload();
    wasActive.current = active;
  }, [active, books.reload]);

  // While some rows are being processed, refresh quietly so their status moves on.
  const hasBusy = books.data?.items.some((b) => isBusyStatus(b.status)) ?? false;
  useEffect(() => {
    if (!hasBusy || !visible || !active) return;
    const t = setInterval(books.reload, 5000);
    return () => clearInterval(t);
  }, [hasBusy, visible, active, books.reload]);

  useEffect(() => {
    if (books.data) setRefreshKey((k) => k + 1);
  }, [books.data]);

  const total = books.data?.total ?? 0;
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const filtered = !!(query || status || tag || publisher);
  // provenance only matters when books from more than one library can appear side by side
  const showLibraryColumn = !library && libraries.length > 1;
  const libraryNames = useMemo(() => new Map(libraries.map((l) => [l.id, l.name])), [libraries]);
  const shownColumns = COLUMNS.filter((c) => c.id !== "library" || showLibraryColumn);
  // Until the title column is sized by hand it takes the room left over; after that, the actions column does
  // (a table narrower than the view still fills it), and one wider than the view scrolls sideways.
  const titleFlexible = columnWidth(columns.widths, COLUMNS[0]!) === undefined;

  const reindex = async (book: Book) => {
    setReindexing((s) => new Set(s).add(book.id));
    try {
      const updated = await api.reindex(book.id);
      books.setData((p) => p && { ...p, items: p.items.map((b) => (b.id === updated.id ? updated : b)) });
      toastSuccess(`Queued “${book.title}” for reindexing`);
    } catch (err) {
      toastError("Reindex failed", err);
    } finally {
      setReindexing((s) => {
        const n = new Set(s);
        n.delete(book.id);
        return n;
      });
    }
  };

  const askAbout = (book: Book) => {
    setScope((s) => ({ ...s, books: [{ id: book.id, title: book.title, authors: book.authors }], tags: [], publishers: [] }));
    navigate("ask");
  };

  const searchIn = (book: Book) => {
    setScope((s) => ({ ...s, books: [{ id: book.id, title: book.title, authors: book.authors }], tags: [], publishers: [] }));
    navigate("search");
  };

  /** a row click opens the book's details, unless it hit a control inside the row */
  const onRowClick = (e: React.MouseEvent, id: number) => {
    if ((e.target as HTMLElement).closest("button, a, input, [role='button']")) return;
    setSelectedId((cur) => (cur === id ? null : id));
  };
  const onRowKey = (e: React.KeyboardEvent, id: number) => {
    if (e.target !== e.currentTarget || (e.key !== "Enter" && e.key !== " ")) return;
    e.preventDefault();
    setSelectedId((cur) => (cur === id ? null : id));
  };

  const clearFilters = () => {
    setQ("");
    setStatus("");
    setTag("");
    setPublisher("");
  };

  return (
    <div
      className={`library-view${panel.dragging || columns.dragging ? " is-resizing" : ""}`}
      onKeyDown={(e) => {
        if (e.key === "Escape" && selectedId != null && !e.defaultPrevented) setSelectedId(null);
      }}
    >
      <div className="view-toolbar library-toolbar">
        <InputGroup
          leftIcon="search"
          placeholder="Filter by title or author…"
          aria-label="Filter by title or author"
          value={q}
          onChange={(e) => setQ(e.currentTarget.value)}
          rightElement={
            q ? <Button variant="minimal" icon="cross" aria-label="Clear filter" onClick={() => setQ("")} /> : undefined
          }
          className="library-search"
        />
        <HTMLSelect
          aria-label="Filter by status"
          value={status}
          onChange={(e) => setStatus(e.currentTarget.value as BookStatus | "")}
          options={[{ value: "", label: "Any status" }, ...BOOK_STATUSES.map((s) => ({ value: s, label: s.toLowerCase() }))]}
        />
        <FacetFilterSelect facet="tags" value={tag} onChange={setTag} />
        <FacetFilterSelect facet="publishers" value={publisher} onChange={setPublisher} />
        <ControlGroup>
          <HTMLSelect
            aria-label="Sort by"
            value={sort}
            onChange={(e) => chooseSort(e.currentTarget.value as BookSort)}
            options={SORTS.map((s) => ({ value: s.value, label: `Sort: ${s.label}` }))}
          />
          <Tooltip
            content={`${descending ? sortMeta.descending : sortMeta.ascending}; click for ${(descending ? sortMeta.ascending : sortMeta.descending).toLowerCase()}`}
            placement="bottom"
          >
            <Button
              icon={descending ? `sort-${sortMeta.kind}-desc` : `sort-${sortMeta.kind}`}
              aria-label={`Sorted ${descending ? "descending" : "ascending"} (${descending ? sortMeta.descending : sortMeta.ascending}); reverse`}
              onClick={() => setDescending((d) => !d)}
            />
          </Tooltip>
        </ControlGroup>
        {books.loading && books.data && <Spinner size={16} />}
        <span className="spacer" />
        <Button icon="refresh" text="Sync library" onClick={() => sync().then(books.reload)} loading={syncing} />
      </div>

      <div className="library-body" ref={bodyRef}>
      <div className="library-main">
      <div className="library-table-wrap">
        {books.error && !books.data ? (
          <ErrorState error={books.error} onRetry={books.reload} />
        ) : !books.data ? (
          <NonIdealState icon={<Spinner />} description="Loading library…" />
        ) : books.data.items.length === 0 ? (
          filtered ? (
            <NonIdealState
              icon="filter"
              title="No books match"
              action={<Button text="Clear filters" onClick={clearFilters} />}
            />
          ) : (
            <NonIdealState
              icon="book"
              title="The library is empty"
              description="Sync with Calibre to import your books."
              action={<Button intent={Intent.PRIMARY} icon="refresh" text="Sync library" loading={syncing} onClick={() => sync().then(books.reload)} />}
            />
          )
        ) : (
          <HTMLTable
            compact
            striped
            interactive
            className={`library-table${books.loading ? " is-stale" : ""}`}
            style={{ minWidth: tableMinWidth(columns.widths, shownColumns, COVER_WIDTH + ACTIONS_WIDTH) }}
          >
            <colgroup>
              <col style={{ width: COVER_WIDTH }} />
              {shownColumns.map((c) => (
                <col key={c.id} style={{ width: columnWidth(columns.widths, c) }} />
              ))}
              <col style={{ width: titleFlexible ? ACTIONS_WIDTH : undefined }} />
            </colgroup>
            <thead>
              <tr>
                <th aria-label="Cover" />
                {shownColumns.map((c) => (
                  <th key={c.id} className={c.className}>
                    {c.label}
                    <span className="col-resize-handle" {...columns.handleProps(c)} />
                  </th>
                ))}
                <th aria-label="Actions" />
              </tr>
            </thead>
            <tbody>
              {books.data.items.map((b) => {
                const year = formatYear(b.pubdate);
                return (
                  <tr
                    key={b.id}
                    className={`library-row${b.id === selectedId ? " is-selected" : ""}`}
                    tabIndex={0}
                    aria-current={b.id === selectedId ? "true" : undefined}
                    aria-label={`${b.title}: show details`}
                    onClick={(e) => onRowClick(e, b.id)}
                    onKeyDown={(e) => onRowKey(e, b.id)}
                  >
                    <td className="cover-cell">
                      <BookCover bookId={b.id} hasCover={b.hasCover} width={24} title={b.title} />
                    </td>
                    <td className="title-cell">
                      <div className="book-title" title={b.title}>
                        {b.title}
                      </div>
                      {(b.series || year) && (
                        <div className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL}`}>
                          {b.series && `${b.series}${b.seriesIndex != null ? ` #${b.seriesIndex}` : ""}`}
                          {b.series && year && " · "}
                          {year}
                        </div>
                      )}
                    </td>
                    <td className="authors-cell" title={b.authors.join(", ")}>
                      {formatAuthors(b.authors)}
                    </td>
                    <td className="publisher-cell" title={b.publisher ?? undefined}>
                      {b.publisher ?? <span className={Classes.TEXT_MUTED}>—</span>}
                    </td>
                    {showLibraryColumn && (
                      <td className={Classes.TEXT_SMALL}>{libraryNames.get(b.libraryId) ?? "—"}</td>
                    )}
                    <td className="tags-cell">
                      <TagList tags={b.tags} />
                    </td>
                    <td>
                      {b.sourceFormat ? (
                        <Tooltip content={`Available: ${b.formats.join(", ") || "—"}`} placement="top">
                          <Tag minimal>{b.sourceFormat}</Tag>
                        </Tooltip>
                      ) : (
                        <span className={Classes.TEXT_MUTED}>{b.formats.join(", ") || "—"}</span>
                      )}
                    </td>
                    <td>
                      <StatusTag status={b.status} error={b.error} />
                    </td>
                    <td className="num">{b.chunkCount ? formatNumber(b.chunkCount) : "—"}</td>
                    <td className={`${Classes.TEXT_SMALL} nowrap`}>{formatDateTime(b.indexedAt)}</td>
                    <td className="actions-cell">
                      <ButtonGroup variant="minimal" size="small">
                        <Tooltip content="Ask about this book" placement="top">
                          <Button icon="chat" aria-label={`Ask about ${b.title}`} onClick={() => askAbout(b)} />
                        </Tooltip>
                        {readerFormat(b.formats, b.sourceFormat) ? (
                          <Tooltip content="Read" placement="top">
                            <Button icon="book" aria-label={`Read ${b.title}`} onClick={() => openReader({ bookId: b.id, title: b.title })} />
                          </Tooltip>
                        ) : b.formats.includes("PDF") && (
                          <Tooltip content="Open PDF" placement="top">
                            <Button
                              icon="document-open"
                              aria-label={`Open ${b.title} PDF`}
                              onClick={() => window.open(bookFileUrl(b.id, "PDF"), "_blank", "noopener")}
                            />
                          </Tooltip>
                        )}
                        <Tooltip content="Reindex" placement="top">
                          <Button
                            icon="repeat"
                            aria-label={`Reindex ${b.title}`}
                            loading={reindexing.has(b.id)}
                            disabled={isBusyStatus(b.status)}
                            onClick={() => reindex(b)}
                          />
                        </Tooltip>
                      </ButtonGroup>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </HTMLTable>
        )}
      </div>

      {books.data && books.data.total > 0 && (
        <div className="pagination">
          <span className={Classes.TEXT_MUTED}>
            {formatNumber(page * PAGE_SIZE + 1)}–{formatNumber(Math.min(total, (page + 1) * PAGE_SIZE))} of {formatNumber(total)} books
          </span>
          <ButtonGroup>
            <Button icon="double-chevron-left" aria-label="First page" disabled={page === 0} onClick={() => setPage(0)} />
            <Button icon="chevron-left" aria-label="Previous page" disabled={page === 0} onClick={() => setPage(page - 1)} />
            <Button disabled text={`Page ${page + 1} / ${pageCount}`} variant="minimal" />
            <Button icon="chevron-right" aria-label="Next page" disabled={page >= pageCount - 1} onClick={() => setPage(page + 1)} />
            <Button icon="double-chevron-right" aria-label="Last page" disabled={page >= pageCount - 1} onClick={() => setPage(pageCount - 1)} />
          </ButtonGroup>
        </div>
      )}
      </div>
      {selectedId != null && (
        <div className="side-dock" style={{ width: panel.width }}>
          <div className="side-resize-handle" {...panel.handleProps} aria-label="Resize book details" />
          <BookDetailsPanel
            bookId={selectedId}
            refreshKey={refreshKey}
            reindexing={reindexing.has(selectedId)}
            onClose={() => setSelectedId(null)}
            onSelectBook={setSelectedId}
            onFilterTag={setTag}
            onFilterPublisher={setPublisher}
            onAsk={askAbout}
            onSearchIn={searchIn}
            onReindex={(b) => reindex(b).then(() => setRefreshKey((k) => k + 1))}
          />
        </div>
      )}
      </div>
    </div>
  );
}
