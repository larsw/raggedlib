import { Classes } from "@blueprintjs/core";
import type { IconName } from "@blueprintjs/icons";
import { useCallback, useEffect, useState } from "react";
import { loadString, saveString, STORAGE_KEYS } from "../lib/storage";

export type Theme = "dark" | "light" | "sepia";

/** Cycle order of the navbar theme button. */
export const THEMES: readonly Theme[] = ["dark", "light", "sepia"];

export const THEME_META: Record<Theme, { label: string; icon: IconName }> = {
  dark: { label: "Dark", icon: "moon" },
  light: { label: "Light", icon: "flash" },
  sepia: { label: "Sepia", icon: "manual" },
};

export function nextTheme(theme: Theme): Theme {
  return THEMES[(THEMES.indexOf(theme) + 1) % THEMES.length]!;
}

/** Unknown/missing values fall back to dark (the default). Keep in sync with the pre-paint script in index.html. */
export function parseTheme(value: string | null | undefined): Theme {
  return value === "light" || value === "sepia" ? value : "dark";
}

/**
 * Dark by default; the choice is persisted. Blueprint's dark class goes on <body> so portals (popovers, toasts)
 * inherit it. Sepia is Blueprint's light theme re-tinted via the `app-sepia` class on <html> (see styles.css).
 */
export function useTheme(): [Theme, () => void] {
  const [theme, setTheme] = useState<Theme>(() => parseTheme(loadString(STORAGE_KEYS.theme)));
  useEffect(() => {
    const root = document.documentElement;
    document.body.classList.toggle(Classes.DARK, theme === "dark");
    root.classList.toggle("app-dark", theme === "dark");
    root.classList.toggle("app-sepia", theme === "sepia");
    root.style.colorScheme = theme === "dark" ? "dark" : "light";
    saveString(STORAGE_KEYS.theme, theme);
  }, [theme]);
  const cycle = useCallback(() => setTheme(nextTheme), []);
  return [theme, cycle];
}
