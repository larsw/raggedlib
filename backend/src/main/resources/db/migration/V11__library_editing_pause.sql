ALTER TABLE libraries ADD COLUMN editing_paused boolean NOT NULL DEFAULT false;

ALTER TABLE book_activity ALTER COLUMN book_id DROP NOT NULL;
ALTER TABLE book_activity ALTER COLUMN calibre_id DROP NOT NULL;
ALTER TABLE book_activity ALTER COLUMN book_title DROP NOT NULL;
