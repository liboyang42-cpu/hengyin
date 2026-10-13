-- P1-6 O块只读预检发现：settlementReconciliationTask(见 migration_settlement_reconciliation_job_20260710.sql)
-- 暂停中(status=1)但 remark 只写了触发条件，没有负责人/到期日，按 chengyin-db-migrate skill 铁律
-- "暂停态注册必须写明触发时机+负责人+到期日"存在被遗忘风险。
--
-- ★本文件仅为准备稿，不由本次任务自动执行——按流程需用户审核后手动落库(chengyin-db-migrate)。
-- 负责人/到期日为建议值，需用户确认或改写后再执行：
--   触发时机：alarm.webhook 配置完成 + 完成一次人工核验(见§O.缺什么)
--   负责人：待用户指定资金域 owner
--   到期日建议：2026-08-31（若 P1-7 真实商户验收窗口延后，届时应重新评估此日期，不应静默过期）
UPDATE sys_job
SET remark = '巡检 settlement_batch 与 settlement_detail 金额差异，仅告警，默认暂停；触发时机=alarm.webhook配置完成+一次人工核验；负责人=待定；到期日=2026-08-31（到期未启用需人工复核是否继续暂停）'
WHERE invoke_target = 'settlementReconciliationTask.run()' AND status = '1';
