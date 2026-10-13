-- P1 RBAC hardening: ensure role permission rows exist before enabling fail-closed quota checks.
-- 真幂等(修正):原 ON DUPLICATE KEY 依赖 uk_role_level(role, membership_level),但 membership_level=NULL
-- 在 MySQL 唯一索引中互不相等,冲突永不触发 → 重跑会插入重复种子行,selectByRole(... membership_level IS NULL LIMIT 1)
-- 命中随机、玩家禁令(max_events=0)可能失效。改为「不存在才插 + 显式 UPDATE 校值」,保证 (role, NULL) 唯一且值正确。

-- ① 缺失才插(NOT EXISTS 对 NULL 判定正确,避免重复行)
INSERT INTO user_permission
    (role, max_themes, max_events, max_coupons, can_create_theme, can_create_event, can_publish_coupon, membership_level)
SELECT r.role, r.max_themes, r.max_events, r.max_coupons, r.can_create_theme, r.can_create_event, r.can_publish_coupon, NULL
FROM (
        SELECT 'player'   AS role, 1 AS max_themes, 0 AS max_events, 1 AS max_coupons, 1 AS can_create_theme, 0 AS can_create_event, 1 AS can_publish_coupon
  UNION ALL SELECT 'club',        5,             3,             3,             1,                    1,                    1
  UNION ALL SELECT 'merchant',    0,             0,             3,             0,                    0,                    1
) r
WHERE NOT EXISTS (
    SELECT 1 FROM user_permission up WHERE up.role = r.role AND up.membership_level IS NULL
);

-- ② 显式校值(幂等):无论此前有无行,都把默认档位对齐到目标值(玩家 max_events=0 与已上线禁令一致)
UPDATE user_permission SET max_themes=1, max_events=0, max_coupons=1, can_create_theme=1, can_create_event=0, can_publish_coupon=1 WHERE role='player'   AND membership_level IS NULL;
UPDATE user_permission SET max_themes=5, max_events=3, max_coupons=3, can_create_theme=1, can_create_event=1, can_publish_coupon=1 WHERE role='club'     AND membership_level IS NULL;
UPDATE user_permission SET max_themes=0, max_events=0, max_coupons=3, can_create_theme=0, can_create_event=0, can_publish_coupon=1 WHERE role='merchant' AND membership_level IS NULL;
