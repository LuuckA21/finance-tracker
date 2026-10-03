-- The IBAN of a bank account position, in its electronic form (no spaces): a bank statement
-- (camt.053) names the account by it, so its closing balance can update the position.
ALTER TABLE asset_position ADD COLUMN iban VARCHAR(34);
CREATE UNIQUE INDEX ux_asset_position_user_iban ON asset_position (user_id, iban) WHERE iban IS NOT NULL;
