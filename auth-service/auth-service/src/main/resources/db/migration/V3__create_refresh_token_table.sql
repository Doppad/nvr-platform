CREATE TABLE refresh_token (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT      NOT NULL,
    token        VARCHAR(255) NOT NULL UNIQUE,
    expires_at   TIMESTAMPTZ  NOT NULL,
    revoked_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    user_agent   VARCHAR(255),
    ip_address   VARCHAR(64)
);

CREATE INDEX idx_refresh_token_token ON refresh_token(token);
CREATE INDEX idx_refresh_token_user_id ON refresh_token(user_id);