-- Multiple Calibre libraries: each book now belongs to one, identified by its own (per-library) Calibre id.
CREATE TABLE libraries (
    id         bigserial PRIMARY KEY,
    name       text        NOT NULL,
    path       text        NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE books ADD COLUMN library_id bigint REFERENCES libraries (id) ON DELETE CASCADE;
ALTER TABLE books ADD COLUMN calibre_id bigint;

CREATE UNIQUE INDEX books_library_calibre_idx ON books (library_id, calibre_id);
CREATE INDEX books_library_idx ON books (library_id);

-- books.id was the Calibre id of the one, single library this app used to support. It becomes a plain surrogate
-- key: existing rows (and vector_store.book_id, which references them) keep their value unchanged, but new books
-- get one assigned from a sequence seeded above the current max, so ids stay unique across every library.
CREATE SEQUENCE books_id_seq OWNED BY books.id;
SELECT setval('books_id_seq', (SELECT coalesce(max(id), 1) FROM books), (SELECT max(id) IS NOT NULL FROM books));
ALTER TABLE books ALTER COLUMN id SET DEFAULT nextval('books_id_seq');

-- library_id/calibre_id for rows that predate this migration are backfilled in app code (LibraryRegistry), once it
-- knows which configured library they belong to (raggedlib.library-path, kept for this one-time migration).
