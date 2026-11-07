/**
 * Copies text to the clipboard, also where `navigator.clipboard` doesn't exist: it needs a secure context (HTTPS or
 * localhost), and the UI is often opened over plain http on a LAN name. The fallback is a hidden textarea and
 * `execCommand("copy")`. Rejects only if both fail.
 */
export async function copyText(text: string): Promise<void> {
  if (typeof navigator !== "undefined" && navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text);
      return;
    } catch {
      /* permission denied etc.: try the fallback */
    }
  }
  const ta = document.createElement("textarea");
  ta.value = text;
  ta.setAttribute("readonly", "");
  ta.style.position = "fixed";
  ta.style.top = "-9999px";
  ta.style.opacity = "0";
  document.body.appendChild(ta);
  ta.select();
  try {
    if (!document.execCommand("copy")) throw new Error("the browser refused to copy");
  } finally {
    document.body.removeChild(ta);
  }
}
