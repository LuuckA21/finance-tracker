-- ============================================================================
-- Users & security
-- ============================================================================
CREATE TABLE app_user (
    id                       BIGSERIAL PRIMARY KEY,
    username                 VARCHAR(64)  NOT NULL,
    password_hash            VARCHAR(255) NOT NULL,
    role                     VARCHAR(16)  NOT NULL CHECK (role IN ('ADMIN', 'USER')),
    enabled                  BOOLEAN      NOT NULL DEFAULT TRUE,
    base_currency            VARCHAR(3)   NOT NULL DEFAULT 'CHF',
    password_change_required BOOLEAN      NOT NULL DEFAULT FALSE,
    totp_secret_enc          VARCHAR(512),
    totp_enabled             BOOLEAN      NOT NULL DEFAULT FALSE,
    totp_last_step           BIGINT,
    failed_login_attempts    INTEGER      NOT NULL DEFAULT 0,
    locked_until             TIMESTAMPTZ,
    last_login_at            TIMESTAMPTZ,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version                  BIGINT       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_app_user_username ON app_user (lower(username));

CREATE TABLE recovery_code (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    code_hash  VARCHAR(64) NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_recovery_code_user ON recovery_code (user_id);

CREATE TABLE login_event (
    id                 BIGSERIAL PRIMARY KEY,
    user_id            BIGINT REFERENCES app_user (id) ON DELETE CASCADE,
    username_attempted VARCHAR(64)  NOT NULL,
    ip_address         VARCHAR(64),
    user_agent         VARCHAR(255),
    success            BOOLEAN      NOT NULL,
    reason             VARCHAR(32)  NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_login_event_user_created ON login_event (user_id, created_at DESC);

-- ============================================================================
-- Cash flow
-- ============================================================================
CREATE TABLE category (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    name       VARCHAR(64)  NOT NULL,
    kind       VARCHAR(16)  NOT NULL CHECK (kind IN ('INCOME', 'EXPENSE')),
    color      VARCHAR(7)   NOT NULL DEFAULT '#6b7280',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_category_user_kind_name ON category (user_id, kind, lower(name));

CREATE TABLE cash_entry (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    entry_date  DATE           NOT NULL,
    kind        VARCHAR(16)    NOT NULL CHECK (kind IN ('INCOME', 'EXPENSE')),
    category_id BIGINT         NOT NULL REFERENCES category (id) ON DELETE RESTRICT,
    amount      NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    currency    VARCHAR(3)     NOT NULL,
    description VARCHAR(500),
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX ix_cash_entry_user_date ON cash_entry (user_id, entry_date DESC);
CREATE INDEX ix_cash_entry_category ON cash_entry (category_id);

-- ============================================================================
-- Net worth
-- ============================================================================
CREATE TABLE asset_position (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    name        VARCHAR(100) NOT NULL,
    symbol      VARCHAR(32),
    asset_class VARCHAR(16)  NOT NULL,
    currency    VARCHAR(3)   NOT NULL,
    notes       VARCHAR(1000),
    archived    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_asset_position_user ON asset_position (user_id);

CREATE TABLE position_snapshot (
    id            BIGSERIAL PRIMARY KEY,
    position_id   BIGINT         NOT NULL REFERENCES asset_position (id) ON DELETE CASCADE,
    user_id       BIGINT         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    snapshot_date DATE           NOT NULL,
    quantity      NUMERIC(38, 12) NOT NULL CHECK (quantity >= 0),
    unit_price    NUMERIC(38, 12) NOT NULL CHECK (unit_price >= 0),
    note          VARCHAR(500),
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ux_position_snapshot_date UNIQUE (position_id, snapshot_date)
);
CREATE INDEX ix_position_snapshot_user ON position_snapshot (user_id, snapshot_date);

CREATE TABLE exchange_rate (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT          NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    base_currency VARCHAR(3)      NOT NULL,
    currency      VARCHAR(3)      NOT NULL,
    rate_date     DATE            NOT NULL,
    rate          NUMERIC(28, 12) NOT NULL CHECK (rate > 0),
    created_at    TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT ux_exchange_rate UNIQUE (user_id, base_currency, currency, rate_date)
);

-- ============================================================================
-- Spring Session JDBC (schema-postgresql.sql from spring-session-jdbc)
-- ============================================================================
CREATE TABLE SPRING_SESSION (
    PRIMARY_ID            CHAR(36) NOT NULL,
    SESSION_ID            CHAR(36) NOT NULL,
    CREATION_TIME         BIGINT   NOT NULL,
    LAST_ACCESS_TIME      BIGINT   NOT NULL,
    MAX_INACTIVE_INTERVAL INT      NOT NULL,
    EXPIRY_TIME           BIGINT   NOT NULL,
    PRINCIPAL_NAME        VARCHAR(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);
CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36)     NOT NULL,
    ATTRIBUTE_NAME     VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES    BYTEA        NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
        REFERENCES SPRING_SESSION (PRIMARY_ID) ON DELETE CASCADE
);
