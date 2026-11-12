-- 后端闭环任务入口。两项任务默认暂停；部署与落库不会自动启用资金/退款消费。
-- 启用条件由资金负责人在核对目标环境、渠道退款流程及收入分配规则后单独决定。
-- runner: assert=scalar-true
SELECT COUNT(*)=0 FROM (
  SELECT CAST(invoke_target AS BINARY) AS exact_target
  FROM sys_job
  WHERE CAST(invoke_target AS BINARY) IN (
    CAST('channelRefundConsumptionTask.run()' AS BINARY),
    CAST('marketingRevenuePostingTask.run()' AS BINARY)
  )
  GROUP BY CAST(invoke_target AS BINARY)
  HAVING COUNT(*)>1
) duplicate_targets;

INSERT INTO sys_job(job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_by,create_time,remark)
SELECT '渠道退款事实补消费','DEFAULT','channelRefundConsumptionTask.run()','0 0/5 * * * ?','3','1','1','system',NOW(),
 '默认暂停；触发时机:每5分钟重试已验证未消费的渠道退款事实；负责人:渠道与资金负责人；到期日:2026-12-08；核验provider、退款回执与测试账后再启用'
WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE CAST(invoke_target AS BINARY)=CAST('channelRefundConsumptionTask.run()' AS BINARY));

INSERT INTO sys_job(job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_by,create_time,remark)
SELECT '营销收入分配队列消费','DEFAULT','marketingRevenuePostingTask.run()','0 0/5 * * * ?','3','1','1','system',NOW(),
 '默认暂停；触发时机:每5分钟消费到期收入分配队列并回收过期租约；负责人:商业化与资金负责人；到期日:2026-12-08；确认收入规则与退款冲销测试后再启用'
WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE CAST(invoke_target AS BINARY)=CAST('marketingRevenuePostingTask.run()' AS BINARY));

-- runner: assert=scalar-true
SELECT COUNT(*)=2 AND COUNT(DISTINCT CAST(invoke_target AS BINARY))=2
  AND MAX(status='1' AND concurrent='1')=1
FROM sys_job
WHERE CAST(invoke_target AS BINARY) IN (
  CAST('channelRefundConsumptionTask.run()' AS BINARY),
  CAST('marketingRevenuePostingTask.run()' AS BINARY)
);
