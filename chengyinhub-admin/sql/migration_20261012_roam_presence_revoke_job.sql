-- POST_DEPLOY only: verify exact candidate jar, roamPresenceRevokeAuditTask.run(), schema and health, then obtain migration approval.
-- Creating this forward does not register or enable a task on any current instance.
-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='sys_job'
 AND column_name IN ('job_id','job_name','job_group','invoke_target','cron_expression','misfire_policy',
 'concurrent','status','create_by','create_time','remark'))=11
 AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='sys_job'
 AND column_name='job_id' AND column_key='PRI' AND extra LIKE '%auto_increment%')=1;
-- The pre-deploy durable intent schema is required before a compensator may be registered.
-- runner: assert=scalar-true
SELECT COUNT(*)=8 FROM information_schema.columns WHERE table_schema=DATABASE()
 AND table_name='roam_presence_revoke_intent'
 AND column_name IN ('request_id','member_id','event_type','state','attempt_count','owner_token','lease_until','next_attempt_at');
-- runner: assert=scalar-true
SELECT COUNT(*) <= 1 FROM sys_job WHERE CAST(invoke_target AS BINARY)=CAST('roamPresenceRevokeAuditTask.run()' AS BINARY);
INSERT INTO sys_job(job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_by,create_time,remark)
SELECT '附近分享撤回审计补偿','COMPLIANCE','roamPresenceRevokeAuditTask.run()','0 * * * * ?','3','1','0','system',NOW(),
 '只消费已提交intent；不重开分享；现有暂停/自定义计划不改；独立POST_DEPLOY审批'
WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE CAST(invoke_target AS BINARY)=CAST('roamPresenceRevokeAuditTask.run()' AS BINARY));
-- Existing rows may deliberately remain paused. Preserve their state and operator-approved schedule.
-- runner: assert=scalar-true
SELECT COUNT(*)=1 FROM sys_job WHERE CAST(invoke_target AS BINARY)=CAST('roamPresenceRevokeAuditTask.run()' AS BINARY);
