import type * as OverlayerModule from "foliate-js/overlayer.js";
import type * as ViewModule from "foliate-js/view.js";

/**
 * Loads foliate-js from where server.ts serves it, unbundled. It is plain ES modules that import one another lazily:
 * the parser of a book's format, the paginator, zip, etc. load on demand, and PDF.js never does (PDFs open in the
 * browser's viewer). The URL is built at runtime so the bundler leaves these imports alone instead of inlining
 * foliate-js, PDF.js included, into the app's script.
 */
export async function loadFoliate(): Promise<{ view: typeof ViewModule; overlayer: typeof OverlayerModule }> {
  const base = new URL("/vendor/foliate-js/", window.location.origin);
  const [view, overlayer] = await Promise.all([
    import(new URL("view.js", base).href) as Promise<typeof ViewModule>,
    import(new URL("overlayer.js", base).href) as Promise<typeof OverlayerModule>,
  ]);
  return { view, overlayer };
}
