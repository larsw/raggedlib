import { Button, Dialog, DialogBody, DialogFooter, Tab, Tabs } from "@blueprintjs/core";
import { useState } from "react";
import { useApp } from "../../AppContext";
import { ApiKeysPanel } from "../apikeys/ApiKeysPanel";
import { LibraryMembersTab } from "./LibraryMembersTab";
import { UsersTab } from "./UsersTab";

type AdminTab = "users" | "members" | "keys";

/**
 * User menu → Administration…: users (global admins), the members of the libraries the caller administers, and
 * system-wide API keys (global admins).
 */
export function AdministrationDialog({ isOpen, onClose }: { isOpen: boolean; onClose: () => void }) {
  const { me } = useApp();
  const tabs: AdminTab[] = [];
  // single-user mode: no one else signs in, so there is no one to grant roles to; Users stays, to disable users
  // from a multi-user past, whose API keys would keep working
  if (me.admin) tabs.push("users");
  if (me.canManageMembers && me.authMode !== "single-user") tabs.push("members");
  if (me.admin) tabs.push("keys");
  const [chosen, setChosen] = useState<AdminTab | null>(null);
  const tab = chosen && tabs.includes(chosen) ? chosen : tabs[0];

  return (
    <Dialog isOpen={isOpen} onClose={onClose} title="Administration" icon="people" className="admin-dialog">
      <DialogBody>
        {tab && (
          <Tabs id="admin-tabs" selectedTabId={tab} onChange={(id) => setChosen(id as AdminTab)} renderActiveTabPanelOnly>
            {tabs.includes("users") && <Tab id="users" title="Users" icon="people" panel={<UsersTab />} />}
            {tabs.includes("members") && <Tab id="members" title="Library members" icon="book" panel={<LibraryMembersTab />} />}
            {tabs.includes("keys") && <Tab id="keys" title="System API keys" icon="key" panel={<ApiKeysPanel system />} />}
          </Tabs>
        )}
      </DialogBody>
      <DialogFooter actions={<Button text="Close" onClick={onClose} />} />
    </Dialog>
  );
}
