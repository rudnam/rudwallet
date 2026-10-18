-- Tags on records (e.g. "Trip to Osaka", "Work"): free text the user picks,
-- any number of them per record, matched ignoring case. Each record keeps
-- its own list, so there is no table of tags: GET /api/tags lists the ones
-- in use. Deleted records keep theirs, so an undone delete brings them back.
ALTER TABLE transactions ADD COLUMN tags TEXT[] NOT NULL DEFAULT '{}';
ALTER TABLE deleted_transactions ADD COLUMN tags TEXT[] NOT NULL DEFAULT '{}';
