-- The publisher from Calibre, for the library list (column, filter, sort) and to scope searches. Existing books get it
-- on the next library sync, without being re-indexed.
ALTER TABLE books ADD COLUMN publisher text;

CREATE INDEX books_publisher_idx ON books (publisher);
