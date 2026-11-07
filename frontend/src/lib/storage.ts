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

export function removeKey(key: string): void {
  try {
    window.localStorage.removeItem(key);
  } catch {
    /* ignore */
  }
}

/** sessionStorage (this tab only): read and remove in one go. */
export function takeSessionString(key: string): string | null {
  try {
    const v = window.sessionStorage.getItem(key);
    window.sessionStorage.removeItem(key);
    return v;
  } catch {
    return null;
  }
}

export function saveSessionString(key: string, value: string): void {
  try {
    window.sessionStorage.setItem(key, value);
  } catch {
    /* ignore */
  }
}

export const STORAGE_KEYS = {
  theme: "raggedlib.theme",
  /** the user these keys were last used by (see USER_KEYS) */
  user: "raggedlib.user.v1",
  /** sessionStorage: the tab (#/…) to come back to after signing in */
  returnHash: "raggedlib.returnHash",
  conversation: "raggedlib.conversation.v1",
  scope: "raggedlib.scope.v1",
  libraryId: "raggedlib.libraryId.v1",
  sourcesPanel: "raggedlib.sourcesPanel.v1",
  bookPanel: "raggedlib.bookPanel.v1",
  vectorsPanel: "raggedlib.vectorsPanel.v1",
  libraryColumns: "raggedlib.libraryColumns.v1",
  readerPrefs: "raggedlib.reader.prefs.v1",
  readerPositions: "raggedlib.reader.positions.v1",
} as const;

/** What one user did in this browser, which the next one to sign in here must not see. */
export const USER_KEYS: readonly string[] = [
  STORAGE_KEYS.conversation,
  STORAGE_KEYS.scope,
  STORAGE_KEYS.libraryId,
  STORAGE_KEYS.readerPositions,
];

export function forgetUserState(): void {
  for (const key of USER_KEYS) removeKey(key);
}
