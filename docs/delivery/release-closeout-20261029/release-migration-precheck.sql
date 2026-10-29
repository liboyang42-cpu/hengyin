-- =====================================================================================
-- release-migration-precheck.sql —— 发布迁移只读预检（release-closeout-20261029）
-- =====================================================================================
-- 用途：在执行任何发布迁移之前，由 owner 对生产库运行一次，把 release-migration-pack.md
--       列出的全部迁移逐个判为 APPLIED / NEEDED / PARTIAL / CONFLICT / BLOCKED_PREREQ / DATA_PREVIEW。
-- 只读：本文件每一条语句都是 SELECT（只查 information_schema 与业务表），没有任何写入、DDL、
--       SET、PREPARE 或事务语句。ci/release_migration_rehearsal.py 在执行前会逐句校验这一点，
--       出现非 SELECT 语句直接拒绝。
-- 连接：本文件不含、也不应含任何主机、端口、账号。由 owner 用自己的只读通道执行，
--       建议使用只读账号，或在会话里先 SET SESSION TRANSACTION READ ONLY（不在本文件里）。
-- 版本：期望值（列/索引指纹）由 ci/release_migration_rehearsal.py 在 MySQL 8.0 上回读钉住：
--       基线状态与执行后状态逐项以 pack 为准；商家两项因含数据搬迁也保留 DATA_PREVIEW，
--       人为制造同名异形表 / 重复键必须判 CONFLICT / BLOCKED_PREREQ。
--
-- 状态含义：
--   APPLIED         对象已存在且形状与迁移一致；是否允许执行/跳过仍以 pack 的幂等约定为准。
--   NEEDED          对象都不存在；需要执行。
--   PARTIAL         部分对象已存在且形状正确、其余缺失；迁移是续跑式幂等的，可执行。
--   CONFLICT        存在同名但定义不同的表/列/索引，或种子键/任务重复（迁移断言要求恰好 1 行）。
--                   禁止执行，先由 owner 人工处置。
--   BLOCKED_PREREQ  依赖的表/角色/菜单不存在或不唯一；先查前置迁移，禁止执行。
--   DATA_PREVIEW    含数据回填的迁移：前置列已就位，仍须跑附录 B 的数据预览/回读；不能据此宣称已完成。
--
-- 列/索引指纹算法（与演练脚本一致）：
--   列  = COUNT(*) + BIT_XOR(前 64 位 SHA2(ordinal_position|column_name|column_type|is_nullable|column_default))
--   索引 = COUNT(*) + BIT_XOR(前 64 位 SHA2(index_name|non_unique|seq_in_index|column_name))
--   不受 group_concat_max_len 影响；不比较注释与排序规则。
-- =====================================================================================

-- [1/7] 服务器身份（runner 要求 Oracle MySQL 8.0.45）
SELECT VERSION() AS mysql_version, @@version_comment AS version_comment, DATABASE() AS current_schema,
       @@transaction_read_only AS session_read_only;

-- [2/7] 主分类：每个发布迁移一行，exec_order 即执行序（与 ci/migration-dependencies.json 拓扑序一致）
SELECT 45 AS exec_order, '20261018_infomation_change_audit' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 cms_infomation_change_audit' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 7 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 1614295783807551701
       AND (SELECT COUNT(*) = 3 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 8595058884001806246 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'cms_infomation_change_audit')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cms_infomation_change_audit'), 3) AS c0) AS x0
UNION ALL
SELECT 46 AS exec_order, '20261022_audit_activity_switch_seed' AS migration_id,
  CASE WHEN GREATEST(c0, c1) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 4 THEN 'DATA_PREVIEW' WHEN GREATEST(c0, c1) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'sys_config 2 键' AS detail
FROM (SELECT
    IFNULL((SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_config WHERE config_key = 'audit.activity.pre_review_all'), 3) AS c0,
    IFNULL((SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_config WHERE config_key = 'audit.activity.high_risk_categories'), 3) AS c1) AS x1
UNION ALL
SELECT 47 AS exec_order, '20261022_f7_minor_guardian' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 minor_guardian_authorization' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 19 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 6888410287868121596
       AND (SELECT COUNT(*) = 9 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 4114035956669675034 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'minor_guardian_authorization')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'minor_guardian_authorization'), 3) AS c0) AS x2
UNION ALL
SELECT 48 AS exec_order, '20261022_f7_user_invoice' AS migration_id,
  CASE WHEN GREATEST(c0, c1, c2) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1, c2) = 4 THEN 'DATA_PREVIEW' WHEN GREATEST(c0, c1, c2) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1, c2) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1, c2) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 user_invoice_* x3' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 9 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 10141321915951413577
       AND (SELECT COUNT(*) = 3 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 105124317661958677 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'user_invoice_title')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'user_invoice_title'), 3) AS c0,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 17 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 7829857101861987626
       AND (SELECT COUNT(*) = 6 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 9785483506190842761 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'user_invoice_application')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'user_invoice_application'), 3) AS c1,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 8 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 459679600236933830
       AND (SELECT COUNT(*) = 5 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 124728697218517101 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'user_invoice_application_order')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'user_invoice_application_order'), 3) AS c2) AS x3
UNION ALL
SELECT 49 AS exec_order, '20261022_location_guard_switch_seed' AS migration_id,
  CASE WHEN GREATEST(c0, c1, c2) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1, c2) = 4 THEN 'DATA_PREVIEW' WHEN GREATEST(c0, c1, c2) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1, c2) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1, c2) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'sys_config 3 键' AS detail
FROM (SELECT
    IFNULL((SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_config WHERE config_key = 'play.arrive.location_guard.mode'), 3) AS c0,
    IFNULL((SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_config WHERE config_key = 'play.arrive.require_accuracy'), 3) AS c1,
    IFNULL((SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_config WHERE config_key = 'roam.visited_poi.stable_identity'), 3) AS c2) AS x4
UNION ALL
SELECT 50 AS exec_order, '20261022_merchant_desk_switch_seed' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'sys_config 1 键' AS detail
FROM (SELECT
    IFNULL((SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_config WHERE config_key = 'merchant.desk.f4.enabled'), 3) AS c0) AS x5
UNION ALL
SELECT 51 AS exec_order, '20261023_creative_square_like_unique' AS migration_id,
  CASE WHEN (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
                  AND table_name = 'ums_creative_square_like' AND engine = 'InnoDB') <> 1 THEN 'BLOCKED_PREREQ'
       WHEN (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
                  AND table_name = 'ums_creative_square_like' AND column_name IN ('member_id','data_id')) <> 2 THEN 'BLOCKED_PREREQ'
       WHEN EXISTS (SELECT 1 FROM ums_creative_square_like GROUP BY member_id, data_id HAVING COUNT(*) > 1) THEN 'CONFLICT'
       WHEN (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE()
                  AND table_name = 'ums_creative_square_like' AND index_name = 'uk_square_like_member_data') = 0 THEN 'NEEDED'
       WHEN (SELECT COUNT(*) = 2 AND SUM(non_unique = 0 AND sub_part IS NULL
                  AND index_type = 'BTREE' AND is_visible = 'YES'
                  AND ((seq_in_index = 1 AND column_name = 'member_id')
                    OR (seq_in_index = 2 AND column_name = 'data_id'))) = 2
             FROM information_schema.statistics WHERE table_schema = DATABASE()
                  AND table_name = 'ums_creative_square_like' AND index_name = 'uk_square_like_member_data') THEN 'APPLIED'
       ELSE 'CONFLICT' END AS status,
  '态度唯一键；重复 member_id/data_id 禁止自动删行，先由 owner 对账' AS detail
UNION ALL
SELECT 52 AS exec_order, '20261023_points_value_config_seed' AS migration_id,
  CASE WHEN c0 = 2 OR c1 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 AND c1 = 1 THEN 'APPLIED'
       WHEN c0 = 0 AND c1 = 0 THEN 'NEEDED'
       ELSE 'PARTIAL' END AS status,
  '积分汇率/单笔上限：每键恰好一行；已有配置值保持原样，不要求等于种子缺省值' AS detail
FROM (SELECT
    (SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_config
      WHERE config_key = 'points.deduct.rate_yuan_per_point') AS c0,
    (SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_config
      WHERE config_key = 'points.deduct.max_yuan_per_order') AS c1) AS points_value_config
UNION ALL
SELECT 53 AS exec_order, '20261023_wx_pay_order_intent' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 wx_pay_order_intent（单次执行）' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 17 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 17851509776243270029
       AND (SELECT COUNT(*) = 13 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 9627256126155115312 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'wx_pay_order_intent')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'wx_pay_order_intent'), 3) AS c0) AS n0
UNION ALL
SELECT 54 AS exec_order, '20261024_community_open_flags' AS migration_id,
  CASE WHEN GREATEST(c0, c1, c2) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1, c2) = 4 THEN 'DATA_PREVIEW' WHEN GREATEST(c0, c1, c2) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1, c2) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1, c2) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'sys_config 3 键（缺失才插入；已配置值保持原样）' AS detail
FROM (SELECT
    (SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_config WHERE config_key = 'feature.flag.communityPostRead') AS c0,
    (SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_config WHERE config_key = 'feature.flag.communityPostWrite') AS c1,
    (SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_config WHERE config_key = 'feature.flag.communityPostPilotMembers') AS c2) AS n20
UNION ALL
SELECT 55 AS exec_order, '20261024_d_committed_official_notice_wx' AS migration_id,
  CASE WHEN GREATEST(c0, c1) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 committed_official_notice_wx + 默认暂停的 sys_job committedOfficialNoticeTask（单次执行）' AS detail
FROM (SELECT
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_name IN ('id', 'event_key', 'recipient', 'title', 'body', 'im_intent_id', 'request_hash', 'wx_state', 'owner_token', 'created_at', 'attempted_at', 'accepted_at')) = 12 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'committed_official_notice_wx') AS c0,
    (SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_job WHERE CAST(TRIM(invoke_target) AS BINARY) = CAST('committedOfficialNoticeTask.deliverPending()' AS BINARY)) AS c1) AS z1
UNION ALL
SELECT 56 AS exec_order, '20261024_c_before_start_notice_typed' AS migration_id,
  CASE WHEN (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'committed_official_notice_wx' AND column_name = 'notice_type') = 1 AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'committed_official_notice_wx' AND column_name = 'wx_payload_json') = 1 THEN 'APPLIED' ELSE 'REPLACED_UNAPPLIED' END AS status,
  '已被 20261024_g_before_start_notice_metadata_v2 取代：任何情况下都不执行本文件（REPLACED_UNAPPLIED）' AS detail
UNION ALL
SELECT 57 AS exec_order, '20261024_d_npc_asr_intent' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 npc_asr_intent（单次执行）' AS detail
FROM (SELECT
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_name IN ('id', 'user_id', 'request_id', 'node_id', 'merchant_member_id', 'audio_sha256', 'audio_format', 'claim_token', 'state', 'transcript', 'body_erased', 'handoff_started', 'text_admitted', 'body_expires_at', 'create_time', 'update_time')) = 16 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'npc_asr_intent') AS c0) AS z3
UNION ALL
SELECT 58 AS exec_order, '20261024_d_npc_asr_shared_quota' AS migration_id,
  CASE WHEN GREATEST(c0, c1, c2) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1, c2) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1, c2) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1, c2) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'npc_asr_intent 配额 2 列 + 索引（单次执行）' AS detail
FROM (SELECT
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'npc_asr_intent' AND column_name = 'quota_reservation') AS c0,
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'npc_asr_intent' AND column_name = 'quota_refund_pending') AS c1,
    (SELECT IF(COUNT(*) > 0, 1, 0) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'npc_asr_intent' AND index_name = 'idx_npc_asr_quota_refund') AS c2) AS z4
UNION ALL
SELECT 59 AS exec_order, '20261024_g_before_start_notice_metadata_v2' AS migration_id,
  CASE WHEN GREATEST(c0, c1, c2, c3) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1, c2, c3) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1, c2, c3) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1, c2, c3) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'committed_official_notice_wx typed 两列 + CHECK + 索引（单次执行）' AS detail
