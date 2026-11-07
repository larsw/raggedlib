import { HTMLSelect, Spinner, Tag, Tooltip } from "@blueprintjs/core";
import { useState } from "react";
import type { GrantableRole, LibraryRole } from "../../api/types";
import { GRANTABLE_ROLES, ROLE_HELP, roleLabel, toGrantable } from "../../lib/auth";

const NO_GRANT = "";

interface Props {
  /** granted in the app for this library (null: none) */
  granted: LibraryRole | null;
  /** what the user has, all sources counted (grant, group, default role, global admin) */
  effective: LibraryRole;
  /** for screen readers: "Role of Ann in Tech" */
  label: string;
  disabled?: boolean;
  /** null revokes the grant */
  onChange: (role: GrantableRole | null) => Promise<void>;
}

/**
 * A user's role in one library: the select grants or revokes; when the user has more than was granted (through a
 * group, the default role, or as global admin), a tag says what they effectively have.
 */
export function RoleSelect({ granted, effective, label, disabled, onChange }: Props) {
  const [busy, setBusy] = useState(false);
  const value = toGrantable(granted) ?? NO_GRANT;
  const differs = (toGrantable(effective) ?? NO_GRANT) !== value;

  return (
    <span className="role-select">
      <HTMLSelect
        aria-label={label}
        value={value}
        disabled={disabled || busy}
        minimal
        options={[
          { value: NO_GRANT, label: "—" },
          ...GRANTABLE_ROLES.map((r) => ({ value: r, label: r })),
        ]}
        onChange={async (e) => {
          const next = e.currentTarget.value;
          setBusy(true);
          try {
            await onChange(next === NO_GRANT ? null : (next as GrantableRole));
          } finally {
            setBusy(false);
          }
        }}
      />
      {busy ? (
        <Spinner size={12} />
      ) : (
        differs && (
          <Tooltip
            content={
              effective === "NONE"
                ? "No access: the user is disabled"
                : `Effectively ${roleLabel(effective)}, through a group, the default role or as global admin. ${ROLE_HELP[toGrantable(effective)!]}.`
            }
            placement="top"
          >
            <Tag minimal round className="role-effective">
              {roleLabel(effective)}
            </Tag>
          </Tooltip>
        )
      )}
    </span>
  );
}
