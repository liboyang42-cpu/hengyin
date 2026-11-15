-- R9 BE-28 定时任务全量错峰(2026-11-15)
-- 背景:20261101 按生产 job_id 错开了 23 个任务,但之后新加的任务(营销记账、推荐分、搜索投影、AI 配额回收、
--       自动核销、漫游召回等)仍在 0 秒起跑;03:00 / 03:20 / 04:00 还各有两个日任务同分同秒。
-- 做法:按 invoke_target(不依赖各环境不同的 job_id)给 48 个已知任务各分配一个**独一无二的秒位**(0..59 内不重复),
--       频率不变,只改秒位与分钟偏移 ⇒ 任意两个任务永远不会在同一秒起跑。
--       指标采样 metricsProbeTask 保留 0 秒(与 20261101 的口径一致)。
-- 只覆盖「仍是历史上某个默认/错峰值」的行;人工改过的表达式不动,文件末尾列出这些行供人工核对。
-- 生效:应用启动时 SysJobServiceImpl.init 按 sys_job 重建调度 —— 部署/重启前执行。可重复执行。
-- 计划真源 = 本文件;SysJobStaggerPlanTest 解析本文件,逐秒模拟 24 小时,断言没有两个任务同秒。
--       以后新增 sys_job 必须在新的错峰迁移里分配未用的秒位,否则该用例失败。
-- 回退:把每行 SET 的新值换回 IN (...) 中原来的值即可(只动 cron_expression)。

-- 计划表(SysJobStaggerPlanTest 解析这些行):
-- plan: roamPresenceRevokeAuditTask.run() = 1 * * * * ?
-- plan: clubEventOpsTask.run() = 7 * * * * ?
-- plan: clubPostMediaReviewTask.run() = 13 * * * * ?
-- plan: communityPostMaintenanceTask.run() = 19 * * * * ?
-- plan: merchantCrmDeliveryRecoveryTask.run() = 25 * * * * ?
-- plan: storeVerifyGrowthTask.run() = 31 * * * * ?
-- plan: aiQuotaRecoveryTask.run() = 37 * * * * ?
-- plan: imNotificationDeliveryTask.deliverPending() = 45 * * * * ?
-- plan: committedOfficialNoticeTask.deliverPending() = 27 * * * * ?
-- plan: withdrawTransferQueryTask.run() = 43 1/2 * * * ?
-- plan: metricsProbeTask.run() = 0 0/5 * * * ?
-- plan: orderCloseTask.closeExpired() = 3 0/5 * * * ?
-- plan: officialEventTask.run() = 49 1/5 * * * ?
-- plan: merchantCommerceReconcileTask.reconcile() = 55 2/5 * * * ?
-- plan: contentSafetyRecheckTask.run() = 21 1/5 * * * ?
-- plan: platformPassDeliveryTask.run() = 23 0/5 * * * ?
-- plan: channelRefundConsumptionTask.run() = 39 3/5 * * * ?
-- plan: marketingRevenuePostingTask.run() = 51 4/5 * * * ?
-- plan: coopInviteExpireTask.run() = 11 3/10 * * * ?
-- plan: topicRecruitDeadlineTask.run() = 17 4/10 * * * ?
-- plan: autoRefundTask.autoRefundUnformed() = 29 6/10 * * * ?
-- plan: verificationQrcodeRepairTask.repairMissing() = 35 7/10 * * * ?
-- plan: playTeamExpireTask.run() = 41 8/10 * * * ?
-- plan: roamRewardDeliveryTask.run() = 47 9/10 * * * ?
-- plan: merchantAftercareTimeoutTask.run() = 53 0/10 * * * ?
-- plan: orderCloseTask.reconcileExpiredPaying() = 59 1/10 * * * ?
-- plan: ecommercePayoutTask.shareAndRetry() = 9 5/10 * * * ?
-- plan: sensitiveContentRescanTask.run() = 33 2/10 * * * ?
-- plan: refundReconcileTask.reconcile() = 5 11/15 * * * ?
-- plan: auditBacklogMonitorTask.run() = 15 12/30 * * * ?
-- plan: contentSearchProjectionTask.refresh() = 2 13/30 * * * ?
-- plan: roamHangoutExpireTask.run() = 4 16/30 * * * ?
-- plan: roamRecallTask.run() = 6 17/30 * * * ?
-- plan: ecommercePayoutTask.syncOnboarding() = 57 18/30 * * * ?
-- plan: settlementTask.run() = 8 14 * * * ?
-- plan: beforeStartNotifyTask.run() = 10 0 * * * ?
-- plan: registrationAutoVerifyTask.run() = 12 31 * * * ?
-- plan: analyticsAggregationTask.aggregateRecentWindow() = 14 10 0 * * ?
-- plan: autoSettlementTask.run() = 16 30 2 * * ?
-- plan: coopSettlementPayoutTask.run() = 18 0 3 * * ?
-- plan: predictVoidExpiredTask.run() = 20 2 3 * * ?
-- plan: settlementReconciliationTask.run() = 22 10 3 * * ?
-- plan: clubSessionReconciliationTask.run() = 24 20 3 * * ?
-- plan: recommendationScoreDailyTask.run() = 26 25 3 * * ?
-- plan: dataRetentionCleanupTask.run() = 28 35 3 * * ?
-- plan: withdrawReconciliationTask.run() = 30 5 4 * * ?
-- plan: piiBackfillTask.run() = 32 15 4 * * ?
-- plan: wxTradeBillDownloadTask.download() = 34 30 10 * * ?
-- retired: activityRecallTask.run()  (deleted by migration_20260717_recall_job_decommission.sql)
-- retired: analyticsAggregationTask.aggregateYesterday()  (paused, replaced by aggregateRecentWindow, 20260909)

