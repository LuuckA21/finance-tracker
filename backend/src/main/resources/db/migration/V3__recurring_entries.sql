-- Recurring income and expenses: each rule creates ordinary cash entries when they fall due.
CREATE TABLE recurring_entry (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    kind           VARCHAR(16)    NOT NULL CHECK (kind IN ('INCOME', 'EXPENSE')),
    category_id    BIGINT         NOT NULL REFERENCES category (id) ON DELETE RESTRICT,
    amount         NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    currency       VARCHAR(3)     NOT NULL,
    description    VARCHAR(500),
    frequency      VARCHAR(16)    NOT NULL CHECK (frequency IN
                       ('DAILY', 'WEEKLY', 'MONTHLY', 'QUARTERLY', 'FOUR_MONTHLY', 'SEMIANNUAL', 'YEARLY')),
    start_date     DATE           NOT NULL,
    end_date       DATE CHECK (end_date IS NULL OR end_date >= start_date),
    -- Last occurrence handled; the next one is computed from start_date and frequency
    last_generated DATE,
    active         BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    version        BIGINT         NOT NULL DEFAULT 0
);
CREATE INDEX ix_recurring_entry_user ON recurring_entry (user_id);
CREATE INDEX ix_recurring_entry_category ON recurring_entry (category_id);

-- Entries created by a rule keep a link to it; deleting the rule keeps the entries
ALTER TABLE cash_entry ADD COLUMN recurring_entry_id BIGINT REFERENCES recurring_entry (id) ON DELETE SET NULL;
CREATE UNIQUE INDEX ux_cash_entry_recurring_date ON cash_entry (recurring_entry_id, entry_date)
    WHERE recurring_entry_id IS NOT NULL;
