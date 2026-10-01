-- The user's rules to categorize entries by their description: a text the description contains
-- (ignoring case and accents) and the category it gets. Applied to imported rows the file gives no
-- usable category, and suggested while writing an entry. Removed with their category.
CREATE TABLE category_rule (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    pattern     VARCHAR(100) NOT NULL CHECK (length(btrim(pattern)) > 0),
    category_id BIGINT       NOT NULL REFERENCES category (id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_category_rule_user_pattern ON category_rule (user_id, lower(pattern));
CREATE INDEX ix_category_rule_category ON category_rule (category_id);
