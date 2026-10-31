import "@blueprintjs/core/lib/css/blueprint.css";
import "@blueprintjs/icons/lib/css/blueprint-icons.css";
import "@blueprintjs/select/lib/css/blueprint-select.css";
import "./styles.css";

import { Icons } from "@blueprintjs/icons";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App";

// Blueprint 6 renders icons as SVG paths loaded on demand (dynamic import per icon size).
// Load them up front so icons don't pop in after first paint; render anyway if this fails.
await Icons.loadAll().catch((err) => console.warn("[raggedlib] icon preload failed", err));

const root = createRoot(document.getElementById("root")!);
root.render(
  <StrictMode>
    <App />
  </StrictMode>,
);
