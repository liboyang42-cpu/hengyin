-- 成团/未成团自动退款 定时任务注册(F4)
-- 执行方式:连生产库 source 本文件(走 chengyin-db-migrate skill,只读预检后确认再执行)
-- 依赖:migration_c3_team_fields.sql 已执行 + 含 AutoRefundTask 的新 jar 已部署
-- ⚠️ 默认 status='1'(暂停):自动退款动资金,务必先真机验证「成团判定 + 未成团退余额/积分」链路无误,
--    再到 RuoYi 后台「系统监控 → 定时任务」手动启用。cron 每10分钟一次。

INSERT INTO sys_job
    (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status, create_by, create_time, remark)
SELECT '成团未成团自动退款', 'DEFAULT', 'autoRefundTask.autoRefundUnformed()', '0 0/10 * * * ?', '3', '1', '1', 'admin', NOW(),
       'F4 未成团自动退余额/积分(不接微信原路);默认暂停,真机验证后启用'
WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE invoke_target = 'autoRefundTask.autoRefundUnformed()');
