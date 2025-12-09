ALTER TABLE nvr_device
    ADD COLUMN IF NOT EXISTS timezone VARCHAR(64) NOT NULL DEFAULT 'UTC';

UPDATE nvr_device
SET timezone = 'UTC'
WHERE timezone IS NULL;










