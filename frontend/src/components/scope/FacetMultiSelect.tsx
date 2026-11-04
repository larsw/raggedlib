import { MenuItem } from "@blueprintjs/core";
import { MultiSelect, type ItemPredicate, type ItemRenderer } from "@blueprintjs/select";
import type { TagCount } from "../../api/types";
import { FACET_LABELS, useFacet, type Facet } from "../../hooks/useFacet";

interface Props {
  facet: Facet;
  selected: string[];
  onChange: (values: string[]) => void;
}

const predicate: ItemPredicate<TagCount> = (query, item) => item.name.toLowerCase().includes(query.toLowerCase());

/** Retrieval scope: limit Ask/Search to books having ANY of the chosen tags (or publishers). */
export function FacetMultiSelect({ facet, selected, onChange }: Props) {
  const { items, error } = useFacet(facet);
  const labels = FACET_LABELS[facet];
  const selectedItems = selected.map((name) => items.find((t) => t.name === name) ?? { name, count: 0 });
  const isSelected = (t: TagCount) => selected.includes(t.name);

  const renderItem: ItemRenderer<TagCount> = (item, { handleClick, handleFocus, modifiers, ref }) => {
    if (!modifiers.matchesPredicate) return null;
    return (
      <MenuItem
        key={item.name}
        ref={ref}
        roleStructure="listoption"
        selected={isSelected(item)}
        active={modifiers.active}
        onClick={handleClick}
        onFocus={handleFocus}
        text={item.name}
        label={String(item.count)}
      />
    );
  };

  return (
    <MultiSelect<TagCount>
      className={`scope-${facet}`}
      items={items}
      selectedItems={selectedItems}
      itemsEqual="name"
      itemPredicate={predicate}
      itemRenderer={renderItem}
      onItemSelect={(t) => onChange(isSelected(t) ? selected.filter((n) => n !== t.name) : [...selected, t.name])}
      onRemove={(t) => onChange(selected.filter((n) => n !== t.name))}
      onClear={() => onChange([])}
      tagRenderer={(t) => t.name}
      tagInputProps={{
        tagProps: { minimal: true },
        leftIcon: labels.icon,
        inputProps: { "aria-label": labels.limitTo },
      }}
      placeholder={selected.length ? "" : labels.any}
      resetOnSelect
      popoverProps={{ minimal: true, placement: "bottom-start" }}
      popoverContentProps={{ className: "scope-popover" }}
      noResults={<MenuItem disabled roleStructure="listoption" text={error ? `Error: ${error}` : labels.noMatch} />}
      fill
    />
  );
}
