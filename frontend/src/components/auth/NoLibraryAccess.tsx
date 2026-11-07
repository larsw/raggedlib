import { Button, Intent, NonIdealState } from "@blueprintjs/core";
import { useState } from "react";
import { useApp } from "../../AppContext";

/** For a signed-in user who can see no library yet: instead of empty views, say why and what to do. */
export function NoLibraryAccess() {
  const { me, refreshMe } = useApp();
  const [checking, setChecking] = useState(false);
  const check = async () => {
    setChecking(true);
    try {
      await refreshMe();
    } finally {
      setChecking(false);
    }
  };
  return (
    <NonIdealState
      className="no-access"
      icon="lock"
      title="No library access yet"
      description={
        <>
          You're signed in as <strong>{me.displayName}</strong>
          {me.email && me.email !== me.displayName ? ` (${me.email})` : ""}, but no library has been shared with you
          yet. Ask an administrator, or the admin of the library you need, to give you access.
        </>
      }
      action={<Button icon="refresh" intent={Intent.PRIMARY} text="Check again" loading={checking} onClick={check} />}
    />
  );
}