UPDATE sys_job SET cron_expression = '1 * * * * ?', update_time = NOW() WHERE invoke_target = 'roamPresenceRevokeAuditTask.run()' AND cron_expression IN ('0 * * * * ?', '13 * * * * ?', '19 * * * * ?', '25 * * * * ?', '31 * * * * ?', '37 * * * * ?', '7 * * * * ?');
UPDATE sys_job SET cron_expression = '7 * * * * ?', update_time = NOW() WHERE invoke_target = 'clubEventOpsTask.run()' AND cron_expression IN ('0 * * * * ?', '13 * * * * ?', '19 * * * * ?', '25 * * * * ?', '31 * * * * ?', '37 * * * * ?');
UPDATE sys_job SET cron_expression = '13 * * * * ?', update_time = NOW() WHERE invoke_target = 'clubPostMediaReviewTask.run()' AND cron_expression IN ('0 * * * * ?', '19 * * * * ?', '25 * * * * ?', '31 * * * * ?', '37 * * * * ?', '7 * * * * ?');
UPDATE sys_job SET cron_expression = '19 * * * * ?', update_time = NOW() WHERE invoke_target = 'communityPostMaintenanceTask.run()' AND cron_expression IN ('0 * * * * ?', '13 * * * * ?', '25 * * * * ?', '31 * * * * ?', '37 * * * * ?', '7 * * * * ?');
UPDATE sys_job SET cron_expression = '25 * * * * ?', update_time = NOW() WHERE invoke_target = 'merchantCrmDeliveryRecoveryTask.run()' AND cron_expression IN ('0 * * * * ?', '13 * * * * ?', '19 * * * * ?', '31 * * * * ?', '37 * * * * ?', '7 * * * * ?');
UPDATE sys_job SET cron_expression = '31 * * * * ?', update_time = NOW() WHERE invoke_target = 'storeVerifyGrowthTask.run()' AND cron_expression IN ('0 * * * * ?', '13 * * * * ?', '19 * * * * ?', '25 * * * * ?', '37 * * * * ?', '7 * * * * ?');
UPDATE sys_job SET cron_expression = '37 * * * * ?', update_time = NOW() WHERE invoke_target = 'aiQuotaRecoveryTask.run()' AND cron_expression IN ('0 */1 * * * ?');
UPDATE sys_job SET cron_expression = '45 * * * * ?', update_time = NOW() WHERE invoke_target = 'imNotificationDeliveryTask.deliverPending()' AND cron_expression IN ('0 0/1 * * * ?');
UPDATE sys_job SET cron_expression = '27 * * * * ?', update_time = NOW() WHERE invoke_target = 'committedOfficialNoticeTask.deliverPending()' AND cron_expression IN ('0 * * * * ?', '0 0/1 * * * ?', '13 * * * * ?', '19 * * * * ?', '25 * * * * ?', '27 0/1 * * * ?', '31 * * * * ?', '37 * * * * ?', '7 * * * * ?');
UPDATE sys_job SET cron_expression = '43 1/2 * * * ?', update_time = NOW() WHERE invoke_target = 'withdrawTransferQueryTask.run()' AND cron_expression IN ('0 0/2 * * * ?');
UPDATE sys_job SET cron_expression = '0 0/5 * * * ?', update_time = NOW() WHERE invoke_target = 'metricsProbeTask.run()' AND cron_expression IN ('3 0/5 * * * ?', '49 1/5 * * * ?', '55 2/5 * * * ?');
UPDATE sys_job SET cron_expression = '3 0/5 * * * ?', update_time = NOW() WHERE invoke_target = 'orderCloseTask.closeExpired()' AND cron_expression IN ('0 0/5 * * * ?', '49 1/5 * * * ?', '55 2/5 * * * ?');
UPDATE sys_job SET cron_expression = '49 1/5 * * * ?', update_time = NOW() WHERE invoke_target = 'officialEventTask.run()' AND cron_expression IN ('0 0/5 * * * ?', '3 0/5 * * * ?', '55 2/5 * * * ?');
UPDATE sys_job SET cron_expression = '55 2/5 * * * ?', update_time = NOW() WHERE invoke_target = 'merchantCommerceReconcileTask.reconcile()' AND cron_expression IN ('0 0/5 * * * ?', '3 0/5 * * * ?', '49 1/5 * * * ?');
-- contentSafetyRecheckTask.run() already at '21 1/5 * * * ?' (no prior default to replace)
-- platformPassDeliveryTask.run() already at '23 0/5 * * * ?' (no prior default to replace)
UPDATE sys_job SET cron_expression = '39 3/5 * * * ?', update_time = NOW() WHERE invoke_target = 'channelRefundConsumptionTask.run()' AND cron_expression IN ('0 0/5 * * * ?', '3 0/5 * * * ?', '49 1/5 * * * ?', '55 2/5 * * * ?');
UPDATE sys_job SET cron_expression = '51 4/5 * * * ?', update_time = NOW() WHERE invoke_target = 'marketingRevenuePostingTask.run()' AND cron_expression IN ('0 0/5 * * * ?', '3 0/5 * * * ?', '49 1/5 * * * ?', '55 2/5 * * * ?');
UPDATE sys_job SET cron_expression = '11 3/10 * * * ?', update_time = NOW() WHERE invoke_target = 'coopInviteExpireTask.run()' AND cron_expression IN ('0 0/10 * * * ?', '17 4/10 * * * ?', '23 6/10 * * * ?', '29 7/10 * * * ?', '35 8/10 * * * ?', '41 9/10 * * * ?');
UPDATE sys_job SET cron_expression = '17 4/10 * * * ?', update_time = NOW() WHERE invoke_target = 'topicRecruitDeadlineTask.run()' AND cron_expression IN ('0 0/10 * * * ?', '11 3/10 * * * ?', '23 6/10 * * * ?', '29 7/10 * * * ?', '35 8/10 * * * ?', '41 9/10 * * * ?');
UPDATE sys_job SET cron_expression = '29 6/10 * * * ?', update_time = NOW() WHERE invoke_target = 'autoRefundTask.autoRefundUnformed()' AND cron_expression IN ('0 0/10 * * * ?', '11 3/10 * * * ?', '17 4/10 * * * ?', '23 6/10 * * * ?', '29 7/10 * * * ?', '35 8/10 * * * ?', '41 9/10 * * * ?');
UPDATE sys_job SET cron_expression = '35 7/10 * * * ?', update_time = NOW() WHERE invoke_target = 'verificationQrcodeRepairTask.repairMissing()' AND cron_expression IN ('0 0/10 * * * ?', '11 3/10 * * * ?', '17 4/10 * * * ?', '23 6/10 * * * ?', '29 7/10 * * * ?', '35 8/10 * * * ?', '41 9/10 * * * ?');
UPDATE sys_job SET cron_expression = '41 8/10 * * * ?', update_time = NOW() WHERE invoke_target = 'playTeamExpireTask.run()' AND cron_expression IN ('0 0/10 * * * ?', '11 3/10 * * * ?', '17 4/10 * * * ?', '23 6/10 * * * ?', '29 7/10 * * * ?', '35 8/10 * * * ?', '41 9/10 * * * ?');
UPDATE sys_job SET cron_expression = '47 9/10 * * * ?', update_time = NOW() WHERE invoke_target = 'roamRewardDeliveryTask.run()' AND cron_expression IN ('0 0/10 * * * ?', '11 3/10 * * * ?', '17 4/10 * * * ?', '23 6/10 * * * ?', '29 7/10 * * * ?', '35 8/10 * * * ?', '41 9/10 * * * ?');
UPDATE sys_job SET cron_expression = '53 0/10 * * * ?', update_time = NOW() WHERE invoke_target = 'merchantAftercareTimeoutTask.run()' AND cron_expression IN ('0 0/10 * * * ?', '11 3/10 * * * ?', '17 4/10 * * * ?', '23 6/10 * * * ?', '29 7/10 * * * ?', '35 8/10 * * * ?', '41 9/10 * * * ?');
UPDATE sys_job SET cron_expression = '59 1/10 * * * ?', update_time = NOW() WHERE invoke_target = 'orderCloseTask.reconcileExpiredPaying()' AND cron_expression IN ('0 0/10 * * * ?', '11 3/10 * * * ?', '17 4/10 * * * ?', '23 6/10 * * * ?', '29 7/10 * * * ?', '35 8/10 * * * ?', '41 9/10 * * * ?');
UPDATE sys_job SET cron_expression = '9 5/10 * * * ?', update_time = NOW() WHERE invoke_target = 'ecommercePayoutTask.shareAndRetry()' AND cron_expression IN ('0 0/10 * * * ?', '11 3/10 * * * ?', '17 4/10 * * * ?', '23 6/10 * * * ?', '29 7/10 * * * ?', '35 8/10 * * * ?', '41 9/10 * * * ?');
-- sensitiveContentRescanTask.run() already at '33 2/10 * * * ?' (no prior default to replace)
UPDATE sys_job SET cron_expression = '5 11/15 * * * ?', update_time = NOW() WHERE invoke_target = 'refundReconcileTask.reconcile()' AND cron_expression IN ('0 0/15 * * * ?', '47 11/15 * * * ?');
UPDATE sys_job SET cron_expression = '15 12/30 * * * ?', update_time = NOW() WHERE invoke_target = 'auditBacklogMonitorTask.run()' AND cron_expression IN ('0 0/30 * * * ?', '53 12/30 * * * ?', '59 13/30 * * * ?');
UPDATE sys_job SET cron_expression = '2 13/30 * * * ?', update_time = NOW() WHERE invoke_target = 'contentSearchProjectionTask.refresh()' AND cron_expression IN ('0 0/30 * * * ?', '53 12/30 * * * ?', '59 13/30 * * * ?');
UPDATE sys_job SET cron_expression = '4 16/30 * * * ?', update_time = NOW() WHERE invoke_target = 'roamHangoutExpireTask.run()' AND cron_expression IN ('0 0/30 * * * ?', '53 12/30 * * * ?', '59 13/30 * * * ?');
UPDATE sys_job SET cron_expression = '6 17/30 * * * ?', update_time = NOW() WHERE invoke_target = 'roamRecallTask.run()' AND cron_expression IN ('0 0/30 * * * ?', '53 12/30 * * * ?', '59 13/30 * * * ?');
UPDATE sys_job SET cron_expression = '57 18/30 * * * ?', update_time = NOW() WHERE invoke_target = 'ecommercePayoutTask.syncOnboarding()' AND cron_expression IN ('0 0/30 * * * ?', '0 15/30 * * * ?', '53 12/30 * * * ?', '59 13/30 * * * ?');
UPDATE sys_job SET cron_expression = '8 14 * * * ?', update_time = NOW() WHERE invoke_target = 'settlementTask.run()' AND cron_expression IN ('0 0 * * * ?', '15 0 * * * ?', '5 14 * * * ?');
UPDATE sys_job SET cron_expression = '10 0 * * * ?', update_time = NOW() WHERE invoke_target = 'beforeStartNotifyTask.run()' AND cron_expression IN ('0 0 * * * ?', '15 0 * * * ?', '5 14 * * * ?');
UPDATE sys_job SET cron_expression = '12 31 * * * ?', update_time = NOW() WHERE invoke_target = 'registrationAutoVerifyTask.run()' AND cron_expression IN ('0 30 * * * ?');
UPDATE sys_job SET cron_expression = '14 10 0 * * ?', update_time = NOW() WHERE invoke_target = 'analyticsAggregationTask.aggregateRecentWindow()' AND cron_expression IN ('0 10 0 * * ?');
UPDATE sys_job SET cron_expression = '16 30 2 * * ?', update_time = NOW() WHERE invoke_target = 'autoSettlementTask.run()' AND cron_expression IN ('0 30 2 * * ?');
UPDATE sys_job SET cron_expression = '18 0 3 * * ?', update_time = NOW() WHERE invoke_target = 'coopSettlementPayoutTask.run()' AND cron_expression IN ('0 0 3 * * ?', '30 5 3 * * ?');
UPDATE sys_job SET cron_expression = '20 2 3 * * ?', update_time = NOW() WHERE invoke_target = 'predictVoidExpiredTask.run()' AND cron_expression IN ('0 0 3 * * ?', '30 5 3 * * ?');
UPDATE sys_job SET cron_expression = '22 10 3 * * ?', update_time = NOW() WHERE invoke_target = 'settlementReconciliationTask.run()' AND cron_expression IN ('0 10 3 * * ?');
UPDATE sys_job SET cron_expression = '24 20 3 * * ?', update_time = NOW() WHERE invoke_target = 'clubSessionReconciliationTask.run()' AND cron_expression IN ('0 20 3 * * ?');
UPDATE sys_job SET cron_expression = '26 25 3 * * ?', update_time = NOW() WHERE invoke_target = 'recommendationScoreDailyTask.run()' AND cron_expression IN ('0 20 3 * * ?');
UPDATE sys_job SET cron_expression = '28 35 3 * * ?', update_time = NOW() WHERE invoke_target = 'dataRetentionCleanupTask.run()' AND cron_expression IN ('0 30 3 * * ?');
UPDATE sys_job SET cron_expression = '30 5 4 * * ?', update_time = NOW() WHERE invoke_target = 'withdrawReconciliationTask.run()' AND cron_expression IN ('0 0 4 * * ?');
UPDATE sys_job SET cron_expression = '32 15 4 * * ?', update_time = NOW() WHERE invoke_target = 'piiBackfillTask.run()' AND cron_expression IN ('0 0 4 * * ?', '30 5 4 * * ?');
UPDATE sys_job SET cron_expression = '34 30 10 * * ?', update_time = NOW() WHERE invoke_target = 'wxTradeBillDownloadTask.download()' AND cron_expression IN ('0 30 10 * * ?');

