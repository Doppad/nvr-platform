-- Добавление полей camera_quota, is_addon, price_minor и currency в subscription_plan (если их еще нет)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'subscription_plan' AND column_name = 'camera_quota') THEN
        ALTER TABLE subscription_plan ADD COLUMN camera_quota INTEGER;
    END IF;
    
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'subscription_plan' AND column_name = 'is_addon') THEN
        ALTER TABLE subscription_plan ADD COLUMN is_addon BOOLEAN NOT NULL DEFAULT false;
    END IF;
    
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'subscription_plan' AND column_name = 'price_minor') THEN
        ALTER TABLE subscription_plan ADD COLUMN price_minor BIGINT;
    END IF;
    
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'subscription_plan' AND column_name = 'currency') THEN
        ALTER TABLE subscription_plan ADD COLUMN currency VARCHAR(8) DEFAULT 'RUB';
    END IF;
END $$;

-- Добавление планов подписок для расширения архива камер
-- CAM_1 - расширение архива на 1 камеру (30 дней)
-- CAM_3 - расширение архива на 3 камеры (30 дней)
-- Цены указаны в копейках (100000 = 1000 рублей)

INSERT INTO subscription_plan(code, title, archive_days, max_cameras, camera_quota, is_addon, price_minor, currency, created_at)
VALUES 
    ('CAM_1', 'Расширение архива на 1 камеру', 30, 1, 1, true, 100000, 'RUB', now()),
    ('CAM_3', 'Расширение архива на 3 камеры', 30, 3, 3, true, 250000, 'RUB', now())
ON CONFLICT (code) DO NOTHING;

