import { MenuItem } from "@blueprintjs/core";
import { MultiSelect, type ItemPredicate, type ItemRenderer } from "@blueprintjs/select";

interface Option {
  value: string;
  label: string;
}

interface Props {
  inputId: string;
  label: string;
  values: string[];
  suggestions: string[];
  onChange: (values: string[]) => void;
  language?: boolean;
}

const languageNames = new Intl.DisplayNames(["en"], { type: "language" });

function option(value: string, language: boolean): Option {
  return {
    value,
    label: language && /^[a-z]{2,3}$/i.test(value) ? languageNames.of(value) ?? value : value,
  };
}

export function MetadataValueSelect({ inputId, label, values, suggestions, onChange, language = false }: Props) {
  const items = [...new Set(suggestions)].map((value) => option(value, language));
  const selectedItems = values.map((value) => items.find((item) => item.value === value) ?? option(value, language));
  const isSelected = (item: Option) => values.includes(item.value);
  const predicate: ItemPredicate<Option> = (query, item) =>
    item.label.toLowerCase().includes(query.toLowerCase()) || item.value.toLowerCase().includes(query.toLowerCase());
  const renderItem: ItemRenderer<Option> = (item, { handleClick, handleFocus, modifiers, ref }) => {
    if (!modifiers.matchesPredicate) return null;
    return (
      <MenuItem
        key={item.value}
        ref={ref}
        roleStructure="listoption"
        selected={isSelected(item)}
        active={modifiers.active}
        onClick={handleClick}
        onFocus={handleFocus}
        text={item.label}
        label={language ? item.value : undefined}
      />
    );
  };

  const createOption = (query: string) => {
    const value = query.trim();
    if (!value || (language && !/^[a-z]{2,3}$/i.test(value))) return [];
    return [items.find((item) => item.value.toLowerCase() === value.toLowerCase()) ?? option(language ? value.toLowerCase() : value, language)];
  };

  return (
    <MultiSelect<Option>
      items={items}
      selectedItems={selectedItems}
      itemsEqual="value"
      itemPredicate={predicate}
      itemRenderer={renderItem}
      createNewItemFromQuery={createOption}
      createNewItemRenderer={(query, active, handleClick) => {
        const value = query.trim();
        if (language && !/^[a-z]{2,3}$/i.test(value)) return undefined;
        return (
          <MenuItem
            roleStructure="listoption"
            active={active}
            onClick={handleClick}
            text={language ? `Add language code "${value.toLowerCase()}"` : `Add "${value}"`}
          />
        );
      }}
      onItemSelect={(item) =>
        onChange(isSelected(item) ? values.filter((value) => value !== item.value) : [...values, item.value])
      }
      onRemove={(item) => onChange(values.filter((value) => value !== item.value))}
      onClear={() => onChange([])}
      tagRenderer={(item) => item.label}
      tagInputProps={{
        tagProps: { minimal: true },
        inputProps: { id: inputId, "aria-label": label },
      }}
      placeholder={values.length ? "" : `Add ${label.toLowerCase()}`}
      resetOnSelect
      popoverProps={{ minimal: true, placement: "bottom-start" }}
      noResults={
        <MenuItem
          disabled
          roleStructure="listoption"
          text={language ? "No match; enter a 2- or 3-letter ISO language code." : "No matching values; enter a new value."}
        />
      }
      fill
    />
  );
}
