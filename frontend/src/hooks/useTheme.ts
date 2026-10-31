import { Classes } from "@blueprintjs/core";
import { useCallback, useEffect, useState } from "react";
import { loadString, saveString, STORAGE_KEYS } from "../lib/storage";

export type Theme = "dark" | "light";

/** Dark by default; the choice is persisted. The class goes on <body> so portals (popovers, toasts) inherit it. */
export function useTheme(): [Theme, () => void] {
  const [theme, setTheme] = useState<Theme>(() => (loadString(STORAGE_KEYS.theme) === "light" ? "light" : "dark"));
  useEffect(() => {
    const dark = theme === "dark";
    document.body.classList.toggle(Classes.DARK, dark);
    document.documentElement.classList.toggle("app-dark", dark);
    document.documentElement.style.colorScheme = theme;
    saveString(STORAGE_KEYS.theme, theme);
  }, [theme]);
  const toggle = useCallback(() => setTheme((t) => (t === "dark" ? "light" : "dark")), []);
  return [theme, toggle];
}
