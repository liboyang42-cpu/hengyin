-- 城瘾专业社区广场维护任务注册。
-- 阶段：post-deploy；仅在包含 CommunityPostMaintenanceTask 的生产 jar 完成部署并通过 SHA/进程回读后执行。
-- 前置：migration_community_post_platform_20260909.sql。

-- pre-deploy 到新 jar 切换期间旧进程仍可能写入空快照；新 jar 回读通过后统一补齐并收紧。
-- 旧 payload_hash 保持原字节不变。数据库 JSON 文本序列化与 Java FastJSON 不保证空格一致，
-- 因此用显式 LEGACY 版本标记让服务继续按旧哈希规范重放，避免破坏历史 requestId 幂等。
UPDATE community_report_case
SET policy_version=IF(policy_version IS NULL OR TRIM(policy_version)='',
                      'COMMUNITY_REPORT_POLICY_LEGACY', policy_version),
    reason_label_snapshot=IF(reason_label_snapshot IS NULL OR TRIM(reason_label_snapshot)='',
      CASE reason_code
        WHEN 'DANGEROUS' THEN '现实人身危险或违法行为'
        WHEN 'MINOR_SAFETY' THEN '未成年人安全'
        WHEN 'SELF_HARM' THEN '自伤或自杀风险'
        WHEN 'SEXUAL_CONTENT' THEN '色情低俗内容'
        WHEN 'HARASSMENT' THEN '人身攻击或骚扰'
        WHEN 'HATE' THEN '仇恨或歧视'
        WHEN 'FRAUD' THEN '诈骗'
        WHEN 'IMPERSONATION' THEN '冒充他人'
        WHEN 'FALSE_INFORMATION' THEN '虚假信息'
        WHEN 'SPAM' THEN '垃圾广告或站外导流'
        WHEN 'PRIVACY' THEN '隐私泄露'
        WHEN 'INTELLECTUAL_PROPERTY' THEN '知识产权或内容盗用'
        WHEN 'OTHER' THEN '其他'
        ELSE '未知原因代码'
      END, reason_label_snapshot),
    priority_snapshot=IF(priority_snapshot NOT IN ('URGENT','STANDARD') OR priority_snapshot IS NULL,
      CASE WHEN reason_code IN ('DANGEROUS','MINOR_SAFETY','SELF_HARM') THEN 'URGENT' ELSE 'STANDARD' END,
      priority_snapshot),
    first_response_minutes_snapshot=IF(first_response_minutes_snapshot IS NULL
                                         OR first_response_minutes_snapshot NOT BETWEEN 1 AND 10080,
      CASE WHEN reason_code IN ('DANGEROUS','MINOR_SAFETY','SELF_HARM') THEN 15 ELSE 1440 END,
      first_response_minutes_snapshot)
WHERE policy_version IS NULL OR TRIM(policy_version)=''
   OR reason_label_snapshot IS NULL OR TRIM(reason_label_snapshot)=''
   OR priority_snapshot IS NULL OR priority_snapshot NOT IN ('URGENT','STANDARD')
   OR first_response_minutes_snapshot IS NULL
   OR first_response_minutes_snapshot NOT BETWEEN 1 AND 10080;

-- 四列有意保持可空：旧 jar 回滚后仍按旧 INSERT 合同写入；新服务读取时
-- fail-closed 补齐快照。不能用 NOT NULL 换取表面整洁而破坏生产回滚能力。

-- runner: assert=scalar-true
SELECT NOT EXISTS (
  SELECT 1 FROM community_report_case
  WHERE policy_version IS NULL OR TRIM(policy_version)=''
     OR reason_label_snapshot IS NULL OR TRIM(reason_label_snapshot)=''
     OR priority_snapshot NOT IN ('URGENT','STANDARD')
     OR first_response_minutes_snapshot NOT BETWEEN 1 AND 10080
) AS assertion_ok;