FROM (SELECT
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'committed_official_notice_wx' AND column_name = 'notice_type') AS c0,
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'committed_official_notice_wx' AND column_name = 'wx_payload_json') AS c1,
    (SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND constraint_name = 'ck_committed_official_notice_typed') AS c2,
    (SELECT IF(COUNT(*) > 0, 1, 0) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'committed_official_notice_wx' AND index_name = 'idx_committed_official_notice_typed_pending') AS c3) AS z2
UNION ALL
SELECT 60 AS exec_order, '20261024_registration_verified_seats' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 cms_registration_seat（单次执行）' AS detail
FROM (SELECT
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_name IN ('id', 'registration_id', 'seat_no', 'status', 'topic_id', 'activity_id', 'verifier_member_id', 'verified_at')) = 8 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cms_registration_seat') AS c0) AS z5
UNION ALL
SELECT 61 AS exec_order, '20261024_registration_seat_activity_scope' AS migration_id,
  CASE WHEN s IS NULL THEN 'NEEDED' WHEN s LIKE '%topic_idisnullortopic_id>0%' THEN 'APPLIED'
       WHEN s LIKE '%topic_idisnotnullandtopic_id>0%' THEN 'NEEDED' ELSE 'CONFLICT' END AS status,
  'chk_registration_seat_fact 允许独立活动席位（可重入）' AS detail
FROM (SELECT (SELECT LOWER(REGEXP_REPLACE(cc.check_clause, '[[:space:]()`]', '')) FROM information_schema.check_constraints cc JOIN information_schema.table_constraints tc ON tc.constraint_schema = cc.constraint_schema AND tc.constraint_name = cc.constraint_name WHERE cc.constraint_schema = DATABASE() AND tc.table_name = 'cms_registration_seat' AND cc.constraint_name = 'chk_registration_seat_fact' LIMIT 1) AS s) AS z6
UNION ALL
SELECT 62 AS exec_order, '20261025_coop_invite_request_receipt' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 coop_invite_request_receipt' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 16 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 17316570512569307862
       AND (SELECT COUNT(*) = 6 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 8173663945025707328 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'coop_invite_request_receipt')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'coop_invite_request_receipt'), 3) AS c0) AS n2
UNION ALL
SELECT 63 AS exec_order, '20261026_official_event_attendance' AS migration_id,
  CASE WHEN GREATEST(c0, c1) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 4 THEN 'DATA_PREVIEW' WHEN GREATEST(c0, c1) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 official_event_attendance(_config)' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 5 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 522559710578164264
       AND (SELECT COUNT(*) = 1 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 14494209268198970070 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'official_event_attendance_config')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_event_attendance_config'), 3) AS c0,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 8 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 953998888564537766
       AND (SELECT COUNT(*) = 4 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 6967273786435203968 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'official_event_attendance')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_event_attendance'), 3) AS c1) AS n3
UNION ALL
SELECT 64 AS exec_order, '20261026_team_event_command_receipt' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 team_event_command_receipt' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 10 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 9596879265546310952
       AND (SELECT COUNT(*) = 5 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 14619782478392098117 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'team_event_command_receipt')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'team_event_command_receipt'), 3) AS c0) AS n4
UNION ALL
SELECT 65 AS exec_order, '20261026_theme_attribute_registry_version' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 theme_attribute_registry_version' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 2 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 4210680533194302826
       AND (SELECT COUNT(*) = 1 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 14128333254474228543 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'theme_attribute_registry_version')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'theme_attribute_registry_version'), 3) AS c0) AS n5
UNION ALL
SELECT 66 AS exec_order, '20261026_user_invoice_processing' AS migration_id,
  CASE WHEN GREATEST(c0, c1, c2, c3, c4, c5) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1, c2, c3, c4, c5) = 4 THEN 'DATA_PREVIEW' WHEN GREATEST(c0, c1, c2, c3, c4, c5) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1, c2, c3, c4, c5) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1, c2, c3, c4, c5) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 user_invoice_issuer/_owner/scope/processing/_audit/issued_reference x6' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 4 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 10688958830205911180
       AND (SELECT COUNT(*) = 1 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 12701328967475720248 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'user_invoice_issuer')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'user_invoice_issuer'), 3) AS c0,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 3 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 14613652917878962315
       AND (SELECT COUNT(*) = 3 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 10378390697567375918 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'user_invoice_issuer_owner')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'user_invoice_issuer_owner'), 3) AS c1,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 5 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 5951095820421503205
       AND (SELECT COUNT(*) = 2 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 8684828903915295743 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'user_invoice_scope')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'user_invoice_scope'), 3) AS c2,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 7 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 2465429317703911475
       AND (SELECT COUNT(*) = 5 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 11963289165687065790 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'user_invoice_processing')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'user_invoice_processing'), 3) AS c3,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 10 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 12133755110329877132
       AND (SELECT COUNT(*) = 4 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 12892123131179746560 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'user_invoice_processing_audit')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'user_invoice_processing_audit'), 3) AS c4,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 3 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 11483792453251363321
       AND (SELECT COUNT(*) = 3 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 9475326090238573468 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'user_invoice_issued_reference')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'user_invoice_issued_reference'), 3) AS c5) AS n6
UNION ALL
SELECT 67 AS exec_order, '20261026_user_invoice_title_receipt' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 user_invoice_title_receipt' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 5 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 10065551186701799896
       AND (SELECT COUNT(*) = 2 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 5863983891022771450 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'user_invoice_title_receipt')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'user_invoice_title_receipt'), 3) AS c0) AS n7
UNION ALL
SELECT 68 AS exec_order, '20261027_template_adoption_receipt' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 template_adoption_receipt' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 12 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 11451000278466319056
       AND (SELECT COUNT(*) = 2 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 8511979612919902689 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'template_adoption_receipt')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'template_adoption_receipt'), 3) AS c0) AS n8
UNION ALL
SELECT 69 AS exec_order, '20261027_user_invoice_processing_backfill' AS migration_id,
  CASE WHEN c0 = 4 THEN 'DATA_PREVIEW' ELSE 'NEEDED' END AS status,
  '数据回填：处理行就位后须跑附录 C1 只读预览；DATA_PREVIEW 不是回填完成证明' AS detail
FROM (SELECT
    IF(EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'user_invoice_application')
       AND EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'user_invoice_processing')
       AND EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'user_invoice_issuer_owner'), 4, 0) AS c0) AS n28
UNION ALL
SELECT 70 AS exec_order, '20261028_topic_play_setup' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 cms_topic_play_setup' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 7 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 16548424480571454463
       AND (SELECT COUNT(*) = 1 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 14128333254474228543 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'cms_topic_play_setup')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cms_topic_play_setup'), 3) AS c0) AS n9
UNION ALL
SELECT 82 AS exec_order, 'ai_theme_draft_request_intent_20261024' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 ai_theme_draft_request_intent（单次执行）' AS detail
FROM (SELECT
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_name IN ('id', 'member_id', 'prepare_key', 'request_id', 'request_hash', 'product_type', 'plan_hash', 'role', 'pool', 'original_day', 'counter_key', 'nonce', 'receipt_key', 'reservation', 'creator_config_hash', 'gateway_config_hash', 'state', 'provider_state', 'reason_code', 'claim_token', 'result_hash', 'outcome_hash', 'claimed_at', 'created_at', 'updated_at', 'completed_at', 'execute_before', 'context_expires_at', 'result_expires_at', 'context_purged_at', 'result_purged_at')) = 31 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ai_theme_draft_request_intent') AS c0) AS z7
UNION ALL
SELECT 87 AS exec_order, 'audit_problem_20261022' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 audit_problem' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 11 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 10367431202118961532
       AND (SELECT COUNT(*) = 4 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 4534874757148141377 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'audit_problem')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'audit_problem'), 3) AS c0) AS x6
UNION ALL
SELECT 103 AS exec_order, '20261024_d_chapter_application_round' AS migration_id,
  CASE WHEN GREATEST(c0, c1) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'merchant_chapter_application.application_round + CHECK（单次执行）' AS detail
FROM (SELECT
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'merchant_chapter_application' AND column_name = 'application_round') AS c0,
    (SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND constraint_name = 'ck_merchant_application_round') AS c1) AS z8
UNION ALL
SELECT 127 AS exec_order, 'club_welcome_note_20261022' AS migration_id,
  CASE WHEN (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
                  AND table_name = 'club' AND engine = 'InnoDB') <> 1 THEN 'BLOCKED_PREREQ'
       WHEN (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
                  AND table_name = 'club' AND column_name = 'welcome_note') = 0 THEN 'NEEDED'
       WHEN (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
                  AND table_name = 'club' AND column_name = 'welcome_note'
                  AND data_type = 'varchar' AND character_maximum_length = 600
                  AND is_nullable = 'YES' AND column_default IS NULL
                  AND character_set_name = 'utf8mb4' AND extra = '' AND generation_expression = '') = 1 THEN 'APPLIED'
       ELSE 'CONFLICT' END AS status,
  'welcome_note 普通可空列；NEEDED 才执行一次，APPLIED 必须跳过原 SQL' AS detail
UNION ALL
SELECT 160 AS exec_order, 'doc2_f3_bundle_ledger_20261022' AS migration_id,
  CASE WHEN GREATEST(c0, c1, c2, c3, c4, c5) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1, c2, c3, c4, c5) = 4 THEN 'DATA_PREVIEW' WHEN GREATEST(c0, c1, c2, c3, c4, c5) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1, c2, c3, c4, c5) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1, c2, c3, c4, c5) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 explore_bundle_* x6' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 17 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 671791269261815392
       AND (SELECT COUNT(*) = 6 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 15015823104286135281 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'explore_bundle_order')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'explore_bundle_order'), 3) AS c0,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 13 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 17876054635586477635
       AND (SELECT COUNT(*) = 6 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 12745476408912327863 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'explore_bundle_grant')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'explore_bundle_grant'), 3) AS c1,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 15 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 14545559366968778862
       AND (SELECT COUNT(*) = 9 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 9807997739071868612 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'explore_bundle_unit')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'explore_bundle_unit'), 3) AS c2,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 16 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 5124830620868548920
       AND (SELECT COUNT(*) = 8 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 17007312391524083324 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'explore_bundle_reservation')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'explore_bundle_reservation'), 3) AS c3,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 5 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 8192314445815828249
       AND (SELECT COUNT(*) = 4 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 16910464094377607791 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'explore_bundle_waiver')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'explore_bundle_waiver'), 3) AS c4,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 11 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 2652816178438157576
       AND (SELECT COUNT(*) = 5 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 11477923173872796512 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'explore_bundle_exit_case')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'explore_bundle_exit_case'), 3) AS c5) AS x7
UNION ALL
SELECT 161 AS exec_order, 'doc2_f3_refund_hold_20261026' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 explore_bundle_refund_hold' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 10 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 11813387773646313411
       AND (SELECT COUNT(*) = 2 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 12929690359715079447 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'explore_bundle_refund_hold')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'explore_bundle_refund_hold'), 3) AS c0) AS n10
UNION ALL
SELECT 202 AS exec_order, '20261023_cash_withdraw_fund_fact' AS migration_id,
  CASE WHEN GREATEST(c0, c1, c2) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1, c2) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1, c2) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1, c2) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 member_cash_withdraw_fund_fact + 两个只追加触发器（单次执行）' AS detail
FROM (SELECT
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_name IN ('id', 'withdrawal_id', 'member_id', 'amount', 'out_batch_no', 'out_detail_no', 'kind', 'submission_intent_id', 'balance_before', 'balance_after', 'channel_batch_status', 'channel_detail_status', 'channel_detail_id', 'channel_error_code', 'create_time')) = 15 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'member_cash_withdraw_fund_fact') AS c0,
    (SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema = DATABASE() AND trigger_name = 'cash_fund_fact_no_update') AS c1,
    (SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema = DATABASE() AND trigger_name = 'cash_fund_fact_no_delete') AS c2) AS z9
UNION ALL
SELECT 203 AS exec_order, '20261024_cash_withdraw_transfer_intent' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 member_cash_withdraw_transfer_intent（单次执行）' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 16 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 11618201005589884506
       AND (SELECT COUNT(*) = 3 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 12031721957538158388 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'member_cash_withdraw_transfer_intent')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'member_cash_withdraw_transfer_intent'), 3) AS c0) AS n1
