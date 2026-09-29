-- Forecast scenarios: a coming year from a base period (the year before, or the last twelve complete
-- months until it is over) with a growth in percent for income and expenses, and extra items such as
-- a new rent. Only the scenario is stored; the forecast is computed from the entries on request.
CREATE TABLE forecast_scenario (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    name           VARCHAR(100)  NOT NULL CHECK (length(btrim(name)) > 0),
    forecast_year  INTEGER       NOT NULL CHECK (forecast_year BETWEEN 2000 AND 2200),
    income_growth  NUMERIC(7, 2) NOT NULL CHECK (income_growth BETWEEN -100 AND 1000),
    expense_growth NUMERIC(7, 2) NOT NULL CHECK (expense_growth BETWEEN -100 AND 1000),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX ix_forecast_scenario_user ON forecast_scenario (user_id);

-- Tags and categories whose entries stay out of the base (a one-off trip, a move). Deleting the tag
-- or the category just removes it from the scenarios.
CREATE TABLE forecast_excluded_tag (
    scenario_id BIGINT NOT NULL REFERENCES forecast_scenario (id) ON DELETE CASCADE,
    tag_id      BIGINT NOT NULL REFERENCES tag (id) ON DELETE CASCADE,
    PRIMARY KEY (scenario_id, tag_id)
);

CREATE TABLE forecast_excluded_category (
    scenario_id BIGINT NOT NULL REFERENCES forecast_scenario (id) ON DELETE CASCADE,
    category_id BIGINT NOT NULL REFERENCES category (id) ON DELETE CASCADE,
    PRIMARY KEY (scenario_id, category_id)
);

-- Extra items in the base currency: every month from start to end (December when missing), or once.
-- A negative amount takes something away (a subscription cancelled). Without a category an item
-- stands on its own; deleting its category keeps it that way.
CREATE TABLE forecast_item (
    scenario_id BIGINT         NOT NULL REFERENCES forecast_scenario (id) ON DELETE CASCADE,
    sort_order  INTEGER        NOT NULL,
    description VARCHAR(100)   NOT NULL CHECK (length(btrim(description)) > 0),
    kind        VARCHAR(16)    NOT NULL CHECK (kind IN ('INCOME', 'EXPENSE')),
    category_id BIGINT REFERENCES category (id) ON DELETE SET NULL,
    amount      NUMERIC(19, 4) NOT NULL CHECK (amount <> 0),
    schedule    VARCHAR(16)    NOT NULL CHECK (schedule IN ('MONTHLY', 'ONCE')),
    start_month INTEGER        NOT NULL CHECK (start_month BETWEEN 1 AND 12),
    end_month   INTEGER CHECK (end_month BETWEEN 1 AND 12),
    PRIMARY KEY (scenario_id, sort_order),
    CONSTRAINT ck_forecast_item_months CHECK (end_month IS NULL OR (schedule = 'MONTHLY' AND end_month >= start_month))
);
