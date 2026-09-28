-- Transfers between the user's own accounts and investments: a third kind of entry. They have no
-- category, may name the positions money moved from and to, and are neither income nor expense.
ALTER TABLE cash_entry
    DROP CONSTRAINT cash_entry_kind_check,
    ADD CONSTRAINT cash_entry_kind_check CHECK (kind IN ('INCOME', 'EXPENSE', 'TRANSFER')),
    ALTER COLUMN category_id DROP NOT NULL,
    ADD COLUMN from_position_id BIGINT REFERENCES asset_position (id) ON DELETE SET NULL,
    ADD COLUMN to_position_id   BIGINT REFERENCES asset_position (id) ON DELETE SET NULL,
    -- Income/expense: a category and no positions; transfer: no category
    ADD CONSTRAINT cash_entry_kind_shape CHECK (
        (kind = 'TRANSFER' AND category_id IS NULL)
        OR (kind <> 'TRANSFER' AND category_id IS NOT NULL
            AND from_position_id IS NULL AND to_position_id IS NULL)),
    ADD CONSTRAINT cash_entry_transfer_distinct CHECK (from_position_id <> to_position_id);

CREATE INDEX ix_cash_entry_from_position ON cash_entry (from_position_id) WHERE from_position_id IS NOT NULL;
CREATE INDEX ix_cash_entry_to_position ON cash_entry (to_position_id) WHERE to_position_id IS NOT NULL;

ALTER TABLE recurring_entry
    DROP CONSTRAINT recurring_entry_kind_check,
    ADD CONSTRAINT recurring_entry_kind_check CHECK (kind IN ('INCOME', 'EXPENSE', 'TRANSFER')),
    ALTER COLUMN category_id DROP NOT NULL,
    ADD COLUMN from_position_id BIGINT REFERENCES asset_position (id) ON DELETE SET NULL,
    ADD COLUMN to_position_id   BIGINT REFERENCES asset_position (id) ON DELETE SET NULL,
    ADD CONSTRAINT recurring_entry_kind_shape CHECK (
        (kind = 'TRANSFER' AND category_id IS NULL)
        OR (kind <> 'TRANSFER' AND category_id IS NOT NULL
            AND from_position_id IS NULL AND to_position_id IS NULL)),
    ADD CONSTRAINT recurring_entry_transfer_distinct CHECK (from_position_id <> to_position_id);

CREATE INDEX ix_recurring_entry_from_position ON recurring_entry (from_position_id) WHERE from_position_id IS NOT NULL;
CREATE INDEX ix_recurring_entry_to_position ON recurring_entry (to_position_id) WHERE to_position_id IS NOT NULL;
