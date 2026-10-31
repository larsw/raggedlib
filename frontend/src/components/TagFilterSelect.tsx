import { Button, MenuItem } from "@blueprintjs/core";
import { Select, type ItemListPredicate, type ItemRenderer } from "@blueprintjs/select";
import type { TagCount } from "../api/types";
import { useTags } from "./scope/TagMultiSelect";

const MAX_SHOWN = 150;
const ALL: TagCount = { name: "", count: 0 };

const listPredicate: ItemListPredicate<TagCount> = (query, items) => {
  const q = query.toLowerCase();
  const rest = items.filter((t) => t.name && t.name.toLowerCase().includes(q)).slice(0, MAX_SHOWN);
  return q ? rest : [ALL, ...rest];
};

/** Single-tag filter for the library table ("" = any tag). */
export function TagFilterSelect({ value, onChange }: { value: string; onChange: (tag: string) => void }) {
  const { tags } = useTags();

  const renderItem: ItemRenderer<TagCount> = (tag, { handleClick, handleFocus, modifiers, ref }) => (
    <MenuItem
      key={tag.name || "__all"}
      ref={ref}
      roleStructure="listoption"
      selected={tag.name === value}
      active={modifiers.active}
      onClick={handleClick}
      onFocus={handleFocus}
      text={tag.name || "Any tag"}
      label={tag.name ? String(tag.count) : undefined}
    />
  );

  return (
    <Select<TagCount>
      items={tags}
      itemListPredicate={listPredicate}
      itemRenderer={renderItem}
      onItemSelect={(t) => onChange(t.name)}
      noResults={<MenuItem disabled roleStructure="listoption" text="No matching tags" />}
      popoverProps={{ minimal: true, placement: "bottom-start" }}
      popoverContentProps={{ className: "scope-popover" }}
      inputProps={{ placeholder: "Filter tags…", "aria-label": "Filter tags" }}
      resetOnClose
    >
      <Button icon="tag" endIcon="caret-down" text={value || "Any tag"} aria-label="Filter by tag" className="tag-filter-button" />
    </Select>
  );
}
