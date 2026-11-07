import { Button, Dialog, DialogBody, DialogFooter } from "@blueprintjs/core";
import { ApiKeysPanel } from "./ApiKeysPanel";

/** The caller's personal API keys (user menu → API keys…). */
export function ApiKeysDialog({ isOpen, onClose }: { isOpen: boolean; onClose: () => void }) {
  return (
    <Dialog isOpen={isOpen} onClose={onClose} title="API keys" icon="key" className="api-keys-dialog">
      <DialogBody>
        <ApiKeysPanel />
      </DialogBody>
      <DialogFooter actions={<Button text="Close" onClick={onClose} />} />
    </Dialog>
  );
}
