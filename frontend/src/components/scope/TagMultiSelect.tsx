import { MenuItem } from "@blueprintjs/core";
import { MultiSelect, type ItemPredicate, type ItemRenderer } from "@blueprintjs/select";
import { useEffect, useState } from "react";
import { api, errorMessage } from "../../api/client";
import type { TagCount } from "../../api/types";

interface Props {
  selected: string[];
  onChange: (tags: string[]) => void;
}

let tagCache: Promise<TagCount[]> | undefined;

/** Tags rarely change; fetch once per page load (retry on failure). */
export function loadTags(): Promise<TagCount[]> {
  tagCache ??= api.tags().catch((err) => {
    tagCache = undefined;
    throw err;
  });
  return tagCache;
}

export function useTags() {
  const [tags, setTags] = useState<TagCount[]>([]);
  const [error, setError] = useState<string>();
  useEffect(() => {
    let alive = true;
    loadTags()
      .then((t) => alive && setTags(t))
      .catch((err) => alive && setError(errorMessage(err)));
    return () => {
      alive = false;
    };
  }, []);
  return { tags, error };
}

const predicate: ItemPredicate<TagCount> = (query, tag) => tag.name.toLowerCase().includes(query.toLowerCase());

export function TagMultiSelect({ selected, onChange }: Props) {
  const { tags, error } = useTags();
  const selectedItems = selected.map((name) => tags.find((t) => t.name === name) ?? { name, count: 0 });
  const isSelected = (t: TagCount) => selected.includes(t.name);

  const renderItem: ItemRenderer<TagCount> = (tag, { handleClick, handleFocus, modifiers, ref }) => {
    if (!modifiers.matchesPredicate) return null;
    return (
      <MenuItem
        key={tag.name}
        ref={ref}
        roleStructure="listoption"
        selected={isSelected(tag)}
        active={modifiers.active}
        onClick={handleClick}
        onFocus={handleFocus}
        text={tag.name}
        label={String(tag.count)}
      />
    );
  };

  return (
    <MultiSelect<TagCount>
      className="scope-tags"
      items={tags}
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
        leftIcon: "tag",
        inputProps: { "aria-label": "Limit to tags" },
      }}
      placeholder={selected.length ? "" : "Any tag"}
      resetOnSelect
      popoverProps={{ minimal: true, placement: "bottom-start" }}
      popoverContentProps={{ className: "scope-popover" }}
      noResults={<MenuItem disabled roleStructure="listoption" text={error ? `Error: ${error}` : "No matching tags"} />}
      fill
    />
  );
}
