-- Euro foreign exchange reference rates published daily by the European Central Bank:
-- 1 EUR = rate CURRENCY. Shared by every user; a user's own rates (exchange_rate) take priority.
CREATE TABLE central_exchange_rate (
    currency   VARCHAR(3)      NOT NULL,
    rate_date  DATE            NOT NULL,
    rate       NUMERIC(28, 12) NOT NULL CHECK (rate > 0),
    fetched_at TIMESTAMPTZ     NOT NULL DEFAULT now(),
    PRIMARY KEY (currency, rate_date)
);

CREATE INDEX ix_central_exchange_rate_date ON central_exchange_rate (rate_date);
