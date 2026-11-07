import { Button, Intent, type IconName } from "@blueprintjs/core";
import { useState } from "react";

interface Props {
  icon?: IconName;
  text?: string;
  /** label of the confirming button, e.g. "Delete" */
  confirmText: string;
  "aria-label"?: string;
  disabled?: boolean;
  /** resolves when done; the buttons show a spinner meanwhile */
  onConfirm: () => Promise<void>;
}

/** A small destructive action that asks once more inline (Confirm / Cancel) before it runs. */
export function ConfirmButton({ icon = "trash", text, confirmText, disabled, onConfirm, ...rest }: Props) {
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  if (!confirming) {
    return (
      <Button
        size="small"
        variant="minimal"
        intent={Intent.DANGER}
        icon={icon}
        text={text}
        disabled={disabled}
        aria-label={rest["aria-label"]}
        onClick={() => setConfirming(true)}
      />
    );
  }
  return (
    <span className="confirm-button">
      <Button
        size="small"
        intent={Intent.DANGER}
        icon={icon}
        text={confirmText}
        loading={busy}
        autoFocus
        onClick={async () => {
          setBusy(true);
          try {
            await onConfirm();
          } finally {
            setBusy(false);
            setConfirming(false);
          }
        }}
      />
      <Button size="small" variant="minimal" text="Cancel" disabled={busy} onClick={() => setConfirming(false)} />
    </span>
  );
}
