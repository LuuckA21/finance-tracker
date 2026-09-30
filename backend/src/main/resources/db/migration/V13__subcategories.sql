-- Categories come in two levels, like first- and second-level cost centres: a macro category
-- ("Casa") and, optionally, detail categories under it ("Affitto", "Energia"). An entry may sit on
-- either. A detail has the kind of its macro and cannot have details of its own; a macro with
-- details cannot be deleted before them.
ALTER TABLE category
    ADD COLUMN parent_id BIGINT REFERENCES category (id) ON DELETE RESTRICT;
CREATE INDEX ix_category_parent ON category (parent_id);

-- Macro names are unique per user and kind, detail names under their macro only
DROP INDEX ux_category_user_kind_name;
CREATE UNIQUE INDEX ux_category_macro_name ON category (user_id, kind, lower(name)) WHERE parent_id IS NULL;
CREATE UNIQUE INDEX ux_category_detail_name ON category (parent_id, lower(name)) WHERE parent_id IS NOT NULL;
