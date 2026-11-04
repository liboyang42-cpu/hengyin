-- 商家活动上限:同时 3 场(2026-11-04 业主拍板,ROUND10 j1 F1)
--
-- 改什么:user_permission 商家默认档位(role='merchant' AND membership_level IS NULL)一行
--   can_create_event 0/1 → 1,max_events 0 → 3。与俱乐部默认档位(1 / 3)一致。
-- 为什么:QuotaServiceImpl.checkCreate(TYPE_EVENT) 读这一行。生产现为 can_create_event=1, max_events=0,
--   仓库迁移(p1_rbac_permission_defaults / 20261012 缺行补种)给的是 0/0,两种都会让每个商家
--   发布活动时被拒(「当前身份不可创建活动」或「已达到当前身份活动数量上限（0）」)。
-- 占名额口径在代码里(CmsActivityMapper.selectActivityQuotaUsedByMemberId):已结束、已取消的活动不算,
--   商家与俱乐部同一条规则。本文件只改数值。
-- 不改什么:其他列;player / club 行;带 membership_level 的会员档位行;任何商家个人数据。
--
-- 可重跑:UPDATE 写绝对值,第二次执行结果相同。前置断言:商家默认档位恰好 1 行(多行时 getByRole 取哪行不确定)。
-- 回退:UPDATE user_permission SET can_create_event=<原值>, max_events=<原值>
--        WHERE role='merchant' AND membership_level IS NULL;(执行前请先 SELECT 记下;生产 2026-11 为 1 / 0)

-- runner: assert=scalar-true
SELECT COUNT(*) = 1 AS merchant_default_permission_row_unique
FROM user_permission WHERE role = 'merchant' AND membership_level IS NULL;

UPDATE user_permission
SET can_create_event = 1,
    max_events       = 3
WHERE role = 'merchant' AND membership_level IS NULL;

-- runner: assert=scalar-true
SELECT COUNT(*) = 1 AS merchant_activity_limit_three
FROM user_permission
WHERE role = 'merchant' AND membership_level IS NULL
  AND can_create_event = 1 AND max_events = 3;
