import {
  Button,
  Callout,
  Card,
  Classes,
  HTMLTable,
  Intent,
  NonIdealState,
  ProgressBar,
  Spinner,
  Tag,
} from "@blueprintjs/core";
import { useState } from "react";
import { api } from "../api/client";
import { BOOK_STATUSES, type IngestStatus } from "../api/types";
import { ErrorState } from "../components/ErrorState";
import { statusIntent } from "../components/StatusTag";
import { toastError, toastSuccess } from "../components/toaster";
import { usePolling } from "../hooks/usePolling";
import { useSyncLibrary } from "../hooks/useSyncLibrary";
import { formatDuration, formatNumber, formatRelative } from "../lib/format";

const POLL_MS = 2000;

function Stat({ label, value, sub }: { label: string; value: React.ReactNode; sub?: React.ReactNode }) {
  return (
    <div className="stat">
      <div className={`${Classes.TEXT_MUTED} stat-label`}>{label}</div>
      <div className="stat-value">{value}</div>
      {sub && <div className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL}`}>{sub}</div>}
    </div>
  );
}

function sessionTotal(s: IngestStatus): number {
  // Remaining work in this run ≈ what's still pending or in flight.
  return s.sessionProcessed + s.counts.PENDING + s.counts.EXTRACTING + s.counts.EMBEDDING;
}

export function IndexingView({ active }: { active: boolean }) {
  // Polls only while this tab is active; usePolling also pauses while the browser tab is hidden.
  const status = usePolling((signal) => api.ingestStatus(signal), POLL_MS, active);
  const { sync, syncing } = useSyncLibrary();
  const [busy, setBusy] = useState<"start" | "stop" | "retry" | null>(null);

  const act = async (kind: "start" | "stop" | "retry") => {
    setBusy(kind);
    try {
      const s =
        kind === "stop" ? await api.ingestStop() : await api.ingestStart(kind === "retry" ? { retryFailed: true } : {});
      status.setData(s);
      toastSuccess(
        kind === "stop"
          ? "Stopping after the books in flight finish"
          : kind === "retry"
            ? "Retrying failed books"
            : s.running
              ? "Indexing started"
              : "Nothing to index",
      );
    } catch (err) {
      toastError(kind === "stop" ? "Stop failed" : "Start failed", err);
    } finally {
      setBusy(null);
    }
  };

  const s = status.data;
  if (!s) {
    return status.error ? (
      <ErrorState error={status.error} onRetry={status.refresh} />
    ) : (
      <NonIdealState icon={<Spinner />} description="Loading indexing status…" />
    );
  }

  const indexed = s.counts.INDEXED ?? 0;
  const progress = s.totalBooks > 0 ? indexed / s.totalBooks : 0;
  const sessTotal = sessionTotal(s);

  return (
    <div className="indexing-view">
      {!!status.error && (
        <Callout intent={Intent.WARNING} icon="offline" compact className="stale-callout">
          Lost contact with the backend — showing the last known status.
        </Callout>
      )}

      <div className="view-toolbar">
        <Tag
          large
          intent={s.running ? (s.stopRequested ? Intent.WARNING : Intent.PRIMARY) : Intent.NONE}
          icon={s.running ? <Spinner size={12} /> : "pause"}
          minimal={!s.running}
        >
          {s.running ? (s.stopRequested ? "Stopping…" : "Indexing") : "Idle"}
        </Tag>
        {s.startedAt && (
          <span className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL}`}>
            {s.running ? "started" : "last run started"} {formatRelative(s.startedAt)}
          </span>
        )}
        <span className="spacer" />
        <Button
          icon="play"
          intent={Intent.PRIMARY}
          text="Start"
          disabled={s.running}
          loading={busy === "start"}
          onClick={() => act("start")}
        />
        <Button
          icon="stop"
          text="Stop"
          disabled={!s.running || s.stopRequested}
          loading={busy === "stop"}
          onClick={() => act("stop")}
        />
        <Button
          icon="repeat"
          text={`Retry failed${s.counts.FAILED ? ` (${s.counts.FAILED})` : ""}`}
          disabled={!s.counts.FAILED}
          loading={busy === "retry"}
          onClick={() => act("retry")}
        />
        <Button icon="refresh" text="Sync library" loading={syncing} onClick={() => sync().then(status.refresh)} />
      </div>

      <div className="indexing-scroll">
        <Card className="indexing-overview" compact>
          <div className="overview-head">
            <span className={Classes.HEADING}>Library indexed</span>
            <span className="overview-pct">{(progress * 100).toFixed(1)}%</span>
          </div>
          <ProgressBar
            value={progress}
            intent={Intent.SUCCESS}
            animate={s.running}
            stripes={s.running}
            aria-label="Books indexed"
          />
          <div className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL}`}>
            {formatNumber(indexed)} of {formatNumber(s.totalBooks)} books
          </div>
          <div className="status-counts">
            {BOOK_STATUSES.map((st) => (
              <Tag key={st} intent={statusIntent(st)} minimal className="count-tag">
                {st.toLowerCase()} <strong>{formatNumber(s.counts[st] ?? 0)}</strong>
              </Tag>
            ))}
          </div>
        </Card>

        <div className="stats-grid">
          <Stat label="Chunks in store" value={formatNumber(s.totalChunks)} />
          <Stat label="Throughput" value={`${formatNumber(s.chunksPerSecond, 1)} chunks/s`} sub="embedded, rolling ~60s" />
          <Stat
            label="ETA"
            value={s.running ? formatDuration(s.etaSeconds) : "—"}
            sub={s.running ? "from books finished in the last ~10 min" : undefined}
          />
          <Stat
            label={s.running ? "This run" : "Last run"}
            value={`${formatNumber(s.sessionProcessed)} books`}
            sub={`${formatNumber(s.sessionChunks)} chunks embedded`}
          />
        </div>

        {s.running && sessTotal > 0 && (
          <Card compact className="session-card">
            <div className="overview-head">
              <span className={Classes.HEADING}>Run progress</span>
              <span className={Classes.TEXT_MUTED}>
                {formatNumber(s.sessionProcessed)} / ~{formatNumber(sessTotal)} books
              </span>
            </div>
            <ProgressBar value={s.sessionProcessed / sessTotal} intent={Intent.PRIMARY} aria-label="Run progress" />
          </Card>
        )}

        <div className="indexing-columns">
          <Card compact className="indexing-panel">
            <h4 className={Classes.HEADING}>Active ({s.active.length})</h4>
            {s.active.length === 0 ? (
              <div className={Classes.TEXT_MUTED}>{s.running ? "Waiting for the next book…" : "Nothing in progress."}</div>
            ) : (
              <HTMLTable compact className="full-width">
                <tbody>
                  {s.active.map((a) => (
                    <tr key={a.bookId}>
                      <td className="title-cell">
                        <div className="book-title" title={a.title}>
                          {a.title}
                        </div>
                      </td>
                      <td className="nowrap">
                        <Tag intent={Intent.PRIMARY} minimal icon={<Spinner size={10} />}>
                          {a.stage.toLowerCase()}
                        </Tag>
                      </td>
                      <td className="num nowrap">{a.chunks != null ? `${formatNumber(a.chunks)} chunks` : ""}</td>
                    </tr>
                  ))}
                </tbody>
              </HTMLTable>
            )}
          </Card>

          <Card compact className="indexing-panel">
            <h4 className={Classes.HEADING}>Recent errors ({s.recentErrors.length})</h4>
            {s.recentErrors.length === 0 ? (
              <div className={Classes.TEXT_MUTED}>No errors.</div>
            ) : (
              <ul className="error-list">
                {s.recentErrors.map((e, i) => (
                  <li key={`${e.bookId}-${e.at}-${i}`}>
                    <div className="error-head">
                      <span className="book-title" title={e.title}>
                        {e.title}
                      </span>
                      <span className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL} nowrap`}>{formatRelative(e.at)}</span>
                    </div>
                    <div className="error-text">{e.error}</div>
                  </li>
                ))}
              </ul>
            )}
          </Card>
        </div>
      </div>
    </div>
  );
}
