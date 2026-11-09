import { Alignment, Button, Navbar, NavbarDivider, NavbarGroup, NavbarHeading, Tab, Tabs, Tooltip } from "@blueprintjs/core";
import type { IconName } from "@blueprintjs/icons";
import type { TabId } from "../hooks/useHashTab";
import { nextTheme, THEME_META, type Theme } from "../hooks/useTheme";
import { LibrarySelect } from "./LibrarySelect";
import { SettingsMenu } from "./SettingsMenu";
import { SystemStatus } from "./SystemStatus";
import { UserMenu } from "./UserMenu";

const TAB_META: Record<TabId, { label: string; icon: IconName }> = {
  ask: { label: "Ask", icon: "chat" },
  search: { label: "Search", icon: "search" },
  library: { label: "Library", icon: "th" },
  lists: { label: "Lists", icon: "bookmark" },
  vectors: { label: "Visualization", icon: "scatter-plot" },
  indexing: { label: "Indexing", icon: "database" },
};

interface Props {
  tab: TabId;
  /** the tabs this user gets */
  tabs: readonly TabId[];
  onTabChange: (tab: TabId) => void;
  theme: Theme;
  onToggleTheme: () => void;
}

export function AppNavbar({ tab, tabs, onTabChange, theme, onToggleTheme }: Props) {
  return (
    <Navbar className="app-navbar">
      <NavbarGroup align={Alignment.START}>
        <NavbarHeading className="app-title">
          <AppLogo />
          <span className="app-title-text">raggedlib</span>
        </NavbarHeading>
        {tabs.length > 0 && (
          <>
            <NavbarDivider />
            <Tabs id="main-tabs" selectedTabId={tab} onChange={(id) => onTabChange(id as TabId)} size="large" animate>
              {/* labels are hidden on phones (icons only) but stay the tabs' accessible names */}
              {tabs.map((id) => (
                <Tab key={id} id={id} title={<span className="tab-label">{TAB_META[id].label}</span>} icon={TAB_META[id].icon} />
              ))}
            </Tabs>
          </>
        )}
      </NavbarGroup>
      <NavbarGroup align={Alignment.END}>
        <LibrarySelect />
        <SystemStatus />
        <NavbarDivider />
        <ThemeButton theme={theme} onClick={onToggleTheme} />
        <SettingsMenu />
        <UserMenu />
      </NavbarGroup>
    </Navbar>
  );
}

export function AppLogo() {
  return (
    <span className="app-logo" aria-hidden>
      <span />
      <span />
      <span />
    </span>
  );
}

/** Tri-state button: the icon shows the current theme, a click advances dark → light → sepia → dark. */
export function ThemeButton({ theme, onClick }: { theme: Theme; onClick: () => void }) {
  const current = THEME_META[theme];
  const next = THEME_META[nextTheme(theme)];
  return (
    <Tooltip
      content={
        <span>
          {current.label} theme · click for <strong>{next.label.toLowerCase()}</strong>
        </span>
      }
      placement="bottom-end"
    >
      <Button
        variant="minimal"
        icon={current.icon}
        className="theme-button"
        aria-label={`Theme: ${current.label}. Switch to ${next.label.toLowerCase()} theme`}
        onClick={onClick}
      />
    </Tooltip>
  );
}
