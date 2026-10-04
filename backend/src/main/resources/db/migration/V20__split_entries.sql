-- A split entry: one payment shared among categories (a receipt: groceries and household). Each
-- part is an ordinary entry with its own category and amount, so budgets, reports and exports count
-- the parts as they are; the parts of one payment share a split_group. Transfers are not split.
ALTER TABLE cash_entry
    ADD COLUMN split_group UUID,
    ADD CONSTRAINT cash_entry_split_kind CHECK (split_group IS NULL OR kind <> 'TRANSFER');
CREATE INDEX ix_cash_entry_split_group ON cash_entry (user_id, split_group) WHERE split_group IS NOT NULL;
