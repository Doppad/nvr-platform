CREATE TABLE app_user (
  id           BIGSERIAL PRIMARY KEY,
  email        VARCHAR(128) UNIQUE,
  phone        VARCHAR(32) UNIQUE,
  pass_hash    TEXT,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  is_active    BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE otp_attempt (
  id           BIGSERIAL PRIMARY KEY,
  user_id      BIGINT REFERENCES app_user(id) ON DELETE CASCADE,
  target       VARCHAR(128) NOT NULL,
  code_hash    TEXT NOT NULL,
  expires_at   TIMESTAMPTZ NOT NULL,
  is_used      BOOLEAN NOT NULL DEFAULT FALSE,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE subscription_plan (
  id              BIGSERIAL PRIMARY KEY,
  code            VARCHAR(32) UNIQUE NOT NULL,
  title           VARCHAR(64) NOT NULL,
  archive_days    INT NOT NULL,
  max_cameras     INT NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE user_subscription (
  id              BIGSERIAL PRIMARY KEY,
  user_id         BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  plan_id         BIGINT NOT NULL REFERENCES subscription_plan(id),
  starts_at       TIMESTAMPTZ NOT NULL,
  ends_at         TIMESTAMPTZ NOT NULL,
  is_active       BOOLEAN NOT NULL DEFAULT TRUE,
  CHECK (ends_at > starts_at)
);

INSERT INTO subscription_plan(code, title, archive_days, max_cameras)
VALUES ('FREE','Free',14,1),
       ('PRO','Pro',30,8)
ON CONFLICT (code) DO NOTHING;
