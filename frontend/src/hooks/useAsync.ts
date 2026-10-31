import { useCallback, useEffect, useState } from "react";
import { isAbortError } from "../api/client";

export interface AsyncState<T> {
  data: T | undefined;
  error: unknown;
  loading: boolean;
  reload: () => void;
  setData: React.Dispatch<React.SetStateAction<T | undefined>>;
}

/** Runs `fn` whenever `deps` change; aborts the previous run. Keeps stale data while reloading. */
export function useAsync<T>(fn: (signal: AbortSignal) => Promise<T>, deps: React.DependencyList): AsyncState<T> {
  const [data, setData] = useState<T>();
  const [error, setError] = useState<unknown>();
  const [loading, setLoading] = useState(true);
  const [nonce, setNonce] = useState(0);

  useEffect(() => {
    const ctrl = new AbortController();
    setLoading(true);
    fn(ctrl.signal)
      .then((d) => {
        if (ctrl.signal.aborted) return;
        setData(d);
        setError(undefined);
      })
      .catch((err) => {
        if (ctrl.signal.aborted || isAbortError(err)) return;
        setError(err);
      })
      .finally(() => {
        if (!ctrl.signal.aborted) setLoading(false);
      });
    return () => ctrl.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, nonce]);

  const reload = useCallback(() => setNonce((n) => n + 1), []);
  return { data, error, loading, reload, setData };
}
