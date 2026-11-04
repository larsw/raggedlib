import { Button, MenuItem } from "@blueprintjs/core";
import { Select, type ItemListPredicate, type ItemRenderer } from "@blueprintjs/select";
import { useMemo } from "react";
import type { TagCount } from "../api/types";
import { FACET_LABELS, useFacet, type Facet } from "../hooks/useFacet";

const MAX_SHOWN = 150;
const ALL: TagCount = { name: "", count: 0 };

/** Single-value filter for the library table, by tag or publisher ("" = any). */
export function FacetFilterSelect({
  facet,
  value,
  onChange,
}: {
  facet: Facet;
  value: string;
  onChange: (value: string) => void;
}) {
  const { items } = useFacet(facet);
  const labels = FACET_LABELS[facet];

  const listPredicate = useMemo<ItemListPredicate<TagCount>>(
    () => (query, all) => {
      const q = query.toLowerCase();
      const rest = all.filter((t) => t.name && t.name.toLowerCase().includes(q)).slice(0, MAX_SHOWN);
      return q ? rest : [ALL, ...rest];
    },
    [],
  );

  const renderItem: ItemRenderer<TagCount> = (item, { handleClick, handleFocus, modifiers, ref }) => (
    <MenuItem
      key={item.name || "__all"}
      ref={ref}
      roleStructure="listoption"
      selected={item.name === value}
      active={modifiers.active}
      onClick={handleClick}
      onFocus={handleFocus}
      text={item.name || labels.any}
      label={item.name ? String(item.count) : undefined}
    />
  );

  return (
    <Select<TagCount>
      items={items}
      itemListPredicate={listPredicate}
      itemRenderer={renderItem}
      onItemSelect={(t) => onChange(t.name)}
      noResults={<MenuItem disabled roleStructure="listoption" text={labels.noMatch} />}
      popoverProps={{ minimal: true, placement: "bottom-start" }}
      popoverContentProps={{ className: "scope-popover" }}
      inputProps={{ placeholder: labels.search, "aria-label": labels.search }}
      resetOnClose
    >
      <Button
        icon={labels.icon}
        endIcon="caret-down"
        text={value || labels.any}
        title={value || undefined}
        aria-label={labels.filterBy}
        className="facet-filter-button"
      />
    </Select>
  );
}
