-- A duplicate's passages are its copy's, shown to readers of the duplicate's library as its own. Across libraries
-- only an exact copy counts now (Duplicates.keeper); one decided under the old rule (90% of its passages in a copy
-- that may hold more) could show passages of a library its readers can't see. Each is indexed again: an exact copy
-- becomes a duplicate again without embedding anything, any other gets passages of its own.
UPDATE books d
SET status = 'PENDING', error = NULL, duplicate_of = NULL, updated_at = now()
FROM books k
WHERE d.duplicate_of = k.id
  AND d.library_id <> k.library_id
  AND d.deleted_at IS NULL;
