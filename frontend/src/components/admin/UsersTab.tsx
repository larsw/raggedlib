import { Classes, HTMLTable, InputGroup, Intent, NonIdealState, Spinner, Switch, Tag, Tooltip } from "@blueprintjs/core";
import { useMemo, useState } from "react";
import { api } from "../../api/client";
import type { GrantableRole, User, UserUpdate } from "../../api/types";
import { useApp } from "../../AppContext";
import { useAsync } from "../../hooks/useAsync";
import { formatDateTime, formatRelative, pluralize } from "../../lib/format";
import { matchesUser } from "../../lib/admin";
import { ErrorState } from "../ErrorState";
import { toastError, toastSuccess } from "../toaster";
import { RoleSelect } from "./RoleSelect";

/** Global admins: every user, their admin and disabled flags, and their role in each library. */
export function UsersTab() {
  const { me, libraries, refreshMe } = useApp();
  const users = useAsync((signal) => api.adminUsers(signal), []);
  const [filter, setFilter] = useState("");
  const shown = useMemo(() => (users.data ?? []).filter((u) => matchesUser(u, filter)), [users.data, filter]);

  const replace = (u: User) => users.setData((list) => list?.map((x) => (x.id === u.id ? u : x)));
  const afterOwnChange = (userId: number) => {
    if (userId === me.id) void refreshMe();
  };

  const update = async (u: User, body: UserUpdate, done: string) => {
    try {
      replace(await api.updateAdminUser(u.id, body));
      toastSuccess(done);
      afterOwnChange(u.id);
    } catch (err) {
      toastError(`Couldn't change ${u.displayName}`, err);
    }
  };

  const setRole = async (u: User, libraryId: number, role: GrantableRole | null) => {
    const lib = libraries.find((l) => l.id === libraryId)?.name ?? `library ${libraryId}`;
    try {
      if (role) {
        const m = await api.grantMember(libraryId, u.id, role);
        const key = String(libraryId);
        replace({ ...u, granted: { ...u.granted, [key]: m.granted ?? "NONE" }, roles: { ...u.roles, [key]: m.role } });
        toastSuccess(`${u.displayName} is now ${role} in ${lib}`);
      } else {
        await api.revokeMember(libraryId, u.id);
        users.reload(); // what's left (group, default role) is the server's to work out
        toastSuccess(`Revoked ${u.displayName}'s role in ${lib}`);
      }
      afterOwnChange(u.id);
    } catch (err) {
      toastError(`Couldn't change ${u.displayName}'s role in ${lib}`, err);
    }
  };

  if (users.error && !users.data) return <ErrorState error={users.error} onRetry={users.reload} title="Couldn't load the users" />;
  if (!users.data) return <NonIdealState icon={<Spinner size={24} />} />;

  return (
    <div className="admin-tab">
      <div className="admin-toolbar">
        <InputGroup
          leftIcon="search"
          placeholder="Filter by name, email or group…"
          aria-label="Filter users"
          value={filter}
          onChange={(e) => setFilter(e.currentTarget.value)}
          className="admin-filter"
        />
        {users.loading && <Spinner size={14} />}
        <span className="spacer" />
        <span className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL}`}>
          {pluralize(users.data.length, "user")} · roles in the app; groups come from the identity provider
        </span>
      </div>
      {shown.length === 0 ? (
        <NonIdealState icon="people" title={filter ? "No users match" : "No one has signed in yet"} />
      ) : (
        <div className="admin-table-wrap">
          <HTMLTable compact striped className="admin-table">
            <thead>
              <tr>
                <th>User</th>
                <th>Admin</th>
                <th>Disabled</th>
                <th>Last sign-in</th>
                {libraries.map((l) => (
                  <th key={l.id} className="admin-role-col">
                    {l.name}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {shown.map((u) => {
                const self = u.id === me.id;
                return (
                  <tr key={u.id} className={u.disabled ? "is-disabled" : undefined}>
                    <td>
                      <UserCell user={u} self={self} />
                    </td>
                    <td>
                      <Tooltip
                        content={
                          u.adminFromClaim
                            ? "Admin through the identity provider's claim; change it there"
                            : self
                              ? "You can't take away your own admin role"
                              : "Global admin: every role in every library, and this administration"
                        }
                        placement="top"
                      >
                        <Switch
                          className="admin-switch"
                          checked={u.admin}
                          disabled={u.adminFromClaim || (self && u.adminGranted)}
                          aria-label={`${u.displayName} is a global admin`}
                          onChange={(e) =>
                            update(
                              u,
                              { adminGranted: e.currentTarget.checked },
                              e.currentTarget.checked ? `${u.displayName} is now an admin` : `${u.displayName} is no longer an admin`,
                            )
                          }
                        />
                      </Tooltip>
                    </td>
                    <td>
                      <Tooltip
                        content={self ? "You can't disable yourself" : "A disabled user can't sign in, and their API keys stop working"}
                        placement="top"
                      >
                        <Switch
                          className="admin-switch"
                          checked={u.disabled}
                          disabled={self}
                          aria-label={`${u.displayName} is disabled`}
                          onChange={(e) =>
                            update(
                              u,
                              { disabled: e.currentTarget.checked },
                              e.currentTarget.checked ? `${u.displayName} is disabled` : `${u.displayName} is enabled again`,
                            )
                          }
                        />
                      </Tooltip>
                    </td>
                    <td className={`nowrap ${Classes.TEXT_SMALL}`} title={formatDateTime(u.lastLoginAt)}>
                      {u.lastLoginAt ? formatRelative(u.lastLoginAt) : <span className={Classes.TEXT_MUTED}>never</span>}
                    </td>
                    {libraries.map((l) => (
                      <td key={l.id} className="admin-role-col">
                        <RoleSelect
                          label={`Role of ${u.displayName} in ${l.name}`}
                          granted={u.granted[String(l.id)] ?? null}
                          effective={u.roles[String(l.id)] ?? "NONE"}
                          onChange={(role) => setRole(u, l.id, role)}
                        />
                      </td>
                    ))}
                  </tr>
                );
              })}
            </tbody>
          </HTMLTable>
        </div>
      )}
    </div>
  );
}

function UserCell({ user: u, self }: { user: User; self: boolean }) {
  return (
    <div className="admin-user">
      <div className="admin-user-name">
        {u.displayName}
        {self && (
          <Tag minimal round className="admin-you">
            you
          </Tag>
        )}
        {u.disabled && (
          <Tag minimal intent={Intent.DANGER}>
            disabled
          </Tag>
        )}
      </div>
      {u.email && u.email !== u.displayName && <div className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL}`}>{u.email}</div>}
      {u.groups.length > 0 && (
        <div className="admin-groups">
          {u.groups.map((g) => (
            <Tag key={g} minimal className="admin-group">
              {g}
            </Tag>
          ))}
        </div>
      )}
    </div>
  );
}