UNION ALL
SELECT 204 AS exec_order, '20261025_cash_withdraw_fund_request_compat' AS migration_id,
  CASE WHEN GREATEST(c0, c1, c2, c3, c4, c5, c6, c7, c8) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1, c2, c3, c4, c5, c6, c7, c8) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1, c2, c3, c4, c5, c6, c7, c8) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1, c2, c3, c4, c5, c6, c7, c8) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'member_cash_withdraw_fund_fact 请求身份 8 列 + CHECK（单次执行）' AS detail
FROM (SELECT
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'member_cash_withdraw_fund_fact' AND column_name = 'request_mchid') AS c0,
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'member_cash_withdraw_fund_fact' AND column_name = 'request_appid') AS c1,
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'member_cash_withdraw_fund_fact' AND column_name = 'request_amount_fen') AS c2,
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'member_cash_withdraw_fund_fact' AND column_name = 'request_openid') AS c3,
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'member_cash_withdraw_fund_fact' AND column_name = 'request_remark') AS c4,
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'member_cash_withdraw_fund_fact' AND column_name = 'request_version') AS c5,
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'member_cash_withdraw_fund_fact' AND column_name = 'request_hash') AS c6,
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'member_cash_withdraw_fund_fact' AND column_name = 'channel_batch_id') AS c7,
    (SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND constraint_name = 'ck_cash_fund_request') AS c8) AS z10
UNION ALL
SELECT 209 AS exec_order, 'member_profile_version_20261022' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 ums_member_profile_version' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 7 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 6303910045254689405
       AND (SELECT COUNT(*) = 3 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 16657050347810390992 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'ums_member_profile_version')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ums_member_profile_version'), 3) AS c0) AS x8
UNION ALL
SELECT 211 AS exec_order, 'member_visited_poi_poi_key_20261022' AS migration_id,
  CASE WHEN p0 = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 4 THEN 'DATA_PREVIEW' WHEN GREATEST(c0, c1) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'member_visited_poi.poi_key + uk_member_poi_key' AS detail
FROM (SELECT
    IFNULL((SELECT IF(COUNT(*) = 1, 1, 3) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'member_visited_poi' AND engine = 'InnoDB'), 3) AS p0,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'varchar(32)' AND is_nullable = 'YES' AND column_default IS NULL) = 1 THEN 1 ELSE 2 END
   FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'member_visited_poi' AND column_name = 'poi_key'), 3) AS c0,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 2 AND SUM(non_unique = 0) = 2
       AND GROUP_CONCAT(column_name ORDER BY seq_in_index) = 'member_id,poi_key' THEN 1 ELSE 2 END
   FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'member_visited_poi' AND index_name = 'uk_member_poi_key'), 3) AS c1) AS x9
UNION ALL
SELECT 217 AS exec_order, 'merchant_category_ids_20261022' AS migration_id,
  CASE WHEN (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
                  AND table_name = 'mms_merchant' AND engine = 'InnoDB') <> 1 THEN 'BLOCKED_PREREQ'
       WHEN (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
                  AND table_name = 'mms_merchant' AND column_name IN ('category_id')) <> 1 THEN 'BLOCKED_PREREQ'
       WHEN (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
                  AND table_name = 'mms_merchant' AND column_name = 'category_ids') = 0 THEN 'NEEDED'
       WHEN (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
                  AND table_name = 'mms_merchant' AND column_name = 'category_ids'
                  AND data_type = 'varchar' AND character_maximum_length = 255
                  AND is_nullable = 'YES' AND column_default IS NULL
                  AND character_set_name = 'utf8mb4' AND extra = '' AND generation_expression = '') = 1 THEN 'DATA_PREVIEW'
       ELSE 'CONFLICT' END AS status,
  'category_ids 结构齐全仍须附录 B3 核对数据；DATA_PREVIEW 不是迁移已完成证明' AS detail
UNION ALL
SELECT 223 AS exec_order, '20261027_merchant_brand_images_to_gallery' AS migration_id,
  CASE WHEN p0 = 3 OR c0 = 3 THEN 'BLOCKED_PREREQ' ELSE 'DATA_PREVIEW' END AS status,
  '数据搬迁：有品牌形象图的商家须跑附录 C2 预览；DATA_PREVIEW 不是搬迁完成证明' AS detail
FROM (SELECT
    IFNULL((SELECT IF(COUNT(*) = 1, 1, 3) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'mms_merchant' AND engine = 'InnoDB'), 3) AS p0,
    IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'mms_merchant'
         AND column_name IN ('derivatives', 'gallery')) = 2, 4, 3) AS c0) AS n29
UNION ALL
SELECT 225 AS exec_order, 'merchant_desk_receipt_20261022' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 merchant_desk_receipt' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 9 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 12383343389863775861
       AND (SELECT COUNT(*) = 3 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 3217512040811375428 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'merchant_desk_receipt')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'merchant_desk_receipt'), 3) AS c0) AS x10
UNION ALL
SELECT 236 AS exec_order, 'merchant_redemption_anchor_20261022' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 merchant_redemption_anchor' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 11 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 17232808289079433777
       AND (SELECT COUNT(*) = 2 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 10133716487572001376 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'merchant_redemption_anchor')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'merchant_redemption_anchor'), 3) AS c0) AS x11
UNION ALL
SELECT 240 AS exec_order, 'merchant_wechat_qrcode_20261022' AS migration_id,
  CASE WHEN (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
                  AND table_name = 'mms_merchant' AND engine = 'InnoDB') <> 1 THEN 'BLOCKED_PREREQ'
       WHEN (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
                  AND table_name = 'mms_merchant' AND column_name IN ('wechat')) <> 1 THEN 'BLOCKED_PREREQ'
       WHEN (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
                  AND table_name = 'mms_merchant' AND column_name = 'wechat_qrcode') = 0 THEN 'NEEDED'
       WHEN (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
                  AND table_name = 'mms_merchant' AND column_name = 'wechat_qrcode'
                  AND data_type = 'varchar' AND character_maximum_length = 512
                  AND is_nullable = 'YES' AND column_default IS NULL
                  AND character_set_name = 'utf8mb4' AND extra = '' AND generation_expression = '') = 1 THEN 'DATA_PREVIEW'
       ELSE 'CONFLICT' END AS status,
  'wechat_qrcode 结构齐全仍须附录 B4 核对数据；DATA_PREVIEW 不是迁移已完成证明' AS detail
UNION ALL
SELECT 262 AS exec_order, 'npc_voice_enrollment_intent_20261024' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 npc_voice_enrollment_intent（单次执行）' AS detail
FROM (SELECT
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_name IN ('id', 'npc_id', 'scope_type', 'scope_id', 'actor_member_id', 'sample_sha256', 'sample_reference', 'state', 'voice_id', 'reason_code', 'profile_version', 'created_at', 'updated_at')) = 13 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'npc_voice_enrollment_intent') AS c0) AS z11
UNION ALL
SELECT 263 AS exec_order, 'npc_voice_confirmed_attempt_20261024' AS migration_id,
  CASE WHEN GREATEST(c0, c1, c2, c3) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1, c2, c3) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1, c2, c3) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1, c2, c3) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'npc_voice_enrollment_intent 请求身份 2 列 + 唯一键 + CHECK（单次执行）' AS detail
FROM (SELECT
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'npc_voice_enrollment_intent' AND column_name = 'request_id') AS c0,
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'npc_voice_enrollment_intent' AND column_name = 'previous_intent_id') AS c1,
    (SELECT IF(COUNT(*) > 0, 1, 0) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'npc_voice_enrollment_intent' AND index_name = 'uk_nvei_request') AS c2,
    (SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND constraint_name = 'chk_nvei_request') AS c3) AS z12
UNION ALL
SELECT 264 AS exec_order, 'o14_city_code_backfill_log_20261022' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 o14_city_code_backfill_log' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 5 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 7322861083250056029
       AND (SELECT COUNT(*) = 2 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 15028227347182792313 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'o14_city_code_backfill_log')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'o14_city_code_backfill_log'), 3) AS c0) AS x12
UNION ALL
SELECT 265 AS exec_order, 'o14_cms_activity_city_code_20261022' AS migration_id,
  CASE WHEN p0 = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 4 THEN 'DATA_PREVIEW' WHEN GREATEST(c0, c1) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'cms_activity.city_code + 索引' AS detail
FROM (SELECT
    IFNULL((SELECT IF(COUNT(*) = 1, 1, 3) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'cms_activity' AND engine = 'InnoDB'), 3) AS p0,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'varchar(32)' AND is_nullable = 'YES' AND column_default IS NULL) = 1 THEN 1 ELSE 2 END
   FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cms_activity' AND column_name = 'city_code'), 3) AS c0,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 1 AND SUM(non_unique = 1) = 1
       AND GROUP_CONCAT(column_name ORDER BY seq_in_index) = 'city_code' THEN 1 ELSE 2 END
   FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'cms_activity' AND index_name = 'idx_cms_activity_city_code'), 3) AS c1) AS x13
UNION ALL
SELECT 266 AS exec_order, 'o14_cms_topic_city_code_20261022' AS migration_id,
  CASE WHEN p0 = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 4 THEN 'DATA_PREVIEW' WHEN GREATEST(c0, c1) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'cms_topic.city_code + 索引' AS detail
FROM (SELECT
    IFNULL((SELECT IF(COUNT(*) = 1, 1, 3) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'cms_topic' AND engine = 'InnoDB'), 3) AS p0,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'varchar(32)' AND is_nullable = 'YES' AND column_default IS NULL) = 1 THEN 1 ELSE 2 END
   FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cms_topic' AND column_name = 'city_code'), 3) AS c0,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 1 AND SUM(non_unique = 1) = 1
       AND GROUP_CONCAT(column_name ORDER BY seq_in_index) = 'city_code' THEN 1 ELSE 2 END
   FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'cms_topic' AND index_name = 'idx_cms_topic_city_code'), 3) AS c1) AS x14
UNION ALL
SELECT 267 AS exec_order, 'o14_content_city_code_backfill_20261022' AS migration_id,
  CASE WHEN p0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '数据回填:前置列就位后须跑 _precheck_o14_content_city_code_backfill_20261022.sql 预览' AS detail
FROM (SELECT
    IFNULL((SELECT IF(COUNT(*) = 1, 1, 3) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'club' AND engine = 'InnoDB'), 3) AS p0,
    IFNULL((SELECT IF(EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cms_topic' AND column_name = 'city_code') AND EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cms_activity' AND column_name = 'city_code') AND EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'o14_city_code_backfill_log'), 4, 0)), 3) AS c0) AS x15
UNION ALL
SELECT 268 AS exec_order, 'o14_content_search_document_20261022' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 content_search_document' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 10 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 8667109254857782881
       AND (SELECT COUNT(*) = 7 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 3318759553424233406 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'content_search_document')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'content_search_document'), 3) AS c0) AS x16
UNION ALL
SELECT 272 AS exec_order, '20261023_d_official_broadcast_delivery' AS migration_id,
  CASE WHEN pre <> 2 THEN 'BLOCKED_PREREQ'
       WHEN d = 4 AND s = 2 THEN 'APPLIED'
       WHEN d = 0 AND s = 0 AND o = 0 THEN 'NEEDED'
       WHEN d = 0 AND (o > 0 OR s > 0) THEN 'CONFLICT'
       ELSE 'PARTIAL' END AS status,
  '广播 D 协议 6 列；CONFLICT = 10-26 旧协议已单独落库（D 与 union 均不可执行，停下）' AS detail
