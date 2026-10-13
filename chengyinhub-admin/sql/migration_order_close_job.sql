-- 过期未支付订单自动关单 定时任务注册(P0-10 弃单释放库存)
-- 执行方式:连生产库 source 本文件(走 chengyin-db-migrate skill,只读预检后确认再执行)
-- 依赖:含 OrderCloseTask 的新 jar 已部署
-- 说明:非阻断(仅 sys_job 一条 INSERT,可随时执行或在后台「定时任务」手动添加)。
--      默认 status='1'(暂停):新 jar 部署完成后在后台启用。
--      cron 每 5 分钟;条件关单(仍待支付才关)+ 原子回补库存 + 退还预扣积分,与支付回调竞态安全。

INSERT INTO sys_job
    (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status, create_by, create_time, remark)
SELECT '过期未支付订单关单', 'DEFAULT', 'orderCloseTask.closeExpired()', '0 0/5 * * * ?', '3', '1', '1', 'admin', NOW(),
       'P0-10 弃单关单:pay_expire_time 已过仍待支付→关单+释放库存+退积分;默认暂停,jar 部署后启用'
WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE invoke_target = 'orderCloseTask.closeExpired()');
