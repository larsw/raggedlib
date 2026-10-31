import { Intent, OverlayToaster, Position, type ToastProps, type Toaster } from "@blueprintjs/core";
import { errorMessage } from "../api/client";

let toaster: Promise<Toaster> | undefined;

function getToaster(): Promise<Toaster> {
  toaster ??= OverlayToaster.create({ position: Position.TOP, maxToasts: 4 });
  return toaster;
}

export function showToast(props: ToastProps): void {
  getToaster()
    .then((t) => t.show({ timeout: 4000, ...props }))
    .catch((err) => console.error("toaster failed", err));
}

export function toastSuccess(message: React.ReactNode): void {
  showToast({ message, intent: Intent.SUCCESS, icon: "tick-circle" });
}

export function toastError(prefix: string, err: unknown): void {
  showToast({ message: `${prefix}: ${errorMessage(err)}`, intent: Intent.DANGER, icon: "error", timeout: 7000 });
}