FROM (SELECT (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('official_broadcast','official_broadcast_log') AND engine = 'InnoDB') AS pre, (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_broadcast_log' AND column_name IN ('delivery_title', 'delivery_sub', 'failure_code', 'reached_time')) AS d, (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_broadcast_log' AND column_name IN ('frozen_title', 'frozen_sub', 'intent_id', 'attempts', 'update_time')) AS o, (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_broadcast_log' AND column_name IN ('delivery_state','message_id')) AS s, (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_broadcast' AND column_name IN ('audience_frozen','client_request_id','request_owner','request_hash')) AS b, (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'official_broadcast_creation') AS cr, (SELECT MAX(column_type) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_broadcast_log' AND column_name = 'delivery_state') AS st) AS z13
UNION ALL
SELECT 273 AS exec_order, '20261026_official_broadcast_delivery' AS migration_id,
  CASE WHEN pre <> 2 THEN 'BLOCKED_PREREQ'
       WHEN d = 4 AND o = 5 AND b = 4 AND cr = 1 THEN 'APPLIED'
       WHEN d = 0 AND (o > 0 OR b > 0 OR cr > 0) THEN 'CONFLICT'
       ELSE 'REPLACED_UNAPPLIED' END AS status,
  '永不执行（被 D + union 取代）；APPLIED = union 已组合出超集；CONFLICT = 旧协议已单独落库' AS detail
FROM (SELECT (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('official_broadcast','official_broadcast_log') AND engine = 'InnoDB') AS pre, (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_broadcast_log' AND column_name IN ('delivery_title', 'delivery_sub', 'failure_code', 'reached_time')) AS d, (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_broadcast_log' AND column_name IN ('frozen_title', 'frozen_sub', 'intent_id', 'attempts', 'update_time')) AS o, (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_broadcast_log' AND column_name IN ('delivery_state','message_id')) AS s, (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_broadcast' AND column_name IN ('audience_frozen','client_request_id','request_owner','request_hash')) AS b, (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'official_broadcast_creation') AS cr, (SELECT MAX(column_type) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_broadcast_log' AND column_name = 'delivery_state') AS st) AS z14
UNION ALL
SELECT 274 AS exec_order, '20261028_official_broadcast_protocol_union' AS migration_id,
  CASE WHEN pre <> 2 THEN 'BLOCKED_PREREQ'
       WHEN d = 4 AND s = 2 AND o = 5 AND b = 4 AND cr = 1 AND st = 'varchar(32)' THEN 'APPLIED'
       WHEN o = 0 AND b = 0 AND cr = 0 AND (d = 4 OR (d = 0 AND s = 0)) THEN 'NEEDED'
       WHEN d = 0 AND (o > 0 OR b > 0 OR cr > 0) THEN 'CONFLICT'
       ELSE 'PARTIAL' END AS status,
  '广播双协议组合（单次执行；只接受 D 前缀）；CONFLICT = 10-26 旧协议已单独落库' AS detail
FROM (SELECT (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('official_broadcast','official_broadcast_log') AND engine = 'InnoDB') AS pre, (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_broadcast_log' AND column_name IN ('delivery_title', 'delivery_sub', 'failure_code', 'reached_time')) AS d, (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_broadcast_log' AND column_name IN ('frozen_title', 'frozen_sub', 'intent_id', 'attempts', 'update_time')) AS o, (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_broadcast_log' AND column_name IN ('delivery_state','message_id')) AS s, (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_broadcast' AND column_name IN ('audience_frozen','client_request_id','request_owner','request_hash')) AS b, (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'official_broadcast_creation') AS cr, (SELECT MAX(column_type) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_broadcast_log' AND column_name = 'delivery_state') AS st) AS z15
UNION ALL
SELECT 287 AS exec_order, 'orient_geo_ar_team_20261022' AS migration_id,
  CASE WHEN GREATEST(c0, c1, c2, c3, c4, c5, c6, c7, c8, c9) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1, c2, c3, c4, c5, c6, c7, c8, c9) = 4 THEN 'DATA_PREVIEW' WHEN GREATEST(c0, c1, c2, c3, c4, c5, c6, c7, c8, c9) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1, c2, c3, c4, c5, c6, c7, c8, c9) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1, c2, c3, c4, c5, c6, c7, c8, c9) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 orient_* x5 + sys_config 5 键' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 12 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 3231702679444751644
       AND (SELECT COUNT(*) = 3 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 16447331850938025924 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'orient_node_geo')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'orient_node_geo'), 3) AS c0,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 10 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 12196610784169876674
       AND (SELECT COUNT(*) = 4 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 6383704301572264919 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'orient_task_alternative')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'orient_task_alternative'), 3) AS c1,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 20 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 1648388884946529033
       AND (SELECT COUNT(*) = 10 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 10424365480879330477 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'orient_task_attempt')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'orient_task_attempt'), 3) AS c2,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 9 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 4793651276412379093
       AND (SELECT COUNT(*) = 9 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 15551048859549850042 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'orient_task_completion')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'orient_task_completion'), 3) AS c3,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 10 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 4778905448606490117
       AND (SELECT COUNT(*) = 1 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 14128333254474228543 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'orient_safety_rule')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'orient_safety_rule'), 3) AS c4,
    IFNULL((SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_config WHERE config_key = 'orient.geo.normalize.enabled'), 3) AS c5,
    IFNULL((SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_config WHERE config_key = 'orient.alt_task.enabled'), 3) AS c6,
    IFNULL((SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_config WHERE config_key = 'orient.team.board.enabled'), 3) AS c7,
    IFNULL((SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_config WHERE config_key = 'orient.team.completion_ratio'), 3) AS c8,
    IFNULL((SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_config WHERE config_key = 'orient.safety.enabled'), 3) AS c9) AS x17
UNION ALL
SELECT 288 AS exec_order, 'orient_score_recovery_20261026' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 orient_score_recovery' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 10 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 887350978483314695
       AND (SELECT COUNT(*) = 5 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 7890895288392942713 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'orient_score_recovery')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'orient_score_recovery'), 3) AS c0) AS n11
UNION ALL
SELECT 289 AS exec_order, 'orient_score_sync_receipt_20261026' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 orient_score_sync_receipt' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 6 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 419381447761362976
       AND (SELECT COUNT(*) = 2 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 7518263432094455407 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'orient_score_sync_receipt')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'orient_score_sync_receipt'), 3) AS c0) AS n12
UNION ALL
SELECT 320 AS exec_order, 'o14_reco_search_jobs_20261022' AS migration_id,
  CASE WHEN p0 = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 4 THEN 'DATA_PREVIEW' WHEN GREATEST(c0, c1) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'sys_job 2 行(暂停)' AS detail
FROM (SELECT
    IFNULL((SELECT IF(COUNT(*) = 1, 1, 3) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'recommendation_score_daily' AND engine = 'InnoDB'), 3) AS p0,
    IFNULL((SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_job WHERE invoke_target = 'recommendationScoreDailyTask.run()'), 3) AS c0,
    IFNULL((SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_job WHERE invoke_target = 'contentSearchProjectionTask.refresh()'), 3) AS c1) AS x18
UNION ALL
SELECT 334 AS exec_order, 'play_arrive_guard_log_20261022' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 play_arrive_guard_log' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 12 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 6759212771035083568
       AND (SELECT COUNT(*) = 3 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 15745635712475062580 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'play_arrive_guard_log')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'play_arrive_guard_log'), 3) AS c0) AS x19
UNION ALL
SELECT 343 AS exec_order, 'pms_sku_booking_20261022' AS migration_id,
  CASE WHEN GREATEST(c0, c1) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 4 THEN 'DATA_PREVIEW' WHEN GREATEST(c0, c1) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 pms_sku_booking_rule / pms_sku_capacity_slot' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 11 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 14842119991963441123
       AND (SELECT COUNT(*) = 4 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 9562436739962736901 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'pms_sku_booking_rule')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'pms_sku_booking_rule'), 3) AS c0,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 12 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 4804660983078453308
       AND (SELECT COUNT(*) = 5 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 6569897251089178890 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'pms_sku_capacity_slot')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'pms_sku_capacity_slot'), 3) AS c1) AS x20
UNION ALL
SELECT 365 AS exec_order, '20261023_advanced_command_fingerprint' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'play_attempt_log.request_fingerprint（可重入）' AS detail
FROM (SELECT
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(data_type = 'char' AND character_maximum_length = 64 AND character_set_name = 'ascii' AND collation_name = 'ascii_bin' AND is_nullable = 'YES' AND column_default IS NULL) = 1 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'play_attempt_log' AND column_name = 'request_fingerprint') AS c0) AS z16
UNION ALL
SELECT 366 AS exec_order, '20261029_unlist_test_topics' AS migration_id,
  CASE WHEN todo > 0 THEN 'DATA_PREVIEW' ELSE 'APPLIED' END AS status,
  '下架 5 个测试主题：DATA_PREVIEW = 仍有可软删的在架目标行；被守卫跳过的行不计入，见附录 C5' AS detail
FROM (SELECT (SELECT COUNT(*) FROM cms_topic t WHERE (t.id, t.name, t.member_id) IN ((990062, '预制人生 · 第一站', 100092), (8, '西安大唐不夜城灯光秀', 100000), (7, '上海沉浸式戏剧', 100000), (6, '成都宽窄巷子辣味食材寻味', 100000), (1, '上海滨江 10KM 骑行', 100086)) AND t.del_flag = 0 AND NOT (EXISTS (SELECT 1 FROM cms_registration r WHERE (r.topic_id = t.id OR (r.owner_type = 1 AND r.owner_id = t.id) OR (r.owner_type = 2 AND r.owner_id IN (SELECT a.id FROM cms_activity a WHERE a.topic_id = t.id))) AND (r.registration_status <> 3 OR r.payment_status IN (1, 2))) OR EXISTS (SELECT 1 FROM player_run_session s WHERE s.topic_id = t.id AND s.run_state <> 'ENDED') OR EXISTS (SELECT 1 FROM topic_route_session s WHERE s.topic_id = t.id AND s.status = 0))) AS todo) AS z28
UNION ALL
SELECT 367 AS exec_order, 'orient_scoring_20261022' AS migration_id,
  CASE WHEN p0 = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1, c2, c3, c4, c5, c6, c7, c8) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1, c2, c3, c4, c5, c6, c7, c8) = 4 THEN 'DATA_PREVIEW' WHEN GREATEST(c0, c1, c2, c3, c4, c5, c6, c7, c8) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1, c2, c3, c4, c5, c6, c7, c8) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1, c2, c3, c4, c5, c6, c7, c8) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 orient_score_* x4 + sys_config 5 键' AS detail
FROM (SELECT
    IFNULL((SELECT IF(COUNT(*) = 1, 1, 3) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'topic_route_session' AND engine = 'InnoDB'), 3) AS p0,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 17 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 17803569289124953085
       AND (SELECT COUNT(*) = 9 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 12522882686132926032 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'orient_score_ledger')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'orient_score_ledger'), 3) AS c0,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 15 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 15598439206091248842
       AND (SELECT COUNT(*) = 4 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 2267494275212618248 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'orient_score_settlement')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'orient_score_settlement'), 3) AS c1,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 15 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 3915102697218143002
       AND (SELECT COUNT(*) = 7 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 15676026130013828830 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'orient_score_appeal')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'orient_score_appeal'), 3) AS c2,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 13 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 7650742614159203308
       AND (SELECT COUNT(*) = 7 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 13138493092948280487 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'orient_result_dispute')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'orient_result_dispute'), 3) AS c3,
    IFNULL((SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_config WHERE config_key = 'orient.scoring.enabled'), 3) AS c4,
    IFNULL((SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_config WHERE config_key = 'orient.scoring.task_centi'), 3) AS c5,
    IFNULL((SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_config WHERE config_key = 'orient.scoring.points_rate_pct'), 3) AS c6,
    IFNULL((SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_config WHERE config_key = 'orient.scoring.xp_rate_pct'), 3) AS c7,
    IFNULL((SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_config WHERE config_key = 'orient.scoring.dispute_window_hours'), 3) AS c8) AS x21
UNION ALL
SELECT 368 AS exec_order, 'orient_session_lifecycle_20261022' AS migration_id,
  CASE WHEN p0 = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1, c2) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1, c2) = 4 THEN 'DATA_PREVIEW' WHEN GREATEST(c0, c1, c2) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1, c2) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1, c2) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'topic_route_session.phase + status 注释 + 新表 orient_session_clock' AS detail
FROM (SELECT
    IFNULL((SELECT IF(COUNT(*) = 1, 1, 3) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'topic_route_session' AND engine = 'InnoDB'), 3) AS p0,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'varchar(16)' AND is_nullable = 'YES' AND column_default IS NULL) = 1 THEN 1 ELSE 2 END
   FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'topic_route_session' AND column_name = 'phase'), 3) AS c0,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 3 WHEN SUM(column_type = 'tinyint' AND is_nullable = 'NO' AND column_default = '0') = 0 THEN 2
   WHEN SUM(column_comment = '0进行中 1已到终点 2已结束(含放弃) 3取消资格') = 1 THEN 1 ELSE 0 END
   FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'topic_route_session' AND column_name = 'status'), 3) AS c1,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 7 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 1915154857964799012
       AND (SELECT COUNT(*) = 3 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 13932434150092499771 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'orient_session_clock')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'orient_session_clock'), 3) AS c2) AS x22
