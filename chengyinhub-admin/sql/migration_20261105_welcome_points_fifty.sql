-- 注册赠送积分统一为 50(cms_points_setting id 1)(2026-11-05 业主拍板,ROUND12 k1 第 3 项)
--
-- 背景:新会员余额原来在代码里写死 50,流水却读规则 1 的 value;生产规则 1 是 100,
--   于是余额 50、积分明细「注册赠送 +100」,两边对不上。新 jar 起余额与流水都只读规则 1,
--   本迁移把规则 1 改成 50,让旧 jar 与新 jar 在切换期发的数一致。
-- 改什么:只改 id 1 一行的 value → 50。status 不动(停用仍按后台开关一分不发)。
-- 不改什么:其他规则;已注册会员的余额与历史流水(不追溯)。
-- 规则不存在也能跑:基线库没有 id 1-7 行(生产后台建的),UPDATE 命中 0 行,断言照样成立。
--
-- 发布阶段:jar 之前或之后都可以(旧 jar 余额写死 50,与新值一致)。
-- 可重跑:写绝对值,只在不等于 50 时命中;第二次执行命中 0 行。
-- 回退:UPDATE cms_points_setting SET value = 100 WHERE id = 1;
--   (生产 2026-11 为 value = 100;执行前请先 SELECT id, value, status FROM cms_points_setting WHERE id = 1 记下实际值)

UPDATE cms_points_setting
SET value       = 50,
    update_by   = 'migration_20261105',
    update_time = NOW()
WHERE id = 1
  AND (value IS NULL OR value <> 50);

-- runner: assert=scalar-true
SELECT COUNT(*) = 0 AS welcome_points_fifty
FROM cms_points_setting
WHERE id = 1
  AND (value IS NULL OR value <> 50);
