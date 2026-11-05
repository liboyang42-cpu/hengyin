-- =====================================================================================
-- production-readonly-precheck.sql  (r1-release second pass, tested commit 573c463b3)
-- =====================================================================================
-- For the owner's own read-only session on the production database. Nobody else runs it.
-- Every statement is a SELECT. No write, DDL, SET, PREPARE or transaction statement.
-- Suggested: a read-only account, or START TRANSACTION READ ONLY in your own session first.
-- No host, port or account is written here.
--
-- What it answers, per migration new against master c6eac3b8c (plus the one master file the
-- rollback jar 014fc0d83 lacks):
--   * already applied? (the file's own runner assertion, asked read-only)
--   * will the file stop on its pre-assertion? (the rows it requires)
--   * what will it change? (row counts and current values to keep for rollback)
-- Save the whole output. It is also what the rollback notes say to "SELECT and keep first".
--
-- Second tool the repo already has for "applied?": python3 ci/audit_migrations_applied.py <keyword>
-- (replays only the runner assertions, read-only). It cannot see the pre-assertions or the
-- data previews below, which is why this file exists.
-- =====================================================================================

-- [0] Server identity and time zone. Several migrations compare with NOW()
--     (gather point backfill: end_date > NOW()); the app runs in Asia/Shanghai.
SELECT VERSION() AS mysql_version, DATABASE() AS current_schema, @@transaction_read_only AS session_read_only,
       @@global.time_zone AS global_tz, @@session.time_zone AS session_tz, @@system_time_zone AS system_tz,
       NOW() AS db_now;

-- [1] Base the release assumes: master c6eac3b8c already applied.
--     If any of these is 0, production is NOT at master and the release list is longer than 13 files
--     (see release-closeout-20261029/migration-ledger.md: production's applied state was UNKNOWN).
SELECT 'author_write_receipt table (on master, not in 014fc0d83)' AS check_item,
       (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'app_author_write_receipt') AS value
UNION ALL SELECT 'master post-deploy job merchantCommerceReconcileTask', (SELECT COUNT(*) FROM sys_job WHERE invoke_target = 'merchantCommerceReconcileTask.reconcile()')
UNION ALL SELECT 'master post-deploy job roamPresenceRevokeAuditTask', (SELECT COUNT(*) FROM sys_job WHERE invoke_target = 'roamPresenceRevokeAuditTask.run()')
UNION ALL SELECT 'master post-deploy jobs monitor (4 targets)', (SELECT COUNT(*) FROM sys_job WHERE invoke_target IN ('metricsProbeTask.run()','auditBacklogMonitorTask.run()','withdrawReconciliationTask.run()','settlementReconciliationTask.run()'));

-- [2] Already applied? One row per release migration. 1 = that file's own final assertion holds now.
--     For the three readback files and points-off, 1 can also mean "nothing to do".
SELECT 77 AS ledger_order, '20261102_merchant_industry_categories' AS migration_id,
       (SELECT COUNT(*) FROM sys_category WHERE type = 6 AND parent_id = 0 AND status = 1 AND del_flag = 0) AS type6_rows,
       NULL AS applied_flag
UNION ALL SELECT 78, '20261103_content_safety_recheck', NULL,
       (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'content_safety_recheck') = 14
UNION ALL SELECT 79, '20261103_sensitive_content_library', NULL,
       (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND engine = 'InnoDB'
          AND table_name IN ('content_sensitive_word','content_sensitive_allow','content_sensitive_image','content_sensitive_hit')) = 4
       AND (SELECT COUNT(*) FROM sys_config WHERE config_key = 'content.sensitive.rescan.remove') = 1
       AND (SELECT COUNT(*) FROM sys_menu WHERE perms IN ('business:sensitive:list','business:sensitive:edit')) = 2
UNION ALL SELECT 80, '20261104_content_safety_jobs_post_deploy (POST-DEPLOY: must be 0 before the new jar)',
       (SELECT COUNT(*) FROM sys_job WHERE invoke_target IN ('contentSafetyRecheckTask.run()','sensitiveContentRescanTask.run()')), NULL
UNION ALL SELECT 81, '20261104_invitee_signup_points_off', NULL,
       (SELECT COUNT(*) FROM cms_points_setting WHERE id = 6 AND (status IS NULL OR status <> 0 OR value IS NULL OR value <> 0)) = 0
UNION ALL SELECT 83, '20261104_topic_total_time_seconds (rows still in minutes)',
       (SELECT COUNT(*) FROM cms_topic t JOIN (SELECT topic_id, SUM(IFNULL(node_time,0)) a, SUM(CASE WHEN merchant_member_id IS NULL THEN IFNULL(node_time,0) ELSE 0 END) h
          FROM cms_topic_node WHERE topic_id IS NOT NULL GROUP BY topic_id) n ON n.topic_id = t.id
        WHERE t.total_time > 0 AND (t.total_time = n.a OR t.total_time = n.h)), NULL
UNION ALL SELECT 111, '20261102_club_session_gather_point (rows it would fill)',
       (SELECT COUNT(*) FROM cms_activity a WHERE a.club_id IS NOT NULL AND a.club_id > 0 AND a.del_flag = 0 AND a.cancel_time IS NULL
          AND (a.end_date IS NULL OR a.end_date > NOW()) AND (a.latitude IS NULL OR a.latitude = '') AND (a.longitude IS NULL OR a.longitude = '')
          AND EXISTS (SELECT 1 FROM oms_ticket t WHERE t.owner_type = 2 AND t.owner_id = a.id AND t.del_flag = 0 AND t.gather_lat IS NOT NULL AND t.gather_lng IS NOT NULL)), NULL
UNION ALL SELECT 392, '20261103_settlement_rule_three_percent', NULL,
       (SELECT COUNT(*) FROM settlement_rule WHERE merchant_id = 0 AND biz_type IN ('activity','topic') AND status = 1 AND del_flag = 0
          AND platform_commission_rate = 0.0300 AND merchant_rate = 0.9700 AND creator_rate = 0.0000) = 2
UNION ALL SELECT 397, '20261102_club_checkin_phone_visible', NULL,
       (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'club_role_assignment' AND column_name = 'customer_phone_visible') = 1
UNION ALL SELECT 430, '20261104_merchant_activity_limit', NULL,
       (SELECT COUNT(*) FROM user_permission WHERE role = 'merchant' AND membership_level IS NULL AND can_create_event = 1 AND max_events = 3) = 1;

-- [3] Pre-assertions that STOP a file. Each must hold before the release starts.
SELECT 'sensitive_content_library: exactly 1 audit-center menu (else the guard aborts)' AS precondition,
       (SELECT COUNT(*) FROM sys_menu WHERE menu_type = 'M' AND path = 'audit-center') AS value, '= 1' AS required
UNION ALL SELECT 'three_percent: active generic activity rule rows', (SELECT COUNT(*) FROM settlement_rule WHERE merchant_id = 0 AND biz_type = 'activity' AND status = 1 AND del_flag = 0), '= 1'
UNION ALL SELECT 'three_percent: active generic topic rule rows', (SELECT COUNT(*) FROM settlement_rule WHERE merchant_id = 0 AND biz_type = 'topic' AND status = 1 AND del_flag = 0), '= 1'
UNION ALL SELECT 'three_percent: active generic topic_club rule rows', (SELECT COUNT(*) FROM settlement_rule WHERE merchant_id = 0 AND biz_type = 'topic_club' AND status = 1 AND del_flag = 0), '<= 1'
UNION ALL SELECT 'merchant_activity_limit: merchant default permission rows', (SELECT COUNT(*) FROM user_permission WHERE role = 'merchant' AND membership_level IS NULL), '= 1'
UNION ALL SELECT 'industry_categories: depends on 20261101_theme_categories (type-1 theme categories present)', (SELECT COUNT(*) FROM sys_category WHERE type = 1 AND parent_id = 0 AND del_flag = 0), '> 0'
UNION ALL SELECT 'post_deploy jobs: existing contentSafetyRecheckTask rows', (SELECT COUNT(*) FROM sys_job WHERE invoke_target = 'contentSafetyRecheckTask.run()'), '= 0 before release'
UNION ALL SELECT 'post_deploy jobs: existing sensitiveContentRescanTask rows', (SELECT COUNT(*) FROM sys_job WHERE invoke_target = 'sensitiveContentRescanTask.run()'), '= 0 before release'
UNION ALL SELECT 'total_time: rows the file converts whose result still equals a node-minute sum (pass-1 assertion fails, a re-run multiplies again)',
       (SELECT COUNT(*) FROM cms_topic t JOIN (SELECT topic_id, SUM(IFNULL(node_time,0)) a, SUM(CASE WHEN merchant_member_id IS NULL THEN IFNULL(node_time,0) ELSE 0 END) h
          FROM cms_topic_node WHERE topic_id IS NOT NULL GROUP BY topic_id) n ON n.topic_id = t.id
        WHERE t.total_time > 0 AND (t.total_time = n.a OR t.total_time = n.h) AND (t.total_time * 60 = n.a OR t.total_time * 60 = n.h)), '= 0';

-- [4] Values to keep for rollback (the files say "SELECT first and keep").
SELECT id, merchant_id, biz_type, platform_commission_rate, merchant_rate, creator_rate, inviter_rate, status, del_flag, update_time
FROM settlement_rule WHERE merchant_id = 0 AND biz_type IN ('activity','topic','topic_club') ORDER BY biz_type, id;
SELECT role, membership_level, can_create_event, max_events FROM user_permission WHERE role IN ('merchant','club','player') ORDER BY role, membership_level;
SELECT id, total_time FROM cms_topic t JOIN (SELECT topic_id, SUM(IFNULL(node_time,0)) a, SUM(CASE WHEN merchant_member_id IS NULL THEN IFNULL(node_time,0) ELSE 0 END) h
   FROM cms_topic_node WHERE topic_id IS NOT NULL GROUP BY topic_id) n ON n.topic_id = t.id
WHERE t.total_time > 0 AND (t.total_time = n.a OR t.total_time = n.h) ORDER BY t.id;
SELECT id, type, category_name, status, del_flag FROM sys_category WHERE parent_id = 0 AND type IN (1, 6)
  AND category_name IN ('咖啡茶饮','茶馆','书店','手作工坊','古着中古','文创杂货','花店','烘焙甜品','酒馆小酌','宠物友好') ORDER BY id;

-- [5] Points rules. The new jar pays rules 2/3/5/6 at payment success only when status = 1 and
--     del_flag = 0; the old jar paid by value alone. Any of 2/3/5 that is disabled here but has a
--     value > 0 is paying today and stops paying the moment the new jar starts. Rules 1/4/7 and
--     event rules 11/12/13 still pay regardless of status in the new jar (h3 owner questions).
SELECT id, title, value, status, del_flag, event_type, type,
       CASE WHEN id IN (2,3,5,6) AND value > 0 AND (status IS NULL OR status <> 1 OR IFNULL(del_flag,0) <> 0)
            THEN 'PAYS TODAY, STOPS ON NEW JAR' ELSE '' END AS deploy_effect
FROM cms_points_setting ORDER BY id;

-- [6] Wording rename: rows that will change (exact seed text) and rows that keep the old words.
SELECT 'template topics renamed' AS item, COUNT(*) AS value FROM cms_topic
 WHERE member_id = 100000 AND is_template = 1 AND create_by = 'seed_playpack_20260820' AND name = '三个盲盒' COLLATE utf8mb4_bin
UNION ALL SELECT 'merchant copies keeping 盲盒 (not changed by design)', COUNT(*) FROM cms_topic WHERE name LIKE '%盲盒%' AND NOT (member_id = 100000 AND is_template = 1)
UNION ALL SELECT 'sys_menu names still 探店日 after the file (text differs from seed)', COUNT(*) FROM sys_menu WHERE menu_name LIKE '探店日%'
  AND perms NOT IN ('business:clubhours:confirm','business:clubextra:approve','business:clubquality:confirm','business:clubquality:exclude','business:editionfinal:execute','business:clubhours:correct')
UNION ALL SELECT 'predict job name now', COUNT(*) FROM sys_job WHERE invoke_target = 'predictVoidExpiredTask.run()' AND job_name = '竞猜轮次超时作废' COLLATE utf8mb4_bin;