UNION ALL
SELECT 369 AS exec_order, 'photo_check_intent_20261023' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 photo_check_intent（单次执行）' AS detail
FROM (SELECT
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_name IN ('id', 'member_id', 'session_id', 'action_key', 'request_fingerprint', 'context_json', 'image_reference', 'reservation', 'original_day', 'nonce', 'state', 'provider_state', 'reason_code', 'encoded_input_sha256', 'model_name', 'prompt_version', 'encoder_version', 'result_pass', 'result_confidence', 'result_reason', 'result_degraded', 'attempt_log_id', 'created_at', 'updated_at')) = 24 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'photo_check_intent') AS c0) AS z17
UNION ALL
SELECT 411 AS exec_order, 'member_visited_poi_poi_key_backfill_20261022' AS migration_id,
  CASE WHEN p0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '数据回填:poi_key 列就位后须跑附录 B 的只读预览' AS detail
FROM (SELECT
    IFNULL((SELECT IF(COUNT(*) = 1, 1, 3) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'roam_poi' AND engine = 'InnoDB'), 3) AS p0,
    IFNULL((SELECT IF(EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'member_visited_poi' AND column_name = 'poi_key'), 4, 0)), 3) AS c0) AS x23
UNION ALL
SELECT 422 AS exec_order, 'roam_share_version_20261022' AS migration_id,
  CASE WHEN p0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'roam_share_snapshot.version' AS detail
FROM (SELECT
    IFNULL((SELECT IF(COUNT(*) = 1, 1, 3) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'roam_share_snapshot' AND engine = 'InnoDB'), 3) AS p0,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'int' AND is_nullable = 'NO' AND column_default = '1') = 1 THEN 1 ELSE 2 END
   FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'roam_share_snapshot' AND column_name = 'version'), 3) AS c0) AS x24
UNION ALL
SELECT 427 AS exec_order, '20261024_official_roam_challenges' AS migration_id,
  CASE WHEN p0 = 3 THEN 'BLOCKED_PREREQ' WHEN p1 = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1, c2, c3, c4) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1, c2, c3, c4) = 4 THEN 'DATA_PREVIEW' WHEN GREATEST(c0, c1, c2, c3, c4) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1, c2, c3, c4) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1, c2, c3, c4) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'roam_poi 3 列 + roam_poi_reward_log 2 列' AS detail
FROM (SELECT
    IFNULL((SELECT IF(COUNT(*) = 1, 1, 3) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'roam_poi' AND engine = 'InnoDB'), 3) AS p0,
    IFNULL((SELECT IF(COUNT(*) = 1, 1, 3) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'roam_poi_reward_log' AND engine = 'InnoDB'), 3) AS p1,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'text' AND is_nullable = 'YES' AND column_default IS NULL) = 1 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'roam_poi' AND column_name = 'gallery_json') AS c0,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'tinyint' AND is_nullable = 'NO' AND column_default = '0') = 1 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'roam_poi' AND column_name = 'photo_required') AS c1,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'varchar(80)' AND is_nullable = 'YES' AND column_default IS NULL) = 1 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'roam_poi' AND column_name = 'badge_code') AS c2,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'varchar(2048)' AND is_nullable = 'YES' AND column_default IS NULL) = 1 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'roam_poi_reward_log' AND column_name = 'photo_url') AS c3,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'varchar(255)' AND is_nullable = 'YES' AND column_default IS NULL) = 1 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'roam_poi_reward_log' AND column_name = 'submitted_answer') AS c4) AS n22
UNION ALL
SELECT 428 AS exec_order, '20261025_official_poi_quotes' AS migration_id,
  CASE WHEN p0 = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 4 THEN 'DATA_PREVIEW' WHEN GREATEST(c0, c1) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'roam_poi.quotes_json / photo_credit' AS detail
FROM (SELECT
    IFNULL((SELECT IF(COUNT(*) = 1, 1, 3) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'roam_poi' AND engine = 'InnoDB'), 3) AS p0,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'text' AND is_nullable = 'YES' AND column_default IS NULL) = 1 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'roam_poi' AND column_name = 'quotes_json') AS c0,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'text' AND is_nullable = 'YES' AND column_default IS NULL) = 1 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'roam_poi' AND column_name = 'photo_credit') AS c1) AS n23
UNION ALL
SELECT 437 AS exec_order, '20261023_g_cash_evidence_expand' AS migration_id,
  CASE WHEN GREATEST(c0, c1, c2, c3, c4, c5, c6, c7, c8, c9) = 2 THEN 'CONFLICT' WHEN LEAST(c0, c1, c2, c3, c4, c5, c6, c7, c8, c9) = 1 THEN 'APPLIED'
       WHEN GREATEST(c0, c1, c2, c3, c4, c5, c6, c7, c8, c9) = 0 AND dirty = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1, c2, c3, c4, c5, c6, c7, c8, c9) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '合作结算现金证据：3 新表 + coop_settlement 5 列 + adjustment 2 列（单次执行）；BLOCKED_PREREQ = 存量结算行有平台/空受款人/悬空 claim，迁移前置断言会拒绝' AS detail
FROM (SELECT
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_name IN ('id', 'topic_id', 'commercial_context_type', 'commercial_context_id', 'close_kind', 'initiator_member_id', 'original_sales_basis', 'original_contribution_count', 'platform_rate', 'guarantee_base_rate', 'guarantee_step_rate', 'guarantee_max_merchant', 'original_verified_merchant_count', 'origin_calculation_version', 'contract_snapshot', 'source_fingerprint', 'closed_at', 'created_at')) = 18 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'coop_settlement_cash_close') AS c0,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_name IN ('id', 'cash_close_id', 'registration_id', 'payer_registration_id', 'parent_order_no', 'registration_no', 'payer_order_no', 'owner_type', 'owner_id', 'source_topic_id', 'snapshot_payment_status', 'snapshot_registration_status', 'snapshot_verification_status', 'snapshot_payable_amount', 'sales_basis_amount', 'registration_snapshot', 'source_fingerprint', 'created_at')) = 18 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'coop_settlement_cash_contribution') AS c1,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_name IN ('id', 'cash_close_id', 'event_type', 'source_id', 'source_registration_id', 'source_amount', 'source_snapshot', 'source_fingerprint', 'created_at')) = 9 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'coop_settlement_cash_event') AS c2,
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'coop_settlement' AND column_name = 'cash_head_kind') AS c3,
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'coop_settlement' AND column_name = 'cash_close_id') AS c4,
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'coop_settlement' AND column_name = 'cash_member_id') AS c5,
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'coop_settlement' AND column_name = 'cash_initiator_member_id') AS c6,
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'coop_settlement' AND column_name = 'cash_global_line_type') AS c7,
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'coop_settlement_adjustment' AND column_name = 'cash_close_id') AS c8,
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'coop_settlement_adjustment' AND column_name = 'cash_event_id') AS c9,
    (SELECT IF((SELECT COUNT(*) FROM coop_settlement h LEFT JOIN ums_member m ON m.id = h.merchant_id WHERE h.merchant_id IS NULL OR h.merchant_id <= 0 OR m.id IS NULL OR h.payee_type = 'platform' OR h.line_type IN ('PLATFORM','DEPOSIT_DEDUCTION')) + (SELECT COUNT(*) FROM coop_settlement_adjustment a LEFT JOIN coop_settlement h ON h.id = a.settlement_id WHERE a.claim_id IS NULL OR a.claim_id <= 0 OR h.id IS NULL) = 0, 0, 3)) AS dirty) AS z18
UNION ALL
SELECT 463 AS exec_order, '20261022_theme_state_key_attribute_def' AS migration_id,
  CASE WHEN p0 = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1, c2, c3, c4, c5, c6, c7, c8) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1, c2, c3, c4, c5, c6, c7, c8) = 4 THEN 'DATA_PREVIEW' WHEN GREATEST(c0, c1, c2, c3, c4, c5, c6, c7, c8) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1, c2, c3, c4, c5, c6, c7, c8) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1, c2, c3, c4, c5, c6, c7, c8) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'theme_state_key 9 列' AS detail
FROM (SELECT
    IFNULL((SELECT IF(COUNT(*) = 1, 1, 3) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'theme_state_key' AND engine = 'InnoDB'), 3) AS p0,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'int' AND is_nullable = 'YES' AND column_default IS NULL) = 1 THEN 1 ELSE 2 END
   FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'theme_state_key' AND column_name = 'min_value'), 3) AS c0,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'int' AND is_nullable = 'YES' AND column_default IS NULL) = 1 THEN 1 ELSE 2 END
   FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'theme_state_key' AND column_name = 'max_value'), 3) AS c1,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'varchar(16)' AND is_nullable = 'YES' AND column_default IS NULL) = 1 THEN 1 ELSE 2 END
   FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'theme_state_key' AND column_name = 'display_type'), 3) AS c2,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'varchar(16)' AND is_nullable = 'YES' AND column_default IS NULL) = 1 THEN 1 ELSE 2 END
   FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'theme_state_key' AND column_name = 'color'), 3) AS c3,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'varchar(32)' AND is_nullable = 'YES' AND column_default IS NULL) = 1 THEN 1 ELSE 2 END
   FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'theme_state_key' AND column_name = 'icon'), 3) AS c4,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'int' AND is_nullable = 'YES' AND column_default IS NULL) = 1 THEN 1 ELSE 2 END
   FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'theme_state_key' AND column_name = 'sort_order'), 3) AS c5,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'tinyint(1)' AND is_nullable = 'YES' AND column_default IS NULL) = 1 THEN 1 ELSE 2 END
   FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'theme_state_key' AND column_name = 'visible'), 3) AS c6,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'varchar(16)' AND is_nullable = 'YES' AND column_default IS NULL) = 1 THEN 1 ELSE 2 END
   FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'theme_state_key' AND column_name = 'persist_scope'), 3) AS c7,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'varchar(1024)' AND is_nullable = 'YES' AND column_default IS NULL) = 1 THEN 1 ELSE 2 END
   FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'theme_state_key' AND column_name = 'rules_json'), 3) AS c8) AS x25
UNION ALL
SELECT 464 AS exec_order, '20261022_theme_character' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 theme_character' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 8 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 852904077189281626
       AND (SELECT COUNT(*) = 3 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 44189788068057420 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'theme_character')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'theme_character'), 3) AS c0) AS x26
UNION ALL
SELECT 477 AS exec_order, '20261028_d20_attribute_registry' AS migration_id,
  CASE WHEN p0 = 3 THEN 'BLOCKED_PREREQ' WHEN p1 = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 4 THEN 'DATA_PREVIEW' WHEN GREATEST(c0, c1) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'theme_state_key.is_attribute / attribute_preset；同文件回填为 ON DUPLICATE KEY UPDATE，可重跑' AS detail
FROM (SELECT
    IFNULL((SELECT IF(COUNT(*) = 1, 1, 3) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'theme_state_key' AND engine = 'InnoDB'), 3) AS p0,
    IFNULL((SELECT IF(COUNT(*) = 1, 1, 3) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'cms_topic' AND engine = 'InnoDB'), 3) AS p1,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'tinyint(1)' AND is_nullable = 'NO' AND column_default = '0') = 1 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'theme_state_key' AND column_name = 'is_attribute') AS c0,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'varchar(16)' AND is_nullable = 'YES' AND column_default IS NULL) = 1 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'theme_state_key' AND column_name = 'attribute_preset') AS c1) AS n24
UNION ALL
SELECT 489 AS exec_order, '20261028_remove_wildlife_theme' AS migration_id,
  CASE WHEN c0 > 1 THEN 'CONFLICT' WHEN c0 = 1 THEN 'DATA_PREVIEW' ELSE 'APPLIED' END AS status,
  '在架目标行数：1=须先跑 _precheck_remove_wildlife_theme_20261028.sql（引用计数全 0）；0=无事可做' AS detail
