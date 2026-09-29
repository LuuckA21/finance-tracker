-- Free labels on entries ("Holidays 2026", "Wedding"), cutting across categories. Names are the
-- user's own and unique regardless of case.
CREATE TABLE tag (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    name       VARCHAR(40) NOT NULL CHECK (length(btrim(name)) > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_tag_user_name ON tag (user_id, lower(name));

-- Deleting a tag or an entry just removes the link.
CREATE TABLE cash_entry_tag (
    entry_id BIGINT NOT NULL REFERENCES cash_entry (id) ON DELETE CASCADE,
    tag_id   BIGINT NOT NULL REFERENCES tag (id) ON DELETE CASCADE,
    PRIMARY KEY (entry_id, tag_id)
);

CREATE INDEX ix_cash_entry_tag_tag ON cash_entry_tag (tag_id);