-- 人工核对(只读):计划内任务若不是计划值,说明被人工改过,本迁移没有覆盖。
SELECT job_id, job_name, invoke_target, cron_expression AS current_cron FROM sys_job
 WHERE status = '0' AND invoke_target IN ('roamPresenceRevokeAuditTask.run()', 'clubEventOpsTask.run()', 'clubPostMediaReviewTask.run()', 'communityPostMaintenanceTask.run()', 'merchantCrmDeliveryRecoveryTask.run()', 'storeVerifyGrowthTask.run()', 'aiQuotaRecoveryTask.run()', 'imNotificationDeliveryTask.deliverPending()', 'committedOfficialNoticeTask.deliverPending()', 'withdrawTransferQueryTask.run()', 'metricsProbeTask.run()', 'orderCloseTask.closeExpired()', 'officialEventTask.run()', 'merchantCommerceReconcileTask.reconcile()', 'contentSafetyRecheckTask.run()', 'platformPassDeliveryTask.run()', 'channelRefundConsumptionTask.run()', 'marketingRevenuePostingTask.run()', 'coopInviteExpireTask.run()', 'topicRecruitDeadlineTask.run()', 'autoRefundTask.autoRefundUnformed()', 'verificationQrcodeRepairTask.repairMissing()', 'playTeamExpireTask.run()', 'roamRewardDeliveryTask.run()', 'merchantAftercareTimeoutTask.run()', 'orderCloseTask.reconcileExpiredPaying()', 'ecommercePayoutTask.shareAndRetry()', 'sensitiveContentRescanTask.run()', 'refundReconcileTask.reconcile()', 'auditBacklogMonitorTask.run()', 'contentSearchProjectionTask.refresh()', 'roamHangoutExpireTask.run()', 'roamRecallTask.run()', 'ecommercePayoutTask.syncOnboarding()', 'settlementTask.run()', 'beforeStartNotifyTask.run()', 'registrationAutoVerifyTask.run()', 'analyticsAggregationTask.aggregateRecentWindow()', 'autoSettlementTask.run()', 'coopSettlementPayoutTask.run()', 'predictVoidExpiredTask.run()', 'settlementReconciliationTask.run()', 'clubSessionReconciliationTask.run()', 'recommendationScoreDailyTask.run()', 'dataRetentionCleanupTask.run()', 'withdrawReconciliationTask.run()', 'piiBackfillTask.run()', 'wxTradeBillDownloadTask.download()')
   AND cron_expression NOT IN ('0 0/5 * * * ?', '1 * * * * ?', '10 0 * * * ?', '11 3/10 * * * ?', '12 31 * * * ?', '13 * * * * ?', '14 10 0 * * ?', '15 12/30 * * * ?', '16 30 2 * * ?', '17 4/10 * * * ?', '18 0 3 * * ?', '19 * * * * ?', '2 13/30 * * * ?', '20 2 3 * * ?', '21 1/5 * * * ?', '22 10 3 * * ?', '23 0/5 * * * ?', '24 20 3 * * ?', '25 * * * * ?', '26 25 3 * * ?', '27 * * * * ?', '28 35 3 * * ?', '29 6/10 * * * ?', '3 0/5 * * * ?', '30 5 4 * * ?', '31 * * * * ?', '32 15 4 * * ?', '33 2/10 * * * ?', '34 30 10 * * ?', '35 7/10 * * * ?', '37 * * * * ?', '39 3/5 * * * ?', '4 16/30 * * * ?', '41 8/10 * * * ?', '43 1/2 * * * ?', '45 * * * * ?', '47 9/10 * * * ?', '49 1/5 * * * ?', '5 11/15 * * * ?', '51 4/5 * * * ?', '53 0/10 * * * ?', '55 2/5 * * * ?', '57 18/30 * * * ?', '59 1/10 * * * ?', '6 17/30 * * * ?', '7 * * * * ?', '8 14 * * * ?', '9 5/10 * * * ?');