FROM (SELECT (SELECT COUNT(*) FROM cms_topic WHERE id BETWEEN 990052 AND 990056 AND name = '野生邻居观察局' AND del_flag = 0) AS c0) AS n30
UNION ALL
SELECT 501 AS exec_order, '20261028_template_shelf_list_free_explore' AS migration_id,
  CASE WHEN c1 > 0 THEN 'CONFLICT' WHEN c0 > 0 THEN 'DATA_PREVIEW' ELSE 'APPLIED' END AS status,
  '待上架模板数（>0 先核对 6 个名称各恰好 1 行且 is_template=1）；同名多行判 CONFLICT' AS detail
FROM (SELECT
    (SELECT COUNT(*) FROM cms_topic WHERE del_flag = 0 AND is_template = 1 AND status = 1 AND user_status = 0
      AND name IN ('风味巡游', '不惑之年', '预制人生', '高温末日逃生', '上海像一部旧电影', '生活不掉线')) AS c0,
    (SELECT COUNT(*) FROM (SELECT name FROM cms_topic WHERE del_flag = 0 AND name IN ('风味巡游', '不惑之年', '预制人生', '高温末日逃生', '上海像一部旧电影', '生活不掉线')
      GROUP BY name HAVING COUNT(*) > 1) d) AS c1) AS n31
UNION ALL
SELECT 520 AS exec_order, 'verification_review_evidence_20261022' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 verification_review_evidence' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 8 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 14781193881360100961
       AND (SELECT COUNT(*) = 5 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 17353113222738040501 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'verification_review_evidence')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'verification_review_evidence'), 3) AS c0) AS x27
UNION ALL
SELECT 523 AS exec_order, '20261025_coop_order_station_fulfillment' AS migration_id,
  CASE WHEN GREATEST(c0, c1, c2, c3) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1, c2, c3) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1, c2, c3) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1, c2, c3) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 coop_order_station_fulfillment + coop_invite.supply_station_id + 2 触发器（单次执行）' AS detail
FROM (SELECT
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_name IN ('id', 'order_id', 'topic_id', 'beneficiary_member_id', 'registration_id', 'station_id', 'verification_audit_id', 'sales_basis_amount', 'order_terms_sha256', 'source_fingerprint', 'fulfilled_at', 'recorded_at')) = 12 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'coop_order_station_fulfillment') AS c0,
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'coop_invite' AND column_name = 'supply_station_id') AS c1,
    (SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema = DATABASE() AND trigger_name = 'tr_corsf_immutable_update') AS c2,
    (SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema = DATABASE() AND trigger_name = 'tr_corsf_immutable_delete') AS c3) AS z19
UNION ALL
SELECT 524 AS exec_order, 'w8_refund_fulfillment_recovery_20261026' AS migration_id,
  CASE WHEN p0 = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 4 THEN 'DATA_PREVIEW' WHEN GREATEST(c0, c1) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'refund_application.fulfillment_sync_queue_at + 恢复队列索引' AS detail
FROM (SELECT
    IFNULL((SELECT IF(COUNT(*) = 1, 1, 3) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'refund_application' AND engine = 'InnoDB'), 3) AS p0,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'datetime(6)' AND is_nullable = 'NO' AND column_default = 'CURRENT_TIMESTAMP(6)') = 1 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'refund_application' AND column_name = 'fulfillment_sync_queue_at') AS c0,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 4 AND SUM(non_unique = 1 AND ((seq_in_index = 1 AND column_name = 'source_type') OR (seq_in_index = 2 AND column_name = 'del_flag') OR (seq_in_index = 3 AND column_name = 'fulfillment_sync_queue_at') OR (seq_in_index = 4 AND column_name = 'id'))) = 4 THEN 1 ELSE 2 END FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'refund_application' AND index_name = 'idx_refund_fulfillment_recovery') AS c1) AS n25
UNION ALL
SELECT 529 AS exec_order, '20261026_user_invoice_menu' AS migration_id,
  CASE WHEN p0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 2 THEN 'CONFLICT' WHEN c0 = 1 THEN 'APPLIED'
       WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'sys_menu 用户发票处理 C 菜单 + 4 个 F 按钮；父菜单 funds-aftercare 须恰好 1 行；PARTIAL 可重跑（逐行 NOT EXISTS + 形状守卫）' AS detail
FROM (SELECT
    (SELECT IF(COUNT(*) = 1, 1, 3) FROM sys_menu WHERE menu_type = 'M' AND path = 'funds-aftercare') AS p0,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) > COUNT(DISTINCT perms) OR SUM(NOT ((perms = 'business:userInvoice:list' AND menu_type <=> 'C' AND component <=> 'business/userInvoice/index' AND route_name <=> 'UserInvoiceProcessing' AND path <=> 'userInvoice' AND parent_id <=> (SELECT MIN(menu_id) FROM sys_menu WHERE menu_type = 'M' AND path = 'funds-aftercare')) OR (perms <> 'business:userInvoice:list' AND menu_type <=> 'F' AND parent_id <=> (SELECT MIN(menu_id) FROM sys_menu WHERE perms = 'business:userInvoice:list')))) > 0 THEN 2 WHEN COUNT(*) = 5 THEN 1 ELSE 5 END FROM sys_menu WHERE perms IN ('business:userInvoice:list', 'business:userInvoice:query', 'business:userInvoice:taxView', 'business:userInvoice:process', 'business:userInvoice:reconcile')) AS c0) AS n26
UNION ALL
SELECT 544 AS exec_order, '20261013_admin_role_page_grants' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'APPLIED = 4 个后台角色均已有页面授权(本迁移 no-op);NEEDED = 有角色无页面授权,禁止执行,交 owner(见 pack D-PAYEE 节);checksum 对账见附录 A' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(DISTINCT r.role_key) < 4 THEN 3
   WHEN SUM(EXISTS (SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id = rm.menu_id
     WHERE rm.role_id = r.role_id AND m.menu_type = 'C')) = COUNT(*) THEN 1 ELSE 0 END
   FROM sys_role r WHERE r.del_flag = '0' AND r.role_key IN ('cy_operator', 'cy_auditor', 'cy_support', 'cy_finance')), 3) AS c0) AS x28
UNION ALL
SELECT 545 AS exec_order, '20261016_admin_role_payee_separation_forward' AS migration_id,
  CASE WHEN GREATEST(p0, p1, p2, p3, p4, p5, p6, p7) = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'D-PAYEE:operator 有 payout 无 verify、finance 有 verify 无 payout' AS detail
FROM (SELECT
    IFNULL((SELECT IF(COUNT(*) = 1, 1, 3) FROM sys_role WHERE del_flag = '0' AND role_key = 'cy_operator'), 3) AS p0,
    IFNULL((SELECT IF(COUNT(*) = 1, 1, 3) FROM sys_role WHERE del_flag = '0' AND role_key = 'cy_auditor'), 3) AS p1,
    IFNULL((SELECT IF(COUNT(*) = 1, 1, 3) FROM sys_role WHERE del_flag = '0' AND role_key = 'cy_support'), 3) AS p2,
    IFNULL((SELECT IF(COUNT(*) = 1, 1, 3) FROM sys_role WHERE del_flag = '0' AND role_key = 'cy_finance'), 3) AS p3,
    IFNULL((SELECT IF(COUNT(*) = 1 AND SUM(menu_type = 'F' AND status = '0' AND visible = '0') = 1, 1, 3) FROM sys_menu WHERE perms = 'business:clubpayee:verify'), 3) AS p4,
    IFNULL((SELECT IF(COUNT(*) = 1 AND SUM(menu_type = 'F' AND status = '0' AND visible = '0') = 1, 1, 3) FROM sys_menu WHERE perms = 'business:clubpayee:payout'), 3) AS p5,
    IFNULL((SELECT IF(COUNT(*) = 1 AND SUM(menu_type = 'F' AND status = '0' AND visible = '0') = 1, 1, 3) FROM sys_menu WHERE perms = 'business:clubpayable:confirm'), 3) AS p6,
    IFNULL((SELECT IF(COUNT(*) = 1 AND SUM(menu_type = 'F' AND status = '0' AND visible = '0') = 1, 1, 3) FROM sys_menu WHERE perms = 'business:clubhours:correct'), 3) AS p7,
    IFNULL((SELECT IF((SELECT COUNT(*) FROM sys_role_menu rm JOIN sys_role r ON r.role_id = rm.role_id JOIN sys_menu m ON m.menu_id = rm.menu_id
     WHERE r.del_flag = '0' AND r.role_key = 'cy_operator' AND m.perms = 'business:clubpayee:payout') >= 1 AND (SELECT COUNT(*) FROM sys_role_menu rm JOIN sys_role r ON r.role_id = rm.role_id JOIN sys_menu m ON m.menu_id = rm.menu_id
     WHERE r.del_flag = '0' AND r.role_key = 'cy_finance' AND m.perms = 'business:clubpayee:verify') >= 1
   AND (SELECT COUNT(*) FROM sys_role_menu rm JOIN sys_role r ON r.role_id = rm.role_id JOIN sys_menu m ON m.menu_id = rm.menu_id
     WHERE r.del_flag = '0' AND r.role_key = 'cy_operator' AND m.perms = 'business:clubpayee:verify') = 0 AND (SELECT COUNT(*) FROM sys_role_menu rm JOIN sys_role r ON r.role_id = rm.role_id JOIN sys_menu m ON m.menu_id = rm.menu_id
     WHERE r.del_flag = '0' AND r.role_key = 'cy_finance' AND m.perms = 'business:clubpayee:payout') = 0, 1, 0)), 3) AS c0) AS x29
UNION ALL
SELECT 546 AS exec_order, '20261027_orient_scoring_menu_grants' AS migration_id,
  CASE WHEN p0 = 3 OR p1 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 2 OR c1 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 AND c1 = 1 THEN 'APPLIED' WHEN c0 = 0 AND c1 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '官方主题 C 菜单下 2 个 F 按钮，仅授 cy_operator；PARTIAL 可重跑（逐行 NOT EXISTS + 事务守卫）' AS detail
FROM (SELECT
    (SELECT IF(COUNT(*) = 1, 1, 3) FROM sys_menu WHERE menu_type = 'C' AND component = 'business/topic/index' AND path = 'official-topic') AS p0,
    (SELECT IF(COUNT(*) >= 1, 1, 3) FROM sys_role WHERE role_key = 'cy_operator' AND del_flag = '0') AS p1,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) > COUNT(DISTINCT perms) OR SUM(menu_type <> 'F') > 0 THEN 2
            WHEN COUNT(*) = 2 AND SUM(parent_id <=> (SELECT MIN(menu_id) FROM sys_menu WHERE menu_type = 'C' AND component = 'business/topic/index' AND path = 'official-topic')) = 2 THEN 1 ELSE 5 END
       FROM sys_menu WHERE perms IN ('business:orientScoring:list', 'business:orientScoring:review')) AS c0,
    (SELECT CASE WHEN EXISTS (SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id = rm.menu_id AND m.perms IN ('business:orientScoring:list', 'business:orientScoring:review')
                  JOIN sys_role r ON r.role_id = rm.role_id WHERE r.role_key <> 'cy_operator' OR r.del_flag <> '0') THEN 2
            WHEN NOT EXISTS (SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id = rm.menu_id AND m.perms IN ('business:orientScoring:list', 'business:orientScoring:review')) THEN 0
            WHEN NOT EXISTS (SELECT 1 FROM sys_role r JOIN sys_menu m ON m.menu_type = 'F' AND m.perms IN ('business:orientScoring:list', 'business:orientScoring:review')
                  WHERE r.role_key = 'cy_operator' AND r.del_flag = '0' AND NOT EXISTS (SELECT 1 FROM sys_role_menu x
                  WHERE x.role_id = r.role_id AND x.menu_id = m.menu_id)) THEN 1 ELSE 5 END) AS c1) AS n27
UNION ALL
SELECT 553 AS exec_order, '20261023_g_wp08_delivery_jobs_paused' AS migration_id,
  CASE WHEN GREATEST(c0, c1) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'sys_job 两个暂停的补偿任务（20261013 种子已登记时为 APPLIED）' AS detail