-- 新 jar 已切换后再次修复 pre-deploy 与切换窗口内旧 jar 写入的默认作用域。
-- 必须先按 member_id 的权威作用域去重，否则 UPDATE 可能撞现有唯一键。
DELETE duplicate_event
FROM biz_event_log duplicate_event
JOIN biz_event_log canonical_event
  ON canonical_event.id < duplicate_event.id
 AND canonical_event.event_name=duplicate_event.event_name
 AND (CASE WHEN canonical_event.member_id IS NULL
           THEN 'ANONYMOUS' ELSE CONCAT('MEMBER:', canonical_event.member_id) END)
     = (CASE WHEN duplicate_event.member_id IS NULL
             THEN 'ANONYMOUS' ELSE CONCAT('MEMBER:', duplicate_event.member_id) END)
 AND canonical_event.idempotency_key=duplicate_event.idempotency_key
WHERE duplicate_event.idempotency_key IS NOT NULL;

UPDATE biz_event_log
SET actor_scope=CASE WHEN member_id IS NULL THEN 'ANONYMOUS' ELSE CONCAT('MEMBER:', member_id) END
WHERE actor_scope <> CASE WHEN member_id IS NULL
                          THEN 'ANONYMOUS' ELSE CONCAT('MEMBER:', member_id) END;

-- runner: assert=scalar-true
SELECT COUNT(*) = 0 AS assertion_ok
FROM biz_event_log
WHERE actor_scope <> CASE WHEN member_id IS NULL
                          THEN 'ANONYMOUS' ELSE CONCAT('MEMBER:', member_id) END;

-- 每分钟驱动 outbox、治理开始/到期与删除擦除。负责人：社区平台；长期任务，无到期日。
UPDATE sys_job
SET cron_expression='0 * * * * ?', misfire_policy='3', concurrent='1', status='0',
    remark='社区平台长期任务：新 jar 部署并回读后启用；消费帖文 outbox、应用/到期治理处置、执行删除搜索通知媒体擦除'
WHERE invoke_target='communityPostMaintenanceTask.run()';

INSERT INTO sys_job
  (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status,
   create_by, create_time, remark)
SELECT '广场帖文投影治理与擦除', 'DEFAULT', 'communityPostMaintenanceTask.run()', '0 * * * * ?',
       '3', '1', '0', 'admin', NOW(),
       '社区平台长期任务：新 jar 部署并回读后启用；消费帖文 outbox、应用/到期治理处置、执行删除搜索通知媒体擦除'
WHERE NOT EXISTS (
  SELECT 1 FROM sys_job WHERE invoke_target = 'communityPostMaintenanceTask.run()'
);

-- runner: assert=scalar-true
SELECT COUNT(*) = 1 AS assertion_ok
FROM sys_job
WHERE invoke_target = 'communityPostMaintenanceTask.run()'
  AND cron_expression = '0 * * * * ?' AND misfire_policy = '3'
  AND concurrent = '1' AND status = '0';

-- 每日 00:10 重算最近 15 个完整自然日，统一吸收允许时间窗内的迟到事件。
UPDATE sys_job
SET status='1', remark='已由 analyticsAggregationTask.aggregateRecentWindow() 替代'
WHERE invoke_target='analyticsAggregationTask.aggregateYesterday()';

UPDATE sys_job
SET cron_expression='0 10 0 * * ?', misfire_policy='2', concurrent='1', status='0',
    remark='社区平台长期任务：每日重算最近15个完整自然日，吸收允许时间窗内迟到事件；删除与重建同事务'
WHERE invoke_target='analyticsAggregationTask.aggregateRecentWindow()';

INSERT INTO sys_job
  (job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status,
   create_by, create_time, remark)
SELECT '每日行为漏斗聚合', 'DEFAULT', 'analyticsAggregationTask.aggregateRecentWindow()', '0 10 0 * * ?',
       '2', '1', '0', 'admin', NOW(),
       '社区平台长期任务：每日重算最近15个完整自然日，吸收允许时间窗内迟到事件；删除与重建同事务'
WHERE NOT EXISTS (
  SELECT 1 FROM sys_job WHERE invoke_target = 'analyticsAggregationTask.aggregateRecentWindow()'
);

-- runner: assert=scalar-true
SELECT COUNT(*) = 1 AS assertion_ok
FROM sys_job
WHERE invoke_target = 'analyticsAggregationTask.aggregateRecentWindow()'
  AND cron_expression = '0 10 0 * * ?' AND misfire_policy = '2'
  AND concurrent = '1' AND status = '0';
