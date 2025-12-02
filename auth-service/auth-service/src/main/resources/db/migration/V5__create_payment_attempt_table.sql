CREATE TABLE IF NOT EXISTS payment_attempt (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    amount_minor        BIGINT NOT NULL,
    currency            VARCHAR(16) NOT NULL,
    plan_code           VARCHAR(64),
    status              VARCHAR(32) NOT NULL,
    provider            VARCHAR(64),
    provider_session_id VARCHAR(128),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);


