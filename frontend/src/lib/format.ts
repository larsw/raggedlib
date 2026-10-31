/** Formats a duration in seconds as e.g. "45s", "12m 05s", "3h 20m", "2d 4h". */
export function formatDuration(totalSeconds: number | null | undefined): string {
  if (totalSeconds == null || !Number.isFinite(totalSeconds) || totalSeconds < 0) return "—";
  const s = Math.round(totalSeconds);
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ${String(s % 60).padStart(2, "0")}s`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ${String(m % 60).padStart(2, "0")}m`;
  const d = Math.floor(h / 24);
  return `${d}d ${h % 24}h`;
}

export function formatNumber(n: number | null | undefined, maxFractionDigits = 0): string {
  if (n == null || !Number.isFinite(n)) return "—";
  return n.toLocaleString(undefined, { maximumFractionDigits: maxFractionDigits });
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString(undefined, { year: "numeric", month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
}

export function formatDate(iso: string | null | undefined): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" });
}

export function formatYear(iso: string | null | undefined): string {
  if (!iso) return "";
  const d = new Date(iso);
  // calibre uses 0101-01-01 for "unknown"
  return Number.isNaN(d.getTime()) || d.getFullYear() < 1000 ? "" : String(d.getFullYear());
}

/** "5 min ago", "just now", etc. */
export function formatRelative(iso: string | null | undefined, now = Date.now()): string {
  if (!iso) return "—";
  const t = new Date(iso).getTime();
  if (Number.isNaN(t)) return iso;
  const diff = Math.round((now - t) / 1000);
  if (diff < 10) return "just now";
  if (diff < 60) return `${diff}s ago`;
  if (diff < 3600) return `${Math.floor(diff / 60)} min ago`;
  if (diff < 86400) return `${Math.floor(diff / 3600)} h ago`;
  return formatDate(iso);
}

export function formatAuthors(authors: string[], max = 2): string {
  if (authors.length === 0) return "Unknown author";
  if (authors.length <= max) return authors.join(", ");
  return `${authors.slice(0, max).join(", ")} +${authors.length - max}`;
}

/** "p. 12" for PDFs, the section heading otherwise. */
export function formatLocation(page: number | null, section: string | null): string | null {
  if (page != null) return `p. ${page}`;
  if (section) return section;
  return null;
}

export function pluralize(n: number, singular: string, plural = `${singular}s`): string {
  return `${formatNumber(n)} ${n === 1 ? singular : plural}`;
}
