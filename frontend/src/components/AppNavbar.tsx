import { Alignment, Button, Navbar, NavbarDivider, NavbarGroup, NavbarHeading, Tab, Tabs, Tooltip } from "@blueprintjs/core";
import type { TabId } from "../hooks/useHashTab";
import type { Theme } from "../hooks/useTheme";
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
          raggedlib
        </NavbarHeading>
        <NavbarDivider />
        <Tabs id="main-tabs" selectedTabId={tab} onChange={(id) => onTabChange(id as TabId)} size="large" animate>
          <Tab id="ask" title="Ask" icon="chat" />
          <Tab id="search" title="Search" icon="search" />
          <Tab id="library" title="Library" icon="th" />
          <Tab id="indexing" title="Indexing" icon="database" />
        </Tabs>
      </NavbarGroup>
      <NavbarGroup align={Alignment.END}>
        <SystemStatus />
        <NavbarDivider />
        <Tooltip content={theme === "dark" ? "Light theme" : "Dark theme"} placement="bottom-end">
          <Button
            variant="minimal"
            icon={theme === "dark" ? "flash" : "moon"}
            aria-label={theme === "dark" ? "Switch to light theme" : "Switch to dark theme"}
            onClick={onToggleTheme}
          />
        </Tooltip>
      </NavbarGroup>
    </Navbar>
  );
}
