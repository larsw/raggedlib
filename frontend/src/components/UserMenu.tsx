import { Button, Classes, Intent, Menu, MenuDivider, MenuItem, Popover, Tag } from "@blueprintjs/core";
import { useState } from "react";
import { useApp } from "../AppContext";
import { abbreviateDisplayName, roleLabel } from "../lib/auth";
import { AdministrationDialog } from "./admin/AdministrationDialog";
import { ApiKeysDialog } from "./apikeys/ApiKeysDialog";
import { toastError } from "./toaster";

const LIBRARIES_SHOWN = 6;

/** Navbar: who is signed in, their API keys, administration (for admins), and signing out. */
export function UserMenu() {
  const { me, signOut } = useApp();
  const [dialog, setDialog] = useState<"keys" | "admin" | null>(null);
  const [adminLoaded, setAdminLoaded] = useState(false);
  const [signingOut, setSigningOut] = useState(false);
  const label = abbreviateDisplayName(me.displayName);
  const canAdminister = me.admin || me.canManageMembers;
  const singleUser = me.authMode === "single-user";

  const doSignOut = async () => {
    setSigningOut(true);
    try {
      await signOut();
    } catch (err) {
      toastError("Couldn't sign out", err);
      setSigningOut(false);
    }
  };

  const openAdmin = () => {
    setAdminLoaded(true);
    setDialog("admin");
  };

  const content = (
    <Menu className="user-menu">
      <li className="user-menu-header" role="none">
        <div className="user-menu-name">{me.displayName}</div>
        {me.email && me.email !== me.displayName && <div className={Classes.TEXT_MUTED}>{me.email}</div>}
        <div className="user-menu-tags">
          {me.admin && (
            <Tag minimal intent={Intent.WARNING} icon="shield">
              admin
            </Tag>
          )}
          {singleUser && (
            <Tag minimal icon="person" title="No identity provider is configured: everyone using this server is the local admin">
              single-user mode
            </Tag>
          )}
        </div>
        {!me.admin && me.libraries.length > 0 && (
          <ul className={`user-menu-libraries ${Classes.TEXT_SMALL}`}>
            {me.libraries.slice(0, LIBRARIES_SHOWN).map((l) => (
              <li key={l.id}>
                <span className="user-menu-library-name">{l.name}</span>
                <span className={Classes.TEXT_MUTED}>{roleLabel(l.role)}</span>
              </li>
            ))}
            {me.libraries.length > LIBRARIES_SHOWN && (
              <li className={Classes.TEXT_MUTED}>and {me.libraries.length - LIBRARIES_SHOWN} more</li>
            )}
          </ul>
        )}
      </li>
      <MenuDivider />
      <MenuItem icon="key" text="API keys…" onClick={() => setDialog("keys")} />
      {canAdminister && <MenuItem icon="people" text="Administration…" onClick={openAdmin} />}
      {!singleUser && (
        <>
          <MenuDivider />
          <MenuItem
            icon="log-out"
            text={signingOut ? "Signing out…" : "Sign out"}
            intent={Intent.DANGER}
            disabled={signingOut}
            shouldDismissPopover={false}
            onClick={doSignOut}
          />
        </>
      )}
    </Menu>
  );

  return (
    <>
      <Popover content={content} placement="bottom-end" popoverClassName="user-menu-popover">
        <Button
          variant="minimal"
          icon="user"
          endIcon="caret-down"
          className="user-menu-button"
          aria-label={`Account: ${me.displayName}`}
          title={label === me.displayName ? undefined : me.displayName}
        >
          <span className="user-menu-label">{label}</span>
        </Button>
      </Popover>
      <ApiKeysDialog isOpen={dialog === "keys"} onClose={() => setDialog(null)} />
      {/* mounted on first use (it loads users and members), then kept so it can animate closed */}
      {adminLoaded && <AdministrationDialog isOpen={dialog === "admin"} onClose={() => setDialog(null)} />}
    </>
  );
}
