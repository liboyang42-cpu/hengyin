-- 关掉「被邀请用户报名任意活动」积分规则(cms_points_setting id 6)(2026-11-04 业主拍板,ROUND10 f4 第 2 项)
--
-- 改什么:只改 id 6 一行:status 1 → 0,value 100 → 0。
-- 为什么两列都改:
--   - 小程序「如何获取积分」只列 status = 1 的规则(points-rules.js),status = 0 让这条不再展示。
--   - 支付成功回调 CmsRegistrationServiceImpl.completePaymentSuccess 发这笔分时按 id 读 value,
--     不看 status(pointsSettingValue)。只改 status 每笔报名照样给邀请人 100 分;value = 0 时
--     awardPointsOnce 直接跳过、不写流水。两列一起改,展示与发放同时停。
-- 不改什么:id 5「邀请1位完成首次购买的用户」(500 分)保持原样;其他规则行;历史积分流水与余额。
--   没有团队奖励、分级奖励。
-- 规则不存在也能跑:基线库没有 id 1-7 行(生产后台建的),UPDATE 命中 0 行,断言照样成立;
--   支付链路不要求这条规则存在(读不到按 0 分处理)。
--
-- 可重跑:UPDATE 写绝对值,只在还开着时命中;第二次执行命中 0 行。
-- 回退(业主重新打开时):UPDATE cms_points_setting SET status = 1, value = 100 WHERE id = 6;
--   (生产 2026-11 为 status = 1, value = 100;执行前请先 SELECT 记下实际值)

UPDATE cms_points_setting
SET status      = 0,
    value       = 0,
    update_by   = 'migration_20261104',
    update_time = NOW()
WHERE id = 6
  AND (status <> 0 OR value <> 0 OR status IS NULL OR value IS NULL);

-- runner: assert=scalar-true
SELECT COUNT(*) = 0 AS invitee_signup_points_off
FROM cms_points_setting
WHERE id = 6
  AND (status IS NULL OR status <> 0 OR value IS NULL OR value <> 0);
