import { Button, Callout, Classes, Dialog, DialogBody, DialogFooter, FormGroup, InputGroup, Intent, Switch, TextArea } from "@blueprintjs/core";
import { useEffect, useState } from "react";
import { api, errorMessage } from "../../api/client";
import type { FavoriteList } from "../../api/types";

const MAX_NAME = 200;
const MAX_DESCRIPTION = 2000;

interface Props {
  isOpen: boolean;
  /** the list to change; null to create one */
  list: FavoriteList | null;
  onClose: () => void;
  onSaved: (list: FavoriteList) => void;
}

/** Name, description and sharing of one of the caller's lists, new or existing. */
export function ListFormDialog({ isOpen, list, onClose, onSaved }: Props) {
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [shared, setShared] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!isOpen) return;
    setName(list?.name ?? "");
    setDescription(list?.description ?? "");
    setShared(list?.shared ?? false);
    setError(null);
    setSaving(false);
  }, [isOpen, list]);

  const save = async () => {
    if (!name.trim()) return setError("Give the list a name.");
    setSaving(true);
    setError(null);
    try {
      const body = { name: name.trim(), description: description.trim(), shared };
      onSaved(await (list ? api.updateList(list.id, body) : api.createList(body)));
    } catch (err) {
      setError(errorMessage(err));
      setSaving(false);
    }
  };

  return (
    <Dialog isOpen={isOpen} onClose={onClose} title={list ? "Edit list" : "New list"} icon="bookmark" className="list-form-dialog">
      <form
        onSubmit={(e) => {
          e.preventDefault();
          void save();
        }}
      >
        <DialogBody>
          <FormGroup label="Name" labelFor="list-name">
            <InputGroup
              id="list-name"
              autoFocus
              maxLength={MAX_NAME}
              value={name}
              placeholder="To read this summer"
              onChange={(e) => setName(e.currentTarget.value)}
            />
          </FormGroup>
          <FormGroup label="Description" labelFor="list-description" labelInfo="(optional)">
            <TextArea
              id="list-description"
              fill
              autoResize
              maxLength={MAX_DESCRIPTION}
              value={description}
              placeholder="What the list is for"
              onChange={(e) => setDescription(e.currentTarget.value)}
            />
          </FormGroup>
          <Switch
            checked={shared}
            onChange={(e) => setShared(e.currentTarget.checked)}
            labelElement={
              <>
                <strong>Share with everyone</strong>
                <div className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL}`}>
                  Every signed-in user can see the list, and the books on it they have access to. Only you change it.
                </div>
              </>
            }
          />
          {error && (
            <Callout intent={Intent.DANGER} icon="error" compact>
              {error}
            </Callout>
          )}
        </DialogBody>
        <DialogFooter
          actions={
            <>
              <Button text="Cancel" onClick={onClose} disabled={saving} />
              <Button type="submit" intent={Intent.PRIMARY} text={list ? "Save" : "Create list"} loading={saving} disabled={!name.trim()} />
            </>
          }
        />
      </form>
    </Dialog>
  );
}
