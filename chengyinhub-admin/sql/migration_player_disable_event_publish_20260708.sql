-- P1 权限收敛:普通玩家不允许发布独立活动。
-- 背景:独立活动应由 club/merchant 等运营角色承接;player 保留主题/打卡等轻量创作能力。
-- 幂等:重复执行只会把 player 默认能力重置为不可创建活动。

UPDATE user_permission
SET max_events = 0,
    can_create_event = 0
WHERE role = 'player' AND membership_level IS NULL;
