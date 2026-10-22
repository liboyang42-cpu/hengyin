-- O-14 定时任务登记：每日推荐打分 + 统一搜索投影刷新。
-- ⚠️ 两条任务都以 status='1'(暂停)登记：本迁移只让任务「存在且可见」，不让它跑。
--    owner 在后台「定时任务」里手动启用前，生产行为不变。
-- 前置：migration_p3_platform_foundation.sql(recommendation_score_daily)、
--       migration_o14_content_search_document_20261022.sql(content_search_document)。
-- 可先于新 jar 执行：暂停的任务不会被调度，旧 jar 里没有对应 bean 也不会触发调用。
-- 幂等：INSERT ... WHERE NOT EXISTS(按 invoke_target)；已存在的登记不改状态/计划，尊重 owner 的手工选择。

-- 每日 03:20(Asia/Shanghai)按当日重算全部活跃会员的推荐分；同日重跑先删后写同事务，结果一致。
INSERT INTO sys_job
  (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status,
   create_by, create_time, remark)
SELECT '每日推荐打分', 'DEFAULT', 'recommendationScoreDailyTask.run()', '0 20 3 * * ?',
       '2', '1', '1', 'admin', NOW(),
       'O-14 默认暂停：owner 启用后每日写 recommendation_score_daily；同日幂等重算，保留最近7天'
WHERE NOT EXISTS (
  SELECT 1 FROM sys_job WHERE invoke_target = 'recommendationScoreDailyTask.run()'
);

-- 每 30 分钟全量对齐统一搜索投影(只写公开内容为 VISIBLE，其余 HIDDEN)。
INSERT INTO sys_job
  (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status,
   create_by, create_time, remark)
SELECT '统一搜索投影刷新', 'DEFAULT', 'contentSearchProjectionTask.refresh()', '0 0/30 * * * ?',
       '3', '1', '1', 'admin', NOW(),
       'O-14 默认暂停：owner 启用后对齐 content_search_document；全量幂等，可随时重跑'
WHERE NOT EXISTS (
  SELECT 1 FROM sys_job WHERE invoke_target = 'contentSearchProjectionTask.refresh()'
);

-- runner: assert=scalar-true
SELECT COUNT(*) = 1 AS assertion_ok
FROM sys_job WHERE invoke_target = 'recommendationScoreDailyTask.run()';

-- runner: assert=scalar-true
SELECT COUNT(*) = 1 AS assertion_ok
FROM sys_job WHERE invoke_target = 'contentSearchProjectionTask.refresh()';
