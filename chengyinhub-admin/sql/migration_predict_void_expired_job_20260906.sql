-- 竞猜超时兜底任务(predictVoidExpiredTask)注册,**启用态**。
--
-- 为什么是启用而不是暂停:这个任务是自收敛的 —— 没有过期轮次时它什么也不做、
--   直接返回 0。不存在「注册了就会乱跑」的风险,所以没有理由注册成暂停,
--   更没有理由派一个「记得去 RuoYi 点启动」的活给未来的自己
--   (piiBackfillTask 那 41 行 PII 裸奔一周,就是这么来的)。
--
-- 不注册的后果:玩家押完永远悬着,商家侧待办无限堆积,而且没有任何信号 ——
--   典型的「不炸所以发现不了」。task bean 在 jar 里躺着,一次都不会被调用。
--
-- cron:每天 03:00 跑一次。作废判据是「过了截止 48 小时」,不需要高频。
--   挑凌晨是因为它会写 play_predict_bet 的 reward_status,避开白天的下注高峰。
--
-- ⚠️ 不声明 requires=transaction:DELETE + INSERT 虽是 DML,但本文件独立幂等
--   (先删同名 invoke_target 再插),重跑安全,不需要事务包裹。
-- ============================================================================

-- ======================= 只读预检 =======================
-- 期望 0 行。非 0 说明已注册过,重跑会先删再插,结果一致。
SELECT job_id, job_name, status, cron_expression
FROM sys_job
WHERE invoke_target = 'predictVoidExpiredTask.run()';


-- ======================= 注册 =======================
DELETE FROM sys_job WHERE invoke_target = 'predictVoidExpiredTask.run()';

INSERT INTO sys_job (job_name, job_group, invoke_target, cron_expression,
                     misfire_policy, concurrent, status, create_time, remark)
VALUES ('竞猜轮次超时作废', 'DEFAULT', 'predictVoidExpiredTask.run()', '0 0 3 * * ?',
        '3', '1', '0', NOW(),
        '商家超过48小时不给竞猜答案的轮次作废且不发奖;自收敛任务,无过期轮次时空跑');


-- ======================= 写后断言 =======================
-- 断言:注册了恰好一条,且是启用态('0' = 正常)。
-- ⚠️ 写成「不满足就喂 JSON_EXTRACT 一段非 JSON」,让 MySQL 当场报错 ——
--    runner 不回显结果集,SELECT COUNT(*) 当断言等于没断言。
SELECT JSON_EXTRACT(IF(COUNT(*) = 1, '[]', 'ASSERT_FAILED__predict_job_registered_and_enabled'), '$[0]')
       AS predict_job_registered_and_enabled
FROM sys_job
WHERE invoke_target = 'predictVoidExpiredTask.run()'
  AND status = '0';
