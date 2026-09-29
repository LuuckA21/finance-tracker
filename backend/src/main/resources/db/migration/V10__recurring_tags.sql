-- Tags on recurring rules: every entry a rule creates gets the rule's tags. Deleting the rule or
-- the tag just removes the link; entries already created keep their own tags.
CREATE TABLE recurring_entry_tag (
    rule_id BIGINT NOT NULL REFERENCES recurring_entry (id) ON DELETE CASCADE,
    tag_id  BIGINT NOT NULL REFERENCES tag (id) ON DELETE CASCADE,
    PRIMARY KEY (rule_id, tag_id)
);

CREATE INDEX ix_recurring_entry_tag_tag ON recurring_entry_tag (tag_id);