-- runner: assert=scalar-true
-- 计划内且启用的任务,秒位两两不同。
SELECT COUNT(*) = COUNT(DISTINCT SUBSTRING_INDEX(cron_expression, ' ', 1)) AS sys_job_seconds_unique
  FROM sys_job WHERE status = '0' AND invoke_target IN ('roamPresenceRevokeAuditTask.run()', 'clubEventOpsTask.run()', 'clubPostMediaReviewTask.run()', 'communityPostMaintenanceTask.run()', 'merchantCrmDeliveryRecoveryTask.run()', 'storeVerifyGrowthTask.run()', 'aiQuotaRecoveryTask.run()', 'imNotificationDeliveryTask.deliverPending()', 'committedOfficialNoticeTask.deliverPending()', 'withdrawTransferQueryTask.run()', 'metricsProbeTask.run()', 'orderCloseTask.closeExpired()', 'officialEventTask.run()', 'merchantCommerceReconcileTask.reconcile()', 'contentSafetyRecheckTask.run()', 'platformPassDeliveryTask.run()', 'channelRefundConsumptionTask.run()', 'marketingRevenuePostingTask.run()', 'coopInviteExpireTask.run()', 'topicRecruitDeadlineTask.run()', 'autoRefundTask.autoRefundUnformed()', 'verificationQrcodeRepairTask.repairMissing()', 'playTeamExpireTask.run()', 'roamRewardDeliveryTask.run()', 'merchantAftercareTimeoutTask.run()', 'orderCloseTask.reconcileExpiredPaying()', 'ecommercePayoutTask.shareAndRetry()', 'sensitiveContentRescanTask.run()', 'refundReconcileTask.reconcile()', 'auditBacklogMonitorTask.run()', 'contentSearchProjectionTask.refresh()', 'roamHangoutExpireTask.run()', 'roamRecallTask.run()', 'ecommercePayoutTask.syncOnboarding()', 'settlementTask.run()', 'beforeStartNotifyTask.run()', 'registrationAutoVerifyTask.run()', 'analyticsAggregationTask.aggregateRecentWindow()', 'autoSettlementTask.run()', 'coopSettlementPayoutTask.run()', 'predictVoidExpiredTask.run()', 'settlementReconciliationTask.run()', 'clubSessionReconciliationTask.run()', 'recommendationScoreDailyTask.run()', 'dataRetentionCleanupTask.run()', 'withdrawReconciliationTask.run()', 'piiBackfillTask.run()', 'wxTradeBillDownloadTask.download()');