FROM (SELECT
    (SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_job WHERE CAST(TRIM(invoke_target) AS BINARY) = CAST('imNotificationDeliveryTask.deliverPending()' AS BINARY)) AS c0,
    (SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_job WHERE CAST(TRIM(invoke_target) AS BINARY) = CAST('roamRewardDeliveryTask.run()' AS BINARY)) AS c1) AS z20
UNION ALL
SELECT 555 AS exec_order, 'club_post_media_review_budget_20261024' AS migration_id,
  CASE WHEN t <> 1 THEN 'BLOCKED_PREREQ' WHEN s IS NULL THEN 'CONFLICT'
       WHEN s LIKE '%orstate=5or%|%orstate=5or%' THEN 'APPLIED' WHEN s LIKE '%if%|%if%' THEN 'NEEDED' ELSE 'CONFLICT' END AS status,
  '帖子媒体审核外发 CHECK 允许 state=5（可重入）' AS detail
FROM (SELECT (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'club_post_media_review_outbox' AND engine = 'InnoDB') AS t, (SELECT GROUP_CONCAT(REGEXP_REPLACE(LOWER(cc.check_clause), '[[:space:]`()]', '') ORDER BY cc.constraint_name SEPARATOR '|') FROM information_schema.check_constraints cc JOIN information_schema.table_constraints tc ON tc.constraint_schema = cc.constraint_schema AND tc.constraint_name = cc.constraint_name WHERE cc.constraint_schema = DATABASE() AND tc.table_name = 'club_post_media_review_outbox' AND cc.constraint_name IN ('chk_club_post_media_review_owner','chk_club_post_media_review_trace')) AS s) AS z21
UNION ALL
SELECT 559 AS exec_order, '20261024_wx_verified_payment_fact' AS migration_id,
  CASE WHEN GREATEST(c0, c1, c2) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1, c2) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1, c2) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1, c2) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 wx_verified_payment_fact / _rejection / wx_payment_callback_fact_link（单次执行）' AS detail
FROM (SELECT
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_name IN ('id', 'fact_kind', 'mchid', 'appid', 'business_out_trade_no', 'wx_out_trade_no', 'merchant_event_no', 'provider_id', 'transaction_id', 'trade_type', 'currency', 'total_fen', 'amount_fen', 'success_at', 'intent_request_hash', 'fact_hash', 'verification_version', 'verification_outcome', 'verification_method', 'original_payment_state', 'created_at')) = 21 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'wx_verified_payment_fact') AS c0,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_name IN ('id', 'rejection_key', 'rejection_reason', 'original_fact_id', 'candidate_fact_hash', 'notify_hash', 'candidate_facts', 'created_at')) = 8 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'wx_verified_payment_fact_rejection') AS c1,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_name IN ('inbox_id', 'notify_hash', 'fact_id', 'fact_hash', 'created_at')) = 5 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'wx_payment_callback_fact_link') AS c2) AS z22
UNION ALL
SELECT 560 AS exec_order, '20261024_clm_commerce_payment_proof' AS migration_id,
  CASE WHEN GREATEST(c0, c1, c2, c3) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1, c2, c3) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1, c2, c3) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1, c2, c3) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '会员发票 / 商城订单支付证明 2+2 列（单次执行）' AS detail
FROM (SELECT
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'club_membership_invoice' AND column_name = 'verified_payment_fact_id') AS c0,
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'club_membership_invoice' AND column_name = 'verified_payment_fact_hash') AS c1,
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'oms_order' AND column_name = 'verified_payment_fact_id') AS c2,
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'oms_order' AND column_name = 'verified_payment_fact_hash') AS c3) AS z23
UNION ALL
SELECT 561 AS exec_order, '20261024_coop_original_refund_components' AS migration_id,
  CASE WHEN GREATEST(c0, c1, c2) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1, c2) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1, c2) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1, c2) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 coop_settlement_original_component / refund_receipt / original_adjustment（单次执行）' AS detail
FROM (SELECT
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_name IN ('id', 'cash_close_id', 'topic_id', 'source_registration_id', 'payer_registration_id', 'settlement_id', 'source_claim_id', 'payment_fact_id', 'payment_fact_hash', 'receiver_kind', 'receiver_member_id', 'line_type', 'original_amount', 'original_order_cash', 'calculation_version', 'source_fingerprint', 'created_at')) = 17 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'coop_settlement_original_component') AS c0,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_name IN ('id', 'cash_close_id', 'topic_id', 'refund_id', 'verified_fact_id', 'verified_fact_kind', 'verified_fact_hash', 'wx_fact_id', 'source_registration_id', 'payer_registration_id', 'currency', 'refund_amount', 'total_amount', 'cumulative_before', 'cumulative_after', 'reversed_amount', 'calculation_version', 'source_fingerprint', 'created_at')) = 19 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'coop_settlement_refund_receipt') AS c1,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_name IN ('id', 'cash_close_id', 'component_id', 'receipt_id', 'settlement_id', 'payer_registration_id', 'amount', 'head_was_paid', 'wallet_debit', 'debt_amount', 'created_at')) = 11 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'coop_settlement_original_adjustment') AS c2) AS z24
UNION ALL
SELECT 562 AS exec_order, '20261024_coop_negative_margin_manual' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 coop_settlement_manual_case（单次执行）' AS detail
FROM (SELECT
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_name IN ('id', 'case_key', 'topic_id', 'reason', 'negative_amount', 'source_snapshot', 'state', 'created_at')) = 8 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'coop_settlement_manual_case') AS c0) AS z25
UNION ALL
SELECT 563 AS exec_order, '20261024_registration_payment_fact' AS migration_id,
  CASE WHEN GREATEST(c0, c1, c2) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1, c2) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1, c2) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1, c2) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 cms_registration_payment_fact + 两个只追加触发器（单次执行）' AS detail
FROM (SELECT
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_name IN ('registration_id', 'registration_no', 'payment_fact_id', 'payment_fact_hash', 'transaction_id', 'consumed_at')) = 6 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cms_registration_payment_fact') AS c0,
    (SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema = DATABASE() AND trigger_name = 'registration_paid_fact_no_update') AS c1,
    (SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema = DATABASE() AND trigger_name = 'registration_paid_fact_no_delete') AS c2) AS z26
UNION ALL
SELECT 564 AS exec_order, '20261024_wx_success_fact_business_index' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'wx_verified_payment_fact 业务单号索引（单次执行）' AS detail
FROM (SELECT
    (SELECT IF(COUNT(*) > 0, 1, 0) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'wx_verified_payment_fact' AND index_name = 'ix_wx_fact_business') AS c0) AS z27
ORDER BY exec_order;

-- [3/7] D-PAYEE（20261016）前置数据与当前授权矩阵
-- 3a 四个后台角色：每个 role_key 必须恰好 1 行 del_flag='0'，否则迁移的 guard 让整个事务回滚
SELECT 'role' AS kind, k.role_key AS item,
       (SELECT COUNT(*) FROM sys_role r WHERE r.role_key = k.role_key AND r.del_flag = '0') AS active_rows,
       (SELECT GROUP_CONCAT(r.role_id ORDER BY r.role_id) FROM sys_role r WHERE r.role_key = k.role_key AND r.del_flag = '0') AS ids,
       (SELECT COUNT(*) FROM sys_role_menu rm JOIN sys_role r ON r.role_id = rm.role_id JOIN sys_menu m ON m.menu_id = rm.menu_id
         WHERE r.role_key = k.role_key AND r.del_flag = '0' AND m.menu_type = 'C') AS page_grants
FROM (SELECT 'cy_operator' AS role_key UNION ALL SELECT 'cy_auditor' UNION ALL SELECT 'cy_support' UNION ALL SELECT 'cy_finance') k
UNION ALL
-- 3b 四个按钮菜单：每个 perms 必须恰好 1 行，且 menu_type='F'、status='0'、visible='0'
SELECT 'menu', p.perms,
       (SELECT COUNT(*) FROM sys_menu m WHERE m.perms = p.perms),
       (SELECT GROUP_CONCAT(m.menu_id ORDER BY m.menu_id) FROM sys_menu m WHERE m.perms = p.perms),
       (SELECT COUNT(*) FROM sys_menu m WHERE m.perms = p.perms AND m.menu_type = 'F' AND m.status = '0' AND m.visible = '0')
FROM (SELECT 'business:clubpayee:verify' AS perms UNION ALL SELECT 'business:clubpayee:payout'
      UNION ALL SELECT 'business:clubpayable:confirm' UNION ALL SELECT 'business:clubhours:correct') p
UNION ALL
-- 3c sys_user 行数：>0 时迁移不走「全新安装批量初始化」分支，只改两对授权
SELECT 'sys_user', 'rows', COUNT(*), NULL, NULL FROM sys_user;

-- 3d 当前授权矩阵（4 角色 × 4 按钮）。迁移执行后的目标：
--    cy_operator: payout=1 verify=0；cy_finance: verify=1 payout=0；其余格子保持原样
--    （cy_operator 已有的 payout、cy_finance 已有的 confirm/correct/verify 会被原样保留）
SELECT r.role_key,
       SUM(m.perms = 'business:clubpayee:verify') AS has_verify,
       SUM(m.perms = 'business:clubpayee:payout') AS has_payout,
       SUM(m.perms = 'business:clubpayable:confirm') AS has_clubpayable_confirm,
       SUM(m.perms = 'business:clubhours:correct') AS has_clubhours_correct
FROM sys_role r
LEFT JOIN sys_role_menu rm ON rm.role_id = r.role_id
LEFT JOIN sys_menu m ON m.menu_id = rm.menu_id
  AND m.perms IN ('business:clubpayee:verify', 'business:clubpayee:payout',
                  'business:clubpayable:confirm', 'business:clubhours:correct')
WHERE r.del_flag = '0' AND r.role_key IN ('cy_operator', 'cy_auditor', 'cy_support', 'cy_finance')
GROUP BY r.role_key
ORDER BY r.role_key;

-- 3e 迁移会删除的两条授权（预期删除行数；执行后应为 0）
SELECT 'will_revoke' AS action, r.role_key, m.perms, r.role_id, m.menu_id
FROM sys_role_menu rm JOIN sys_role r ON r.role_id = rm.role_id JOIN sys_menu m ON m.menu_id = rm.menu_id
WHERE r.del_flag = '0' AND ((r.role_key = 'cy_operator' AND m.perms = 'business:clubpayee:verify')
                         OR (r.role_key = 'cy_finance' AND m.perms = 'business:clubpayee:payout'))
ORDER BY r.role_key;

-- 3f 持有 business:clubpayee:verify 或 payout 的后台账号（职责分离人工复核用；不输出手机号/邮箱）
SELECT u.user_id, u.user_name, r.role_key, m.perms
FROM sys_user u JOIN sys_user_role ur ON ur.user_id = u.user_id
JOIN sys_role r ON r.role_id = ur.role_id AND r.del_flag = '0'
JOIN sys_role_menu rm ON rm.role_id = r.role_id
JOIN sys_menu m ON m.menu_id = rm.menu_id AND m.perms IN ('business:clubpayee:verify', 'business:clubpayee:payout')
WHERE u.del_flag = '0'
ORDER BY u.user_id, m.perms;

-- [4/7] 种子键与定时任务的当前值（迁移只在缺失时插入，不覆盖已配置值）
SELECT config_key, config_value, COUNT(*) OVER (PARTITION BY config_key) AS rows_for_key
FROM sys_config
WHERE config_key IN ('audit.activity.pre_review_all', 'audit.activity.high_risk_categories',
                     'play.arrive.location_guard.mode', 'play.arrive.require_accuracy',
                     'roam.visited_poi.stable_identity', 'merchant.desk.f4.enabled',
                     'points.deduct.rate_yuan_per_point', 'points.deduct.max_yuan_per_order',
                     'feature.flag.communityPostRead', 'feature.flag.communityPostWrite',
                     'feature.flag.communityPostPilotMembers')
   OR config_key LIKE 'orient.%'
ORDER BY config_key;

SELECT job_id, job_name, invoke_target, cron_expression, status
FROM sys_job
WHERE invoke_target IN ('recommendationScoreDailyTask.run()', 'contentSearchProjectionTask.refresh()',
                        'committedOfficialNoticeTask.deliverPending()', 'imNotificationDeliveryTask.deliverPending()',
                        'roamRewardDeliveryTask.run()')
