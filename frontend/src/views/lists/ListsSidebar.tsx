import { Button, Classes, Icon, Intent, Spinner, Tooltip } from "@blueprintjs/core";
import type { FavoriteList, FavoriteLists } from "../../api/types";
import { formatNumber } from "../../lib/format";

interface Props {
  lists: FavoriteLists | undefined;
  loading: boolean;
  selectedId: number | null;
  onSelect: (id: number) => void;
  onCreate: () => void;
}

/** Lists tab, left: the caller's own lists and the ones others share. */
export function ListsSidebar({ lists, loading, selectedId, onSelect, onCreate }: Props) {
  return (
    <nav className="lists-sidebar" aria-label="Lists">
      <div className="lists-sidebar-head">
        <Button icon="plus" intent={Intent.PRIMARY} text="New list" fill onClick={onCreate} />
      </div>
      {!lists ? (
        loading && <Spinner size={20} className="lists-sidebar-spinner" />
      ) : (
        <div className="lists-sidebar-scroll">
          <Section title="My lists" lists={lists.mine} empty="None yet" selectedId={selectedId} onSelect={onSelect} />
          <Section
            title="Shared with me"
            lists={lists.shared}
            empty="No one shares a list yet"
            selectedId={selectedId}
            onSelect={onSelect}
            showOwner
          />
        </div>
      )}
    </nav>
  );
}

function Section({
  title,
  lists,
  empty,
  selectedId,
  onSelect,
  showOwner,
}: {
  title: string;
  lists: FavoriteList[];
  empty: string;
  selectedId: number | null;
  onSelect: (id: number) => void;
  showOwner?: boolean;
}) {
  return (
    <section className="lists-sidebar-section">
      <h3 className="lists-sidebar-title">{title}</h3>
      {lists.length === 0 ? (
        <div className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL} lists-sidebar-empty`}>{empty}</div>
      ) : (
        <ul className="lists-sidebar-items">
          {lists.map((l) => (
            <li key={l.id}>
              <button
                type="button"
                className={`lists-sidebar-item${l.id === selectedId ? " is-selected" : ""}`}
                aria-current={l.id === selectedId ? "true" : undefined}
                onClick={() => onSelect(l.id)}
              >
                <span className="lists-sidebar-name">
                  <span className="lists-sidebar-label" title={l.name}>
                    {l.name}
                  </span>
                  {showOwner && <span className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL} lists-sidebar-owner`}>{l.ownerName}</span>}
                </span>
                {!showOwner && l.shared && (
                  <Tooltip content="Shared with everyone" placement="top">
                    <Icon icon="people" size={12} className={Classes.TEXT_MUTED} />
                  </Tooltip>
                )}
                <span className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL} lists-sidebar-count`}>{formatNumber(l.bookCount)}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
