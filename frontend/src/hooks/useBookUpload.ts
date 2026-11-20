import { Intent } from "@blueprintjs/core";
import { useMemo, useRef, useState } from "react";
import type { Library } from "../api/types";
import { showToast } from "../components/toaster";
import { canEditBooksIn, curatesWritable } from "../lib/auth";

/**
 * Adding books on the Library tab: the Add books button, dropping files anywhere on the tab, and the upload dialog.
 * An upload with duplicates still to decide outlives the dialog: it can be hidden (to edit one of the new books) and
 * brought back, until it's done.
 */
export function useBookUpload(libraries: Library[], current: Library | null) {
  const [files, setFiles] = useState<File[]>([]);
  const [open, setOpen] = useState(false);
  const [initialLibraryId, setInitialLibraryId] = useState<number | null>(null);
  const [pendingCount, setPendingCount] = useState(0);
  const [dragging, setDragging] = useState(false);
  const dragDepth = useRef(0);

  const curated = useMemo(() => libraries.filter(curatesWritable), [libraries]);
  const editable = useMemo(() => curated.filter(canEditBooksIn), [curated]);
  /** Shown to curators of a writable library, paused or not, so a pause is visible rather than a missing button. */
  const visible = curated.length > 0;
  const blockedReason = visible && editable.length === 0
    ? `Editing is paused in ${curated.map((library) => library.name).join(", ")}`
    : null;

  /** Starts an upload of [chosen]; while editing is paused, says so instead. */
  const begin = (chosen: File[]) => {
    if (blockedReason) {
      showToast({ message: `${blockedReason}; books can't be added now.`, intent: Intent.WARNING, icon: "pause" });
      return;
    }
    if (chosen.length === 0) return;
    if (pendingCount > 0) {
      setOpen(true);
      showToast({ message: "Choose what to do with the books still waiting before adding more.", intent: Intent.WARNING });
      return;
    }
    setFiles(chosen);
    setInitialLibraryId(current && editable.some((library) => library.id === current.id) ? current.id : null);
    setOpen(true);
  };

  const hasFiles = (event: React.DragEvent) => visible && Array.from(event.dataTransfer.types).includes("Files");
  const dropTarget = {
    onDragEnter: (event: React.DragEvent<HTMLElement>) => {
      if (!hasFiles(event)) return;
      event.preventDefault();
      dragDepth.current++;
      setDragging(true);
    },
    onDragOver: (event: React.DragEvent<HTMLElement>) => {
      if (!hasFiles(event)) return;
      event.preventDefault();
      event.dataTransfer.dropEffect = blockedReason ? "none" : "copy";
    },
    onDragLeave: (event: React.DragEvent<HTMLElement>) => {
      if (!hasFiles(event)) return;
      dragDepth.current = Math.max(0, dragDepth.current - 1);
      if (dragDepth.current === 0) setDragging(false);
    },
    onDrop: (event: React.DragEvent<HTMLElement>) => {
      if (!hasFiles(event)) return;
      event.preventDefault();
      dragDepth.current = 0;
      setDragging(false);
      begin(Array.from(event.dataTransfer.files));
    },
  };

  return {
    /** the Add books control is shown */
    visible,
    /** why books can't be added now (every writable library the caller curates is paused), or null */
    blockedReason,
    /** files are being dragged over the tab */
    dragging,
    /** duplicates of the last upload still waiting for a choice */
    pendingCount,
    begin,
    reopen: () => setOpen(true),
    dropTarget,
    dialog: {
      isOpen: open,
      files,
      initialLibraryId,
      libraries,
      /** hidden, but kept: duplicates are still waiting */
      onHide: () => setOpen(false),
      /** this upload is over */
      onDone: () => {
        setOpen(false);
        setFiles([]);
        setInitialLibraryId(null);
        setPendingCount(0);
      },
      onPendingChange: setPendingCount,
    },
  };
}
