/** localStorage access that never throws (private mode, blocked storage, quota, bad JSON). */

export function loadJson<T>(key: string, fallback: T): T {
  try {
    const raw = window.localStorage.getItem(key);
    return raw == null ? fallback : (JSON.parse(raw) as T);
  } catch {
    return fallback;
  }
}

export function saveJson(key: string, value: unknown): void {
  try {
    window.localStorage.setItem(key, JSON.stringify(value));
  } catch {
    /* storage unavailable or full: keep working without persistence */
  }
}

export function loadString(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

export function saveString(key: string, value: string): void {
  try {
    window.localStorage.setItem(key, value);
  } catch {
    /* ignore */
  }
}

export const STORAGE_KEYS = {
  theme: "raggedlib.theme",
  conversation: "raggedlib.conversation.v1",
  scope: "raggedlib.scope.v1",
  sourcesPanel: "raggedlib.sourcesPanel.v1",
  bookPanel: "raggedlib.bookPanel.v1",
} as const;
