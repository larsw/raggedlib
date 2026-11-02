// Types for the parts of foliate-js (https://github.com/johnfactotum/foliate-js) the reader uses; it ships none.

declare module "foliate-js/view.js" {
  export interface TocItem {
    id?: number;
    label: string;
    href: string;
    subitems?: TocItem[] | null;
  }

  export interface BookSection {
    id?: string;
    linear?: string;
    cfi?: string;
    createDocument?: () => Promise<Document>;
  }

  export interface ResolvedTarget {
    index: number;
    anchor?: (doc: Document) => Element | Range | null;
  }

  export interface Book {
    metadata?: { title?: unknown; language?: string | string[] };
    toc?: TocItem[];
    sections: BookSection[];
    dir?: string;
    resolveHref?: (href: string) => ResolvedTarget | null;
  }

  export interface RelocateDetail {
    fraction?: number;
    section?: { current: number; total: number };
    tocItem?: TocItem | null;
    cfi: string;
    range?: Range;
  }

  export interface Renderer extends HTMLElement {
    setStyles(styles: string | [string, string]): void;
    getContents(): { doc: Document; index: number }[];
    focusView?(): void;
  }

  /** Annotation drawing callback handed out by the "draw-annotation" event. */
  export type Draw = (fn: unknown, options?: Record<string, unknown>) => void;

  export class View extends HTMLElement {
    book: Book;
    renderer: Renderer;
    lastLocation: RelocateDetail | null;
    open(book: File | Blob | string): Promise<void>;
    close(): void;
    init(options: { lastLocation?: string | null; showTextStart?: boolean }): Promise<void>;
    goTo(target: string | number): Promise<ResolvedTarget | undefined>;
    goToFraction(fraction: number): Promise<void>;
    goToTextStart(): Promise<void>;
    prev(): Promise<void>;
    next(): Promise<void>;
    goLeft(): Promise<void>;
    goRight(): Promise<void>;
    getCFI(index: number, range?: Range): string;
    addAnnotation(annotation: { value: string }): Promise<unknown>;
    deleteAnnotation(annotation: { value: string }): Promise<unknown>;
  }

  export class UnsupportedTypeError extends Error {}
}

declare module "foliate-js/overlayer.js" {
  export class Overlayer {
    static highlight: unknown;
  }
}
