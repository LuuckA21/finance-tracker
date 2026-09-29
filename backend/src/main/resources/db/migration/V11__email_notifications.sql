-- Email notifications: a verified address per user, which alerts they want, and the alerts already sent.
CREATE TABLE notification_settings (
    user_id          BIGINT PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    -- Verified address; notifications go only here
    email            VARCHAR(254),
    -- Address waiting for its code, and the code (hashed) sent to it
    pending_email    VARCHAR(254),
    code_hash        VARCHAR(100),
    code_expires_at  TIMESTAMPTZ,
    code_attempts    INT         NOT NULL DEFAULT 0,
    code_sent_at     TIMESTAMPTZ,
    test_sent_at     TIMESTAMPTZ,
    budget_alerts    BOOLEAN     NOT NULL DEFAULT TRUE,
    goal_alerts      BOOLEAN     NOT NULL DEFAULT TRUE,
    monthly_summary  BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per alert already handled (sent, or current when notifications were switched on), so none repeats
CREATE TABLE notification_sent (
    user_id    BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    alert_key  VARCHAR(100) NOT NULL,
    sent_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, alert_key)
);
