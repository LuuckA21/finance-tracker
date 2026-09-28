-- Monthly spending limit of an expense category, the same every month. Removed with the category.
CREATE TABLE budget (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    category_id BIGINT         NOT NULL REFERENCES category (id) ON DELETE CASCADE,
    amount      NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    currency    VARCHAR(3)     NOT NULL,
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ux_budget_category UNIQUE (category_id)
);

CREATE INDEX ix_budget_user ON budget (user_id);
