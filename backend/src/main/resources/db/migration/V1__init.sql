CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE books (
    id                    bigint PRIMARY KEY,               -- calibre book id
    title                 text        NOT NULL,
    authors               text[]      NOT NULL DEFAULT '{}',
    tags                  text[]      NOT NULL DEFAULT '{}',
    series                text,
    series_index          double precision,
    languages             text[]      NOT NULL DEFAULT '{}',
    pubdate               timestamptz,
    formats               jsonb       NOT NULL DEFAULT '{}', -- {"PDF": "Author/Title (12)/file.pdf"} relative to library root
    source_format         text,
    cover_path            text,                              -- relative to library root
    calibre_last_modified timestamptz,
    status                text        NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'EXTRACTING', 'EMBEDDING', 'INDEXED', 'FAILED', 'SKIPPED')),
    chunk_count           integer     NOT NULL DEFAULT 0,
    error                 text,
    indexed_at            timestamptz,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX books_status_idx ON books (status);
CREATE INDEX books_tags_idx ON books USING gin (tags);

-- Chunk table, compatible with Spring AI PgVectorStore (id, content, metadata, embedding) plus extra columns.
CREATE TABLE vector_store (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    content     text,
    metadata    jsonb,
    embedding   vector(1024),
    book_id     bigint REFERENCES books (id) ON DELETE CASCADE,
    chunk_index integer,
    page        integer,
    section     text,
    content_tsv tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('english'::regconfig, coalesce(section, '')), 'B') ||
        setweight(to_tsvector('english'::regconfig, coalesce(content, '')), 'A')
    ) STORED
);

CREATE INDEX vector_store_embedding_idx ON vector_store USING hnsw (embedding vector_cosine_ops);
CREATE INDEX vector_store_book_idx ON vector_store (book_id, chunk_index);
CREATE INDEX vector_store_tsv_idx ON vector_store USING gin (content_tsv);
