-- post-deploy: register only after the candidate containing this task is deployed.
-- Failed/unknown queries leave the order pending. Successful queries reuse the real payment callback grant.
-- sys_job is an existing RuoYi base table, not a managed migration dependency.
-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.tables
        WHERE table_schema=DATABASE() AND table_name='sys_job' AND table_type='BASE TABLE')=1
   AND (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='sys_job'
          AND column_name IN ('job_id','job_name','job_group','invoke_target','cron_expression',
                              'misfire_policy','concurrent','status','create_by','create_time','remark'))=11
   AND (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='sys_job' AND column_name='job_id'
          AND column_key='PRI' AND extra LIKE '%auto_increment%')=1;
INSERT INTO sys_job(job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent,
                    status, create_by, create_time, remark)
SELECT '商业化支付主动查单', 'DEFAULT', 'merchantCommerceReconcileTask.reconcile()', '0 0/5 * * * ?',
       '3', '1', '0', 'system', NOW(), '查询五分钟前待支付订单；真实成功补发权益，未知结果保留并告警'
WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE invoke_target = 'merchantCommerceReconcileTask.reconcile()');
-- runner: assert=scalar-true
SELECT COUNT(*) = 1 AND SUM(cron_expression = '0 0/5 * * * ?' AND concurrent = '1'
  AND misfire_policy = '3' AND status = '0') = 1
FROM sys_job WHERE invoke_target = 'merchantCommerceReconcileTask.reconcile()';
