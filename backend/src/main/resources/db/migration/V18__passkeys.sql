-- Passkeys (WebAuthn): sign in with the device's own lock (face, fingerprint, PIN) instead of
-- password and 2FA code. The server keeps only public keys; removed with the user.

-- The id the authenticators store for the user: random, so it says nothing about the account
ALTER TABLE app_user ADD COLUMN webauthn_user_handle BYTEA UNIQUE;

CREATE TABLE passkey (
    id                 BIGSERIAL PRIMARY KEY,
    user_id            BIGINT       NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    credential_id      BYTEA        NOT NULL UNIQUE,
    -- Attested credential data (AAGUID, credential id, COSE public key) as the authenticator sent it
    credential_data    BYTEA        NOT NULL,
    sign_count         BIGINT       NOT NULL DEFAULT 0,
    uv_initialized     BOOLEAN      NOT NULL,
    backup_eligible    BOOLEAN      NOT NULL,
    backed_up          BOOLEAN      NOT NULL,
    transports         VARCHAR(100),
    name               VARCHAR(64)  NOT NULL CHECK (length(btrim(name)) > 0),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_used_at       TIMESTAMPTZ
);

CREATE INDEX ix_passkey_user ON passkey (user_id);
