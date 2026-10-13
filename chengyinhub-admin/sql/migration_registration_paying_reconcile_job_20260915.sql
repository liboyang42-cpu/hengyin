-- 阶段:post-deploy —— 必须先部署含 OrderCloseTask.reconcileExpiredPaying() 的新 jar 再注册本任务,
-- 否则 Quartz 每轮反序列化都抛 NoSuchMethodException(predictVoidExpiredTask 的老教训)。
-- ⚠️ 注册后尚需让运行中的 Scheduler 加载这一行:跑一次后台「定时任务」对该任务的 changeStatus
--    (status=0,等价于 resumeJob),或重启应用;本文件断言只证明数据库配置,不证明调度已生效。
--
-- §5-3:PAYING(预下单已发起)且过支付窗口的报名单既不能再付(/pay 拒过期),
-- 也不会被 closeExpired 扫到(它只扫 payment_status=0)。本任务每 10 分钟主动查微信真实态:
--   SUCCESS                       → 按支付回调同一条链路补记(钱到了就不能关单回补);
--   NOTPAY / USERPAYING / PAYERROR → 先关微信订单钉成终态,关单成功才本地关单 + 回补库存/积分/权益;
--   CLOSED / REVOKED              → 直接本地关单 + 回补;
--   ORDER_NOT_EXIST               → 过期超 2 小时宽限(不短于 prepay_id 有效期)后按未支付本地关单 + 回补;
--   查单失败 / 关单失败 / 未知态   → 保持不动并告警(AlarmService),不猜结果,下轮再查。
-- 幂等:INSERT ... WHERE NOT EXISTS,重跑不插第二行、不换 job_id(DELETE+INSERT 会换 job_id,
--       已激活的旧 JobKey 仍留在内存 Scheduler,再 changeStatus 就两个触发器并跑)。
-- 依赖:含本方法的 jar 已部署;无其它迁移依赖。
INSERT INTO sys_job (job_name, job_group, invoke_target, cron_expression,
                     misfire_policy, concurrent, status, create_by, create_time, remark)
SELECT '支付中过期订单对账', 'DEFAULT', 'orderCloseTask.reconcileExpiredPaying()', '0 0/10 * * * ?',
       '3', '1', '0', 'system', NOW(),
       '§5-3 PAYING 过支付窗口:查微信真实态——已支付补记/未支付关单回补/查单失败告警不猜'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_job WHERE invoke_target = 'orderCloseTask.reconcileExpiredPaying()'
);

-- runner: assert=scalar-true
SELECT COUNT(*)=1 AND SUM(cron_expression='0 0/10 * * * ?' AND misfire_policy='3'
  AND concurrent='1' AND status='0')=1 AS assertion_ok
FROM sys_job WHERE invoke_target='orderCloseTask.reconcileExpiredPaying()';
