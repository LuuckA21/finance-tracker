-- Savings goals: reach a balance on some positions (optionally by a date), or put a yearly amount
-- into them (e.g. the pillar 3a maximum). Progress is computed from the positions' values and
-- from the transfers into them; nothing else is stored.
CREATE TABLE savings_goal (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    name          VARCHAR(100)   NOT NULL,
    kind          VARCHAR(16)    NOT NULL CHECK (kind IN ('BALANCE', 'YEARLY')),
    target_amount NUMERIC(19, 4) NOT NULL CHECK (target_amount > 0),
    currency      VARCHAR(3)     NOT NULL,
    target_date   DATE,
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    -- A yearly goal restarts every 1 January: a deadline would mean nothing
    CONSTRAINT ck_savings_goal_date CHECK (kind = 'BALANCE' OR target_date IS NULL)
);

CREATE INDEX ix_savings_goal_user ON savings_goal (user_id);

-- Positions whose value (BALANCE) or incoming transfers (YEARLY) count towards a goal.
-- Deleting a position just removes it from its goals.
CREATE TABLE savings_goal_position (
    goal_id     BIGINT NOT NULL REFERENCES savings_goal (id) ON DELETE CASCADE,
    position_id BIGINT NOT NULL REFERENCES asset_position (id) ON DELETE CASCADE,
    PRIMARY KEY (goal_id, position_id)
);

CREATE INDEX ix_savings_goal_position_position ON savings_goal_position (position_id);
