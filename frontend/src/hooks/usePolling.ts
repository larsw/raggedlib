import { useCallback, useEffect, useRef, useState } from "react";
import { isAbortError } from "../api/client";
import { usePageVisible } from "./usePageVisible";

export interface PollState<T> {
  data: T | undefined;
  error: unknown;
  loading: boolean;
  /** Fetch now (also used after actions to refresh immediately). */
  refresh: () => void;
  /** Replace data locally, e.g. with the status returned by a POST. */
  setData: (data: T) => void;
}

/**
 * Polls `fetcher` every `intervalMs` while `enabled` and the page is visible. The next poll is
 * scheduled after the previous one finished, so slow responses never pile up.
 */
export function usePolling<T>(
  fetcher: (signal: AbortSignal) => Promise<T>,
  intervalMs: number,
  enabled = true,
): PollState<T> {
  const visible = usePageVisible();
  const [data, setData] = useState<T>();
  const [error, setError] = useState<unknown>();
  const [loading, setLoading] = useState(true);
  const [tick, setTick] = useState(0);
  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;

  const active = enabled && visible;

  useEffect(() => {
    if (!active) return;
    const ctrl = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    const run = async () => {
      try {
        const result = await fetcherRef.current(ctrl.signal);
        if (ctrl.signal.aborted) return;
        setData(result);
        setError(undefined);
      } catch (err) {
        if (ctrl.signal.aborted || isAbortError(err)) return;
        setError(err);
      } finally {
        if (!ctrl.signal.aborted) {
          setLoading(false);
          timer = setTimeout(run, intervalMs);
        }
      }
    };
    run();
    return () => {
      ctrl.abort();
      clearTimeout(timer);
    };
  }, [active, intervalMs, tick]);

  const refresh = useCallback(() => setTick((t) => t + 1), []);
  const set = useCallback((d: T) => {
    setData(d);
    setError(undefined);
  }, []);
  return { data, error, loading, refresh, setData: set };
}
