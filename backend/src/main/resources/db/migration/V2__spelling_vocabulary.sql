-- Spelling correction for search queries: the words of the indexed passages with their document frequencies,
-- built from vector_store by SpellingVocabulary (ts_stat over the 'simple' configuration, i.e. unstemmed words).
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE search_vocabulary (
    word text PRIMARY KEY,
    ndoc integer NOT NULL -- passages containing the word
);

CREATE INDEX search_vocabulary_trgm_idx ON search_vocabulary USING gin (word gin_trgm_ops);

-- one row: when the vocabulary was last built, to rebuild it at startup after indexing it hasn't seen
CREATE TABLE search_vocabulary_meta (
    id       boolean PRIMARY KEY DEFAULT true CHECK (id),
    built_at timestamptz NOT NULL,
    words    integer     NOT NULL,
    params   text        NOT NULL -- settings the vocabulary was built with; a change triggers a rebuild
);
