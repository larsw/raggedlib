ALTER TABLE books ADD COLUMN deleted_at timestamptz;

CREATE INDEX books_deleted_at_idx ON books (deleted_at) WHERE deleted_at IS NOT NULL;

CREATE VIEW active_books AS
SELECT *
FROM books
WHERE deleted_at IS NULL;