ORDER BY invoke_target, job_id;

-- [5/7] 依赖前置（基线之前的迁移；任一为 0 都要先查清楚再执行对应发布迁移）
SELECT 'member_visited_poi (member_visited_poi)' AS prerequisite, COUNT(*) AS present FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'member_visited_poi'
UNION ALL SELECT 'roam (roam_poi)', COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'roam_poi'
UNION ALL SELECT '20260917_roam_share_snapshot (roam_share_snapshot)', COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'roam_share_snapshot'
UNION ALL SELECT '20260823_topic_route_graph (topic_route_session)', COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'topic_route_session'
UNION ALL SELECT 'theme_state_key_20260916 (theme_state_key)', COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'theme_state_key'
UNION ALL SELECT 'p3_platform_foundation (recommendation_score_daily)', COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'recommendation_score_daily'
UNION ALL SELECT 'base schema (cms_topic, cms_activity, club, sys_job)', COUNT(*) = 4 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('cms_topic', 'cms_activity', 'club', 'sys_job')
UNION ALL SELECT '20260816/20260817 tandianri batch3 funds (4 payee/payable/hours buttons)', COUNT(DISTINCT perms) = 4 FROM sys_menu WHERE perms IN ('business:clubpayee:verify', 'business:clubpayee:payout', 'business:clubpayable:confirm', 'business:clubhours:correct')
UNION ALL SELECT 'web_admin_roles_20260719 (4 cy_* roles)', COUNT(DISTINCT role_key) = 4 FROM sys_role WHERE del_flag = '0' AND role_key IN ('cy_operator', 'cy_auditor', 'cy_support', 'cy_finance')
UNION ALL SELECT '20261012_missing_business_menu (business C pages)', COUNT(*) > 0 FROM sys_menu WHERE menu_type = 'C' AND perms LIKE 'business:%'
UNION ALL SELECT '20261012_media_review_menu (business:audit:list)', COUNT(*) > 0 FROM sys_menu WHERE perms = 'business:audit:list'
UNION ALL SELECT 'role2_analytics_perm_20260918 (business:analytics:list)', COUNT(*) > 0 FROM sys_menu WHERE perms = 'business:analytics:list'
UNION ALL SELECT 'official_broadcast (official_broadcast, official_broadcast_log)', COUNT(*) = 2 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('official_broadcast', 'official_broadcast_log')
UNION ALL SELECT '20261012_roam_claim_fingerprints / roam (roam_poi_reward_log)', COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'roam_poi_reward_log'
UNION ALL SELECT 'p3_phase_c_web_rules_refund (refund_application)', COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'refund_application'
UNION ALL SELECT 'merchant_decor_20260708 (mms_merchant.gallery)', COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'mms_merchant' AND column_name = 'gallery'
UNION ALL SELECT '20261012_missing_business_menu (funds-aftercare M 菜单恰好 1 行)', COUNT(*) = 1 FROM sys_menu WHERE menu_type = 'M' AND path = 'funds-aftercare'
UNION ALL SELECT 'topic_source_menu_20260722 (official-topic C 菜单恰好 1 行)', COUNT(*) = 1 FROM sys_menu WHERE menu_type = 'C' AND component = 'business/topic/index' AND path = 'official-topic'
UNION ALL SELECT 'topic_journey_rules_20260916 (cms_topic.journey_rules_json)', COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cms_topic' AND column_name = 'journey_rules_json'
UNION ALL SELECT '20261013_b_im_notification_intent (im_notification_intent)', COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'im_notification_intent'
UNION ALL SELECT 'member_cash_withdraw (member_cash_withdraw)', COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'member_cash_withdraw'
UNION ALL SELECT 'wx_payment_callback_inbox_outbox_20260725 (wx_payment_callback_inbox)', COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'wx_payment_callback_inbox'
UNION ALL SELECT '20261011_npc_voice_version (npc_profile.voice_version)', COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'npc_profile' AND column_name = 'voice_version'
UNION ALL SELECT 'chapter_recruitment_20260722 (merchant_chapter_application)', COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'merchant_chapter_application'
UNION ALL SELECT '20260820_new_city_preference_chain (play_attempt_log)', COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'play_attempt_log'
UNION ALL SELECT 'club_role_media_review_outbox_20260824 (club_post_media_review_outbox)', COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'club_post_media_review_outbox'
UNION ALL SELECT 'coop_settlement / coop_settlement_line (coop_settlement, coop_settlement_adjustment)', COUNT(*) = 2 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('coop_settlement', 'coop_settlement_adjustment')
UNION ALL SELECT 'club_role_membership_20260823 / commerce (club_membership_invoice, oms_order)', COUNT(*) = 2 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('club_membership_invoice', 'oms_order')
UNION ALL SELECT 'w2_terms_axes / business_audit_event (coop_invite, cms_registration_merchant, business_audit_event)', COUNT(*) = 3 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('coop_invite', 'cms_registration_merchant', 'business_audit_event');

-- [6/7] 锁/体量：被 ALTER 或回填的既有表（table_rows 为估算值）
SELECT table_name, engine, table_rows AS approx_rows,
       ROUND((data_length + index_length) / 1024 / 1024, 1) AS approx_mb, row_format
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN ('cms_topic', 'cms_activity', 'member_visited_poi', 'topic_route_session',
                     'roam_share_snapshot', 'theme_state_key', 'sys_role_menu', 'sys_config', 'sys_job',
                     'club', 'mms_merchant', 'ums_creative_square_like', 'official_broadcast',
                     'official_broadcast_log', 'roam_poi', 'roam_poi_reward_log', 'refund_application',
                     'user_invoice_application', 'sys_menu', 'coop_settlement', 'coop_settlement_adjustment',
                     'coop_invite', 'oms_order', 'club_membership_invoice', 'merchant_chapter_application',
                     'play_attempt_log', 'club_post_media_review_outbox', 'member_cash_withdraw',
                     'wx_payment_callback_inbox', 'cms_registration')
ORDER BY table_name;

-- [7/7] 执行窗口前：长事务会让 ALTER 卡在元数据锁上（需要 PROCESS 权限；无权限时本句报错，不影响前面结果）
SELECT trx_id, trx_state, trx_started, TIMESTAMPDIFF(SECOND, trx_started, NOW()) AS age_seconds, trx_rows_locked
FROM information_schema.innodb_trx
ORDER BY trx_started;

-- =====================================================================================
-- 附录（注释，不会执行）：以下查询引用的表/列只在特定状态下存在，放进主脚本会在基线库上解析失败。
-- 按条件手工执行，仍然只读。
-- -------------------------------------------------------------------------------------
-- 附录 A —— 20261013 checksum 对账（仅当 information_schema 里存在 cy_migration_history 时）：
--   SELECT migration_id, checksum_sha256, status, applied_at FROM cy_migration_history
--   WHERE migration_id IN ('20261013_admin_role_page_grants', '20261016_admin_role_payee_separation_forward');
--   比对两个候选值：基线版 0834bcafb1c9a2f169377445d463cc34a6dce7fa2e96e6b6f81d09ab65254ead
--                   master 版 1f147a2136e999b6fd87a7e0a865d8fde5b0c332170d561b28e415b9fccaf56e
--   表不存在 = 生产没有 runner 历史可对账，按 [2/7] 的对象状态判定（4 角色均已有页面授权 = no-op）。
-- -------------------------------------------------------------------------------------
-- 附录 B —— 数据回填预览（[2/7] 判为 DATA_PREVIEW 后再跑）：
--   B1 O-14 city_code：执行仓库里的 chengyinhub-admin/sql/_precheck_o14_content_city_code_backfill_20261022.sql
--   B2 poi_key（poi_key 列已存在后）：
--     SELECT COUNT(*) AS rows_null_poi_key,
--            SUM(lat IS NULL OR lng IS NULL) AS rows_without_coords
--     FROM member_visited_poi WHERE poi_key IS NULL;
--     SELECT member_id, COUNT(*) AS rows_null_poi_key FROM member_visited_poi
--     WHERE poi_key IS NULL GROUP BY member_id ORDER BY rows_null_poi_key DESC LIMIT 20;

--   B3 category_ids（列存在后）：
--     SELECT COUNT(*) AS remaining_backfill FROM mms_merchant
--     WHERE category_id > 0 AND (category_ids IS NULL OR category_ids = '');
--     期望 0；非零 = 数据仍待回填，不能当成 APPLIED。
--   B4 wechat_qrcode（列存在后）：
--     SELECT COUNT(*) AS remaining_url FROM mms_merchant
--     WHERE wechat LIKE 'http://%' OR wechat LIKE 'https://%';
--     期望 0；同时用 owner 自有受控回读核对搬迁前后值，公开回执不要输出二维码 URL。
--   B5 唯一键重复预览（原表存在后；主分类已执行同样的拒绝检查）：
--     SELECT member_id, data_id, COUNT(*) AS duplicate_rows FROM ums_creative_square_like
--     GROUP BY member_id, data_id HAVING COUNT(*) > 1;
--     任何结果都必须停止；不自动删除、不自动合并历史行。
-- -------------------------------------------------------------------------------------
-- 附录 C —— 10-23 之后新增的数据迁移预览（[2/7] 判为 DATA_PREVIEW 后再跑）：
--   C1 发票处理行回填（user_invoice_processing 已存在后）：
--     SELECT COUNT(*) AS apps_without_processing FROM user_invoice_application a
--     WHERE NOT EXISTS (SELECT 1 FROM user_invoice_processing p WHERE p.application_id = a.id);
--     SELECT COUNT(*) AS issuer_owner_rows FROM user_invoice_issuer_owner;
--     issuer_owner_rows = 0 时回填插入 0 行（开票主体是空配置）；回填后仍无处理行的申请交 owner 人工核对。
--   C2 品牌形象图并入门店相册：
--     SELECT COUNT(*) AS merchants_with_brand_images FROM mms_merchant
--     WHERE derivatives IS NOT NULL AND TRIM(derivatives) <> '';
--     SELECT COUNT(*) AS skipped_invalid_gallery FROM mms_merchant
--     WHERE gallery IS NOT NULL AND TRIM(gallery) <> '' AND (JSON_VALID(gallery) = 0 OR JSON_TYPE(gallery) <> 'ARRAY');
--     SELECT COUNT(*) AS skipped_quote_or_backslash FROM mms_merchant
--     WHERE LOCATE('"', derivatives) > 0 OR LOCATE(CHAR(92), derivatives) > 0;
--   C3 下架测试主题：执行仓库里的 chengyinhub-admin/sql/_precheck_remove_wildlife_theme_20261028.sql
--     （第 1 段恰好 1 行、第 2 段全部为 0 才可执行；[2/7] 判 APPLIED 表示已无在架目标行，跳过即可）。
--   C4 模板货架：
--     SELECT id, name, is_template, status, user_status, template_status, del_flag FROM cms_topic
--     WHERE del_flag = 0 AND name IN ('风味巡游', '不惑之年', '预制人生', '高温末日逃生', '上海像一部旧电影', '生活不掉线');
--     期望 6 行、is_template 全为 1；同名多行或 is_template<>1 先停下核对。
--   C5 下架 5 个测试主题（20261029_unlist_test_topics）：执行迁移文件头那条只读 SELECT。
--     期望最多 5 行；live_registrations / live_runs / live_routes 全为 0 且 del_flag=0 的行会被软删，
--     非 0 的行被跳过（留给 owner 另定退款/保留只读）。不在结果里的 id 说明名称或 member_id 不吻合，不会被改动。
--   C6 现金证据前置脏数据（[2/7] 对 20261023_g_cash_evidence_expand 判 BLOCKED_PREREQ 时）：
--     执行仓库里的 chengyinhub-admin/sql/_precheck_g_cash_evidence_20261023.sql，找出平台受款行/空受款人/悬空 claim，
--     交 owner 处置后再重跑预检；不得删除或改写结算行来“通过”前置断言。
--   C7 广播三件套（D / 10-26 / union）：按 release-migration-pack.md 第 4.1 节的表处置；只执行 D 与 union，
--     永不执行 migration_20261026_official_broadcast_delivery.sql。
-- =====================================================================================
