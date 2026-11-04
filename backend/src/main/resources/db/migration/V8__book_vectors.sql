-- One vector per book: the average of its passages' embeddings, normalized to unit length like they are, for the
-- Vectors view's per-book level. Derived data: computed from vector_store (when a book is indexed, or on demand
-- for books indexed before this existed), never re-embedded.
CREATE TABLE book_vectors (
    book_id     bigint PRIMARY KEY REFERENCES books (id) ON DELETE CASCADE,
    embedding   vector(1024) NOT NULL,
    passages    int          NOT NULL,
    computed_at timestamptz  NOT NULL DEFAULT now()
);
