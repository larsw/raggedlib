-- Append-only history of changes raggedlib makes to Calibre books.
CREATE TABLE book_activity (
    id         bigserial PRIMARY KEY,
    book_id    bigint      NOT NULL,
    library_id bigint      NOT NULL REFERENCES libraries (id) ON DELETE CASCADE,
    calibre_id bigint      NOT NULL,
    book_title text        NOT NULL,
    actor      text        NOT NULL,
    action     text        NOT NULL,
    changes    jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX book_activity_library_created_idx ON book_activity (library_id, created_at DESC);
CREATE INDEX book_activity_book_idx ON book_activity (book_id, created_at DESC);
