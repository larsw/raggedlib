import { useEffect, useState } from "react";
import { api } from "../api/client";
import type { UploadOptions } from "../api/types";

let cached: Promise<UploadOptions> | null = null;

/** The backend's upload limits and formats, fetched once (again after a failure); null until known or if unavailable. */
export function useUploadOptions(enabled = true): UploadOptions | null {
  const [options, setOptions] = useState<UploadOptions | null>(null);
  useEffect(() => {
    if (!enabled) return;
    let live = true;
    cached ??= api.uploadOptions().catch((err) => {
      cached = null;
      throw err;
    });
    cached.then(
      (loaded) => live && setOptions(loaded),
      () => {}, // the backend checks uploads anyway
    );
    return () => {
      live = false;
    };
  }, [enabled]);
  return options;
}
