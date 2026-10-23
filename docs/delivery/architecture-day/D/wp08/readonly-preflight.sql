-- WP08 READ ONLY. Not executed by D. Run only in an approved read-only audit session.
-- No INSERT/UPDATE/DELETE/ALTER/CREATE/DROP, no task enablement, no production connection details.
SELECT VERSION() AS mysql_version, DATABASE() AS selected_schema;

-- 0=normal/enabled, 1=paused in ScheduleConstants; never infer live state from a migration file.
SELECT job_id, job_name, job_group, invoke_target, cron_expression, concurrent, status
FROM sys_job
WHERE invoke_target IN (
 'roamRewardDeliveryTask.run()',
 'refundReconcileTask.reconcile()',
 'roamPresenceRevokeAuditTask.run()',
 'verificationQrcodeRepairTask.repairMissing()',
 'imNotificationDeliveryTask.deliverPending()',
 'officialEventTask.run()',
 'merchantCrmDeliveryRecoveryTask.run()'
)
ORDER BY invoke_target, job_id;

SELECT invoke_target, COUNT(*) AS definition_count
FROM sys_job
WHERE invoke_target IN (
 'roamRewardDeliveryTask.run()', 'refundReconcileTask.reconcile()',
 'roamPresenceRevokeAuditTask.run()', 'verificationQrcodeRepairTask.repairMissing()',
 'imNotificationDeliveryTask.deliverPending()', 'officialEventTask.run()',
 'merchantCrmDeliveryRecoveryTask.run()'
)
GROUP BY invoke_target HAVING COUNT(*) <> 1;
-- Missing targets return no row above: compare the complete expected list in README, not only HAVING output.

SELECT invoke_target, status, COUNT(*) AS log_count, MAX(start_time) AS latest_started
FROM sys_job_log
WHERE invoke_target IN (
 'roamRewardDeliveryTask.run()', 'refundReconcileTask.reconcile()',
 'roamPresenceRevokeAuditTask.run()', 'verificationQrcodeRepairTask.repairMissing()',
 'imNotificationDeliveryTask.deliverPending()', 'officialEventTask.run()',
 'merchantCrmDeliveryRecoveryTask.run()'
)
GROUP BY invoke_target, status;
-- A successful scheduler invocation does not prove any notification was delivered.
-- Builtin export cleanup is registered in code, not necessarily present in sys_job_log.

SELECT table_name, engine FROM information_schema.tables
WHERE table_schema=DATABASE() AND table_name IN (
 'im_notification_intent','roam_badge_delivery','roam_badge_reconcile_cursor',
 'roam_reward_delivery','roam_presence_revoke_intent','refund_application',
 'official_broadcast','official_broadcast_log','merchant_crm_campaign_recipient'
);
SELECT table_name, column_name, column_type
FROM information_schema.columns
WHERE table_schema=DATABASE()
 AND table_name IN ('im_notification_intent','official_broadcast_log','refund_application')
ORDER BY table_name, ordinal_position;
SELECT table_name, index_name, non_unique, seq_in_index, column_name
FROM information_schema.statistics
WHERE table_schema=DATABASE()
 AND table_name IN ('im_notification_intent','official_broadcast_log')
ORDER BY table_name, index_name, seq_in_index;

-- Only after the exact B schema has been confirmed present; aggregate only, no message/recipient content.
SELECT status, COUNT(*) AS intent_count FROM im_notification_intent GROUP BY status;
SELECT COUNT(*) AS original_signup_intents
FROM im_notification_intent WHERE event_key LIKE 'registration-signup:%';

-- Shows payout population, NOT notification delivery. No row is changed or replayed.
SELECT payout_status, COUNT(*) AS refunds FROM refund_application
WHERE del_flag=0 GROUP BY payout_status;
