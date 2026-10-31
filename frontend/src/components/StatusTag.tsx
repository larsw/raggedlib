import { Intent, Spinner, Tag, Tooltip } from "@blueprintjs/core";
import type { BookStatus } from "../api/types";

const INTENT: Record<BookStatus, Intent> = {
  INDEXED: Intent.SUCCESS,
  FAILED: Intent.DANGER,
  SKIPPED: Intent.WARNING,
  PENDING: Intent.NONE,
  EXTRACTING: Intent.PRIMARY,
  EMBEDDING: Intent.PRIMARY,
};

export function statusIntent(status: BookStatus): Intent {
  return INTENT[status];
}

export function isBusyStatus(status: BookStatus): boolean {
  return status === "EXTRACTING" || status === "EMBEDDING";
}

export function StatusTag({ status, error }: { status: BookStatus; error?: string | null }) {
  const busy = isBusyStatus(status);
  const tag = (
    <Tag
      intent={INTENT[status]}
      minimal={status === "PENDING"}
      icon={busy ? <Spinner size={10} intent={Intent.NONE} /> : undefined}
      className="status-tag"
    >
      {status.toLowerCase()}
    </Tag>
  );
  if (error && (status === "FAILED" || status === "SKIPPED")) {
    return (
      <Tooltip content={<div className="tooltip-error">{error}</div>} placement="top">
        {tag}
      </Tooltip>
    );
  }
  return tag;
}
