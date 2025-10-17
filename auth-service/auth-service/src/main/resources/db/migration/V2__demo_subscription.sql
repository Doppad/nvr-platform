-- дам PRO на 30 дней пользователю с email test@example.com
WITH u AS (
  SELECT id FROM app_user WHERE email = 'test@example.com'
)
INSERT INTO user_subscription(user_id, plan_id, starts_at, ends_at, is_active)
SELECT u.id, p.id, now(), now() + interval '30 days', true
FROM u JOIN subscription_plan p ON p.code='PRO'
ON CONFLICT DO NOTHING;