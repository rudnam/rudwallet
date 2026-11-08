-- A category can sit under another of the user's categories of the same
-- kind, one level deep (wallet.Store checks both). Deleting a main category
-- makes its subcategories main ones. The previous release doesn't read the
-- column, and the categories it adds are main ones.
ALTER TABLE categories ADD COLUMN parent_id BIGINT REFERENCES categories (id) ON DELETE SET NULL;
CREATE INDEX categories_parent_id_idx ON categories (parent_id) WHERE parent_id IS NOT NULL;
