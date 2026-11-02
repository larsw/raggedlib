import { HTMLSelect } from "@blueprintjs/core";
import { useApp } from "../AppContext";

const ALL = "";

/** Navbar control: narrows the whole app (Library, Indexing, Ask, Search) to one library, or the union of all. */
export function LibrarySelect() {
  const { libraries, libraryId, setLibraryId, librariesLoading } = useApp();
  if (!librariesLoading && libraries.length <= 1) return null; // nothing to choose between

  return (
    <HTMLSelect
      className="library-select"
      aria-label="Library"
      value={libraryId ?? ALL}
      disabled={librariesLoading}
      onChange={(e) => setLibraryId(e.currentTarget.value === ALL ? null : Number(e.currentTarget.value))}
      options={[
        { value: ALL, label: "All libraries" },
        ...libraries.map((l) => ({ value: l.id, label: `${l.name} (${l.bookCount})` })),
      ]}
    />
  );
}
