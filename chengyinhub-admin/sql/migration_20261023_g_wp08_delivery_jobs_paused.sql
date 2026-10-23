-- G/WP08 POST_DEPLOY DML candidate only; NOT executed on production or this task VM.
-- Verify the exact jar admits ONLY the two bean/method pairs and preserves the existing coordinator/fence.
-- Use the approved assertion-aware migration runner, exclusively against the reviewed target schema.
-- No DDL, UPDATE, DELETE, cron change or activation. New rows PAUSED; every existing row stays untouched.
-- R7 cron and R1 cron match their original 20261013 seeds. Separate owner approval is needed to enable either.
-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='sys_job'
 AND column_name IN ('job_id','job_name','job_group','invoke_target','cron_expression','misfire_policy',
 'concurrent','status','create_by','create_time','remark'))=11
 AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='sys_job'
 AND column_name='job_id' AND column_key='PRI' AND extra LIKE '%auto_increment%')=1;
-- Original intent/cursor schemas must exist before either compensator is registered.
-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='im_notification_intent'
 AND column_name IN ('id','event_key','recipient','msg_type','content','extra_json','request_hash','status','message_id','create_time','delivered_at'))=11
 AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='roam_reward_delivery'
 AND column_name IN ('drop_log_id','member_id','coupon_id','extra_xp','reward_kind','state','history_id','created_at','updated_at'))=9
 AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='roam_reward_reconcile_cursor'
 AND column_name IN ('id','after_id','through_id'))=3
 AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='roam_badge_delivery'
 AND column_name IN ('id','member_id','session_id','badge_code','source_id','source_type','badge_name','icon_url','obtain_time','state','delivered_at'))=11
 AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='roam_badge_reconcile_cursor'
 AND column_name IN ('id','after_id','through_id'))=3;
-- All original local-effect tables must be transactional; R7 source-message idempotency must be installed.
-- This is a schema guard, not evidence that provider permissions, payloads or the actual consumer suite passed.
-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND engine='InnoDB'
 AND table_name IN ('sys_job','im_notification_intent','im_conversation','im_conversation_member','im_message',
 'roam_reward_delivery','roam_reward_reconcile_cursor','reward_drop_log','roam_badge_delivery',
 'roam_badge_reconcile_cursor','member_growth','member_exp_earned','player_badge','ums_member'))=14
 AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='im_message'
 AND column_name IN ('client_message_id','request_hash','visible_at'))=3
 AND (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics
 WHERE table_schema=DATABASE() AND table_name='im_message' AND index_name='uk_im_message_client' AND non_unique=0)
 ='conversation_id,sender_id,client_message_id';
-- Reject duplicate exact targets BEFORE any insert, including outer-space variants.
-- runner: assert=scalar-true
SELECT NOT EXISTS (SELECT 1 FROM sys_job
 WHERE CAST(TRIM(invoke_target) AS BINARY) IN
 (CAST('imNotificationDeliveryTask.deliverPending()' AS BINARY),CAST('roamRewardDeliveryTask.run()' AS BINARY))
 GROUP BY CAST(TRIM(invoke_target) AS BINARY) HAVING COUNT(*)>1);
-- Known class forms, no-parenthesis and internal-space variants are not exact admitted targets.
-- Do not insert a second canonical invocation beside them. Unknown bean aliases still require the
-- existing application canonical-definition check before scheduling; SQL cannot resolve Spring aliases.
-- runner: assert=scalar-true
SELECT NOT EXISTS (SELECT 1 FROM sys_job
 WHERE REGEXP_LIKE(REGEXP_REPLACE(invoke_target,'[[:space:]]',''),
 '^(imNotificationDeliveryTask|com[.]chengyinhub[.]business[.]task[.]ImNotificationDeliveryTask)[.]deliverPending([(][)])?$|^(roamRewardDeliveryTask|com[.]chengyinhub[.]business[.]task[.]RoamRewardDeliveryTask)[.]run([(][)])?$','c')
 AND CAST(TRIM(invoke_target) AS BINARY) NOT IN
 (CAST('imNotificationDeliveryTask.deliverPending()' AS BINARY),CAST('roamRewardDeliveryTask.run()' AS BINARY)));
START TRANSACTION;
INSERT INTO sys_job(job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_by,create_time,remark)
SELECT '原站内通知意图投递','DEFAULT','imNotificationDeliveryTask.deliverPending()','0 0/1 * * * ?','3','1','1','system',NOW(),
 'G/WP08 默认暂停；只消费原站内通知意图；已登记行/暂停/自定义计划保持原样；启用须另行批准'
WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE CAST(TRIM(invoke_target) AS BINARY)=CAST('imNotificationDeliveryTask.deliverPending()' AS BINARY));
INSERT INTO sys_job(job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_by,create_time,remark)
SELECT '原漫游掉落补交付','DEFAULT','roamRewardDeliveryTask.run()','0 0/10 * * * ?','3','1','1','system',NOW(),
 'G/WP08 默认暂停；只恢复冻结掉落/徽章；不重掷、不重开finish；已登记行保持原样；启用须另行批准'
WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE CAST(TRIM(invoke_target) AS BINARY)=CAST('roamRewardDeliveryTask.run()' AS BINARY));
-- Existing rows can be paused OR explicitly active. Do not assert/overwrite their approved status or cron.
-- runner: assert=scalar-true
SELECT COUNT(*)=2 AND COUNT(DISTINCT CAST(TRIM(invoke_target) AS BINARY))=2 FROM sys_job
 WHERE CAST(TRIM(invoke_target) AS BINARY) IN
 (CAST('imNotificationDeliveryTask.deliverPending()' AS BINARY),CAST('roamRewardDeliveryTask.run()' AS BINARY));
COMMIT;
