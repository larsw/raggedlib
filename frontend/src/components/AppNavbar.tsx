import { Alignment, Button, Navbar, NavbarDivider, NavbarGroup, NavbarHeading, Tab, Tabs, Tooltip } from "@blueprintjs/core";
import type { TabId } from "../hooks/useHashTab";
import { nextTheme, THEME_META, type Theme } from "../hooks/useTheme";
import { SettingsMenu } from "./SettingsMenu";
import { SystemStatus } from "./SystemStatus";

interface Props {
  tab: TabId;
  onTabChange: (tab: TabId) => void;
  theme: Theme;
  onToggleTheme: () => void;
}

export function AppNavbar({ tab, onTabChange, theme, onToggleTheme }: Props) {
  return (
    <Navbar className="app-navbar">
      <NavbarGroup align={Alignment.START}>
        <NavbarHeading className="app-title">
          <span className="app-logo" aria-hidden>
            <span />
            <span />
            <span />
          </span>
          <span className="app-title-text">raggedlib</span>
        </NavbarHeading>
        <NavbarDivider />
        <Tabs id="main-tabs" selectedTabId={tab} onChange={(id) => onTabChange(id as TabId)} size="large" animate>
          {/* labels are hidden on phones (icons only) but stay the tabs' accessible names */}
          <Tab id="ask" title={<span className="tab-label">Ask</span>} icon="chat" />
          <Tab id="search" title={<span className="tab-label">Search</span>} icon="search" />
          <Tab id="library" title={<span className="tab-label">Library</span>} icon="th" />
          <Tab id="indexing" title={<span className="tab-label">Indexing</span>} icon="database" />
        </Tabs>
      </NavbarGroup>
      <NavbarGroup align={Alignment.END}>
        <SystemStatus />
        <NavbarDivider />
        <ThemeButton theme={theme} onClick={onToggleTheme} />
        <SettingsMenu />
      </NavbarGroup>
    </Navbar>
  );
}

/** Tri-state button: the icon shows the current theme, a click advances dark → light → sepia → dark. */
function ThemeButton({ theme, onClick }: { theme: Theme; onClick: () => void }) {
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
