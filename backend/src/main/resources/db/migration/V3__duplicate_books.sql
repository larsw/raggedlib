-- Books whose text is (almost) all in another indexed book: status DUPLICATE, no passages of their own,
-- duplicate_of = the copy that is kept. Searches restricted to a duplicate use that copy.
ALTER TABLE books DROP CONSTRAINT books_status_check;
ALTER TABLE books ADD CONSTRAINT books_status_check
    CHECK (status IN ('PENDING', 'EXTRACTING', 'EMBEDDING', 'INDEXED', 'FAILED', 'SKIPPED', 'DUPLICATE'));
ALTER TABLE books ADD COLUMN duplicate_of bigint REFERENCES books (id) ON DELETE SET NULL;
CREATE INDEX books_duplicate_of_idx ON books (duplicate_of) WHERE duplicate_of IS NOT NULL;

-- finds the books that already contain a new book's passages (and identical passages within results)
CREATE INDEX vector_store_content_md5_idx ON vector_store (md5(content));
