import {
  Button,
  ButtonGroup,
  Classes,
  HTMLSelect,
  HTMLTable,
  InputGroup,
  Intent,
  NonIdealState,
  Spinner,
  Tag,
  Tooltip,
} from "@blueprintjs/core";
import { useEffect, useRef, useState } from "react";
import { api, bookFileUrl } from "../api/client";
import { BOOK_STATUSES, type Book, type BookSort, type BookStatus } from "../api/types";
import { useApp } from "../AppContext";
import { BookCover } from "../components/BookCover";
import { ErrorState } from "../components/ErrorState";
import { isBusyStatus, StatusTag } from "../components/StatusTag";
import { TagFilterSelect } from "../components/TagFilterSelect";
import { toastError, toastSuccess } from "../components/toaster";
import { useAsync } from "../hooks/useAsync";
import { useDebounced } from "../hooks/useDebounced";
import { usePageVisible } from "../hooks/usePageVisible";
import { useSyncLibrary } from "../hooks/useSyncLibrary";
import { formatAuthors, formatDateTime, formatNumber, formatYear } from "../lib/format";

const PAGE_SIZE = 50;
const SORTS: { value: BookSort; label: string }[] = [
  { value: "title", label: "Title" },
  { value: "author", label: "Author" },
  { value: "pubdate", label: "Published" },
  { value: "indexedAt", label: "Indexed at" },
  { value: "id", label: "Calibre id" },
];

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
  const { setScope, navigate } = useApp();
  const visible = usePageVisible();
  const [q, setQ] = useState("");
  const query = useDebounced(q.trim(), 300);
  const [status, setStatus] = useState<BookStatus | "">("");
  const [tag, setTag] = useState("");
  const [sort, setSort] = useState<BookSort>("title");
  const [page, setPage] = useState(0);
  const [reindexing, setReindexing] = useState<Set<number>>(new Set());
  const { sync, syncing } = useSyncLibrary();

  // back to the first page whenever the filter changes
  useEffect(() => setPage(0), [query, status, tag, sort]);

  const books = useAsync(
    (signal) => api.books({ q: query || undefined, status: status || undefined, tag: tag || undefined, sort, page, size: PAGE_SIZE }, signal),
    [query, status, tag, sort, page],
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

  const total = books.data?.total ?? 0;
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const filtered = !!(query || status || tag);

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
    setScope((s) => ({ ...s, books: [{ id: book.id, title: book.title, authors: book.authors }], tags: [] }));
    navigate("ask");
  };

  const clearFilters = () => {
    setQ("");
    setStatus("");
    setTag("");
  };

  return (
    <div className="library-view">
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
        <TagFilterSelect value={tag} onChange={setTag} />
        <HTMLSelect
          aria-label="Sort by"
          value={sort}
          onChange={(e) => setSort(e.currentTarget.value as BookSort)}
          options={SORTS.map((s) => ({ value: s.value, label: `Sort: ${s.label}` }))}
        />
        {books.loading && books.data && <Spinner size={16} />}
        <span className="spacer" />
        <Button icon="refresh" text="Sync library" onClick={() => sync().then(books.reload)} loading={syncing} />
      </div>

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
          <HTMLTable compact striped interactive className={`library-table${books.loading ? " is-stale" : ""}`}>
            <thead>
              <tr>
                <th aria-label="Cover" />
                <th>Title</th>
                <th>Authors</th>
                <th>Tags</th>
                <th>Format</th>
                <th>Status</th>
                <th className="num">Chunks</th>
                <th>Indexed</th>
                <th aria-label="Actions" />
              </tr>
            </thead>
            <tbody>
              {books.data.items.map((b) => {
                const year = formatYear(b.pubdate);
                return (
                  <tr key={b.id}>
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
                        {b.formats.includes("PDF") && (
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
  );
}
