import { Classes, HTMLSelect, HTMLTable, InputGroup, Intent, NonIdealState, Spinner, Switch, Tag } from "@blueprintjs/core";
import { useMemo, useState } from "react";
import { api } from "../../api/client";
import type { GrantableRole, Member } from "../../api/types";
import { useApp } from "../../AppContext";
import { useAsync } from "../../hooks/useAsync";
import { visibleMembers } from "../../lib/admin";
import { pluralize } from "../../lib/format";
import { ErrorState } from "../ErrorState";
import { toastError, toastSuccess } from "../toaster";
import { RoleSelect } from "./RoleSelect";

/** Library admins: who has which role in the libraries they administer. */
export function LibraryMembersTab() {
  const { me, refreshMe } = useApp();
  const administered = me.libraries.filter((l) => l.role === "ADMIN");
  const [chosen, setChosen] = useState<number | null>(null);
  const libraryId = administered.find((l) => l.id === chosen)?.id ?? administered[0]?.id ?? null;
  const library = administered.find((l) => l.id === libraryId);
  const members = useAsync(
    async (signal) => ({
      libraryId,
      list: libraryId == null ? [] : await api.libraryMembers(libraryId, signal),
    }),
    [libraryId],
  );
  const [filter, setFilter] = useState("");
  const [onlyWithAccess, setOnlyWithAccess] = useState(false);
  // while another library loads, its predecessor's rows don't show under its name
  const data = members.data?.libraryId === libraryId ? members.data.list : undefined;
  const shown = useMemo(() => visibleMembers(data ?? [], filter, onlyWithAccess), [data, filter, onlyWithAccess]);

  if (libraryId == null || !library) {
    return <NonIdealState icon="people" title="You administer no library" />;
  }

  const setRole = async (m: Member, role: GrantableRole | null) => {
    try {
      if (role) {
        const updated = await api.grantMember(library.id, m.userId, role);
        members.setData((d) => d && { ...d, list: d.list.map((x) => (x.userId === m.userId ? updated : x)) });
        toastSuccess(`${m.displayName} is now ${role} in ${library.name}`);
      } else {
        await api.revokeMember(library.id, m.userId);
        members.reload();
        toastSuccess(`Revoked ${m.displayName}'s role in ${library.name}`);
      }
      if (m.userId === me.id) void refreshMe();
    } catch (err) {
      toastError(`Couldn't change ${m.displayName}'s role`, err);
    }
  };

  const withAccess = (data ?? []).filter((m) => m.role !== "NONE").length;

  return (
    <div className="admin-tab">
      <div className="admin-toolbar">
        {administered.length > 1 && (
          <HTMLSelect
            aria-label="Library"
            value={libraryId}
            onChange={(e) => setChosen(Number(e.currentTarget.value))}
            options={administered.map((l) => ({ value: l.id, label: l.name }))}
          />
        )}
        <InputGroup
          leftIcon="search"
          placeholder="Filter by name or email…"
          aria-label="Filter users"
          value={filter}
          onChange={(e) => setFilter(e.currentTarget.value)}
          className="admin-filter"
        />
        <Switch
          className="admin-inline-switch"
          label="Only users with access"
          checked={onlyWithAccess}
          onChange={(e) => setOnlyWithAccess(e.currentTarget.checked)}
        />
        {members.loading && data && <Spinner size={14} />}
        <span className="spacer" />
        {data && (
          <span className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL}`}>
            {pluralize(withAccess, "user")} with access to {library.name}
          </span>
        )}
      </div>
      <p className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL} admin-hint`}>
        Everyone who has signed in is listed, so you can grant a role to someone new. A grant adds to what a user
        already has through a group or the default role; revoking it leaves that.
      </p>
      {members.error && !data ? (
        <ErrorState error={members.error} onRetry={members.reload} title="Couldn't load the members" />
      ) : !data ? (
        <NonIdealState icon={<Spinner size={24} />} />
      ) : shown.length === 0 ? (
        <NonIdealState icon="people" title={filter || onlyWithAccess ? "No users match" : "No one has signed in yet"} />
      ) : (
        <div className="admin-table-wrap">
          <HTMLTable compact striped className={`admin-table${members.loading ? " is-stale" : ""}`}>
            <thead>
              <tr>
                <th>User</th>
                <th>Granted role</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((m) => (
                <tr key={m.userId} className={m.disabled ? "is-disabled" : undefined}>
                  <td>
                    <div className="admin-user-name">
                      {m.displayName}
                      {m.userId === me.id && (
                        <Tag minimal round className="admin-you">
                          you
                        </Tag>
                      )}
                      {m.admin && (
                        <Tag minimal intent={Intent.WARNING} icon="shield">
                          global admin
                        </Tag>
                      )}
                      {m.disabled && (
                        <Tag minimal intent={Intent.DANGER}>
                          disabled
                        </Tag>
                      )}
                    </div>
                    {m.email && m.email !== m.displayName && (
                      <div className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL}`}>{m.email}</div>
                    )}
                  </td>
                  <td>
                    <RoleSelect
                      label={`Role of ${m.displayName} in ${library.name}`}
                      granted={m.granted}
                      effective={m.role}
                      onChange={(role) => setRole(m, role)}
                    />
                  </td>
                </tr>
              ))}
            </tbody>
          </HTMLTable>
        </div>
      )}
    </div>
  );
}
