-- A budget's period: a limit for each month (as before), each calendar quarter or each calendar year,
-- for spending that is not monthly (yearly health insurance, taxes, car insurance).
ALTER TABLE budget
    ADD COLUMN period VARCHAR(16) NOT NULL DEFAULT 'MONTHLY'
        CHECK (period IN ('MONTHLY', 'QUARTERLY', 'YEARLY'));
