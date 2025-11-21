CREATE TABLE IF NOT EXISTS nvr_device (
    id              BIGSERIAL PRIMARY KEY,
    owner_id        BIGINT       NOT NULL,
    name            VARCHAR(255) NOT NULL,
    ip              inet         NOT NULL,
    port            INTEGER      NOT NULL,
    address         VARCHAR(512),
    vendor          VARCHAR(64),
    cameras_count   INTEGER      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- позже сюда же добавишь nvr_device_user, nvr_address и т.п.


