-- =====================================================================================
-- f-release 只读预检（候选 92fca378eec32082a786aa685ba24b0a6223d6f2）
-- 每个发布迁移一条 SELECT，返回 step / migration_id / release_class / phase / status。
-- 只读：全部是 SELECT（information_schema 与业务表），生成器逐句校验，无写入、无 SET、无 PREPARE。
-- 不含任何主机、端口、账号；由业主用只读账号执行（建议先 SET SESSION TRANSACTION READ ONLY，不在本文件里）。
-- 10-29 包的 88 项：原样复用 docs/delivery/release-closeout-20261029/release-migration-precheck.sql
--   的主分类块（列/索引指纹已在 MySQL 8.0 演练中钉住），状态含义见该文件头；其余节（D-PAYEE 授权矩阵、
--   种子键、依赖前置、锁/体量、长事务）请把那份文件整份再跑一次。
-- 之后新增的项：按迁移要建立的对象计数，APPLIED=全部就位，NEEDED=全部缺失，PARTIAL=部分就位（先停下核对）；
--   数据类迁移复用迁移文件自身的只读断言，1/1=已应用（或无需改动），0/1=需要执行。
-- 生产执行状态在本文件运行前一律 UNKNOWN。
-- =====================================================================================

SELECT VERSION() AS mysql_version, DATABASE() AS current_schema, @@transaction_read_only AS session_read_only;

-- [1] migration_20261018_infomation_change_audit.sql · 可选 · PRE_DEPLOY
SELECT 1 AS exec_order, '20261018_infomation_change_audit' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 cms_infomation_change_audit' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 7 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 1614295783807551701
       AND (SELECT COUNT(*) = 3 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 8595058884001806246 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'cms_infomation_change_audit')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cms_infomation_change_audit'), 3) AS c0) AS x0;

-- [2] migration_20261022_audit_activity_switch_seed.sql · 可选 · PRE_DEPLOY
SELECT 2 AS exec_order, '20261022_audit_activity_switch_seed' AS migration_id,
  CASE WHEN GREATEST(c0, c1) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 4 THEN 'DATA_PREVIEW' WHEN GREATEST(c0, c1) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'sys_config 2 键' AS detail
FROM (SELECT
    IFNULL((SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_config WHERE config_key = 'audit.activity.pre_review_all'), 3) AS c0,
    IFNULL((SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_config WHERE config_key = 'audit.activity.high_risk_categories'), 3) AS c1) AS x1;

-- [3] migration_20261022_f7_minor_guardian.sql · 可选 · PRE_DEPLOY
SELECT 3 AS exec_order, '20261022_f7_minor_guardian' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 minor_guardian_authorization' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 19 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 6888410287868121596
       AND (SELECT COUNT(*) = 9 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 4114035956669675034 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'minor_guardian_authorization')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'minor_guardian_authorization'), 3) AS c0) AS x2;

-- [4] migration_20261022_f7_user_invoice.sql · 必须 · PRE_DEPLOY
SELECT 4 AS exec_order, '20261022_f7_user_invoice' AS migration_id,
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
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'user_invoice_application_order'), 3) AS c2) AS x3;

-- [5] migration_20261022_location_guard_switch_seed.sql · 可选 · PRE_DEPLOY
SELECT 5 AS exec_order, '20261022_location_guard_switch_seed' AS migration_id,
  CASE WHEN GREATEST(c0, c1, c2) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1, c2) = 4 THEN 'DATA_PREVIEW' WHEN GREATEST(c0, c1, c2) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1, c2) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1, c2) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'sys_config 3 键' AS detail
FROM (SELECT
    IFNULL((SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_config WHERE config_key = 'play.arrive.location_guard.mode'), 3) AS c0,
    IFNULL((SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_config WHERE config_key = 'play.arrive.require_accuracy'), 3) AS c1,
    IFNULL((SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_config WHERE config_key = 'roam.visited_poi.stable_identity'), 3) AS c2) AS x4;

-- [6] migration_20261022_merchant_desk_switch_seed.sql · 可选 · PRE_DEPLOY
SELECT 6 AS exec_order, '20261022_merchant_desk_switch_seed' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'sys_config 1 键' AS detail
FROM (SELECT
    IFNULL((SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_config WHERE config_key = 'merchant.desk.f4.enabled'), 3) AS c0) AS x5;

-- [7] migration_20261023_creative_square_like_unique.sql · 必须 · PRE_DEPLOY
SELECT 7 AS exec_order, '20261023_creative_square_like_unique' AS migration_id,
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
  '态度唯一键；重复 member_id/data_id 禁止自动删行，先由 owner 对账' AS detail;

-- [8] migration_20261023_points_value_config_seed.sql · 可选 · PRE_DEPLOY
SELECT 8 AS exec_order, '20261023_points_value_config_seed' AS migration_id,
  CASE WHEN c0 = 2 OR c1 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 AND c1 = 1 THEN 'APPLIED'
       WHEN c0 = 0 AND c1 = 0 THEN 'NEEDED'
       ELSE 'PARTIAL' END AS status,
  '积分汇率/单笔上限：每键恰好一行；已有配置值保持原样，不要求等于种子缺省值' AS detail
FROM (SELECT
    (SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_config
      WHERE config_key = 'points.deduct.rate_yuan_per_point') AS c0,
    (SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_config
      WHERE config_key = 'points.deduct.max_yuan_per_order') AS c1) AS points_value_config;

-- [9] migration_20261023_wx_pay_order_intent.sql · 必须 · PRE_DEPLOY
SELECT 9 AS exec_order, '20261023_wx_pay_order_intent' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 wx_pay_order_intent（单次执行）' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 17 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 17851509776243270029
       AND (SELECT COUNT(*) = 13 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 9627256126155115312 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'wx_pay_order_intent')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'wx_pay_order_intent'), 3) AS c0) AS n0;

-- [10] migration_20261024_community_open_flags.sql · 可选 · PRE_DEPLOY
SELECT 10 AS exec_order, '20261024_community_open_flags' AS migration_id,
  CASE WHEN GREATEST(c0, c1, c2) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1, c2) = 4 THEN 'DATA_PREVIEW' WHEN GREATEST(c0, c1, c2) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1, c2) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1, c2) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'sys_config 3 键（缺失才插入；已配置值保持原样）' AS detail
FROM (SELECT
    (SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_config WHERE config_key = 'feature.flag.communityPostRead') AS c0,
    (SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_config WHERE config_key = 'feature.flag.communityPostWrite') AS c1,
    (SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_config WHERE config_key = 'feature.flag.communityPostPilotMembers') AS c2) AS n20;

-- [11] migration_20261024_d_committed_official_notice_wx.sql · 必须 · PRE_DEPLOY
SELECT 11 AS exec_order, '20261024_d_committed_official_notice_wx' AS migration_id,
  CASE WHEN GREATEST(c0, c1) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 committed_official_notice_wx + 默认暂停的 sys_job committedOfficialNoticeTask（单次执行）' AS detail
FROM (SELECT
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_name IN ('id', 'event_key', 'recipient', 'title', 'body', 'im_intent_id', 'request_hash', 'wx_state', 'owner_token', 'created_at', 'attempted_at', 'accepted_at')) = 12 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'committed_official_notice_wx') AS c0,
    (SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_job WHERE CAST(TRIM(invoke_target) AS BINARY) = CAST('committedOfficialNoticeTask.deliverPending()' AS BINARY)) AS c1) AS z1;

-- [12] migration_20261024_c_before_start_notice_typed.sql · 不执行 · PRE_DEPLOY
SELECT 12 AS exec_order, '20261024_c_before_start_notice_typed' AS migration_id,
  CASE WHEN (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'committed_official_notice_wx' AND column_name = 'notice_type') = 1 AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'committed_official_notice_wx' AND column_name = 'wx_payload_json') = 1 THEN 'APPLIED' ELSE 'REPLACED_UNAPPLIED' END AS status,
  '已被 20261024_g_before_start_notice_metadata_v2 取代：任何情况下都不执行本文件（REPLACED_UNAPPLIED）' AS detail;

-- [13] migration_20261024_d_npc_asr_intent.sql · 必须 · PRE_DEPLOY
SELECT 13 AS exec_order, '20261024_d_npc_asr_intent' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 npc_asr_intent（单次执行）' AS detail
FROM (SELECT
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_name IN ('id', 'user_id', 'request_id', 'node_id', 'merchant_member_id', 'audio_sha256', 'audio_format', 'claim_token', 'state', 'transcript', 'body_erased', 'handoff_started', 'text_admitted', 'body_expires_at', 'create_time', 'update_time')) = 16 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'npc_asr_intent') AS c0) AS z3;

-- [14] migration_20261024_d_npc_asr_shared_quota.sql · 必须 · PRE_DEPLOY
SELECT 14 AS exec_order, '20261024_d_npc_asr_shared_quota' AS migration_id,
  CASE WHEN GREATEST(c0, c1, c2) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1, c2) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1, c2) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1, c2) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'npc_asr_intent 配额 2 列 + 索引（单次执行）' AS detail
FROM (SELECT
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'npc_asr_intent' AND column_name = 'quota_reservation') AS c0,
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'npc_asr_intent' AND column_name = 'quota_refund_pending') AS c1,
    (SELECT IF(COUNT(*) > 0, 1, 0) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'npc_asr_intent' AND index_name = 'idx_npc_asr_quota_refund') AS c2) AS z4;

-- [15] migration_20261024_g_before_start_notice_metadata_v2.sql · 必须 · PRE_DEPLOY
SELECT 15 AS exec_order, '20261024_g_before_start_notice_metadata_v2' AS migration_id,
  CASE WHEN GREATEST(c0, c1, c2, c3) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1, c2, c3) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1, c2, c3) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1, c2, c3) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'committed_official_notice_wx typed 两列 + CHECK + 索引（单次执行）' AS detail
FROM (SELECT
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'committed_official_notice_wx' AND column_name = 'notice_type') AS c0,
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'committed_official_notice_wx' AND column_name = 'wx_payload_json') AS c1,
    (SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND constraint_name = 'ck_committed_official_notice_typed') AS c2,
    (SELECT IF(COUNT(*) > 0, 1, 0) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'committed_official_notice_wx' AND index_name = 'idx_committed_official_notice_typed_pending') AS c3) AS z2;

-- [16] migration_20261024_registration_verified_seats.sql · 必须 · PRE_DEPLOY
SELECT 16 AS exec_order, '20261024_registration_verified_seats' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 cms_registration_seat（单次执行）' AS detail
FROM (SELECT
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_name IN ('id', 'registration_id', 'seat_no', 'status', 'topic_id', 'activity_id', 'verifier_member_id', 'verified_at')) = 8 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cms_registration_seat') AS c0) AS z5;

-- [17] migration_20261024_registration_seat_activity_scope.sql · 必须 · PRE_DEPLOY
SELECT 17 AS exec_order, '20261024_registration_seat_activity_scope' AS migration_id,
  CASE WHEN s IS NULL THEN 'NEEDED' WHEN s LIKE '%topic_idisnullortopic_id>0%' THEN 'APPLIED'
       WHEN s LIKE '%topic_idisnotnullandtopic_id>0%' THEN 'NEEDED' ELSE 'CONFLICT' END AS status,
  'chk_registration_seat_fact 允许独立活动席位（可重入）' AS detail
FROM (SELECT (SELECT LOWER(REGEXP_REPLACE(cc.check_clause, '[[:space:]()`]', '')) FROM information_schema.check_constraints cc JOIN information_schema.table_constraints tc ON tc.constraint_schema = cc.constraint_schema AND tc.constraint_name = cc.constraint_name WHERE cc.constraint_schema = DATABASE() AND tc.table_name = 'cms_registration_seat' AND cc.constraint_name = 'chk_registration_seat_fact' LIMIT 1) AS s) AS z6;

-- [18] migration_20261025_coop_invite_request_receipt.sql · 必须 · PRE_DEPLOY
SELECT 18 AS exec_order, '20261025_coop_invite_request_receipt' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 coop_invite_request_receipt' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 16 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 17316570512569307862
       AND (SELECT COUNT(*) = 6 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 8173663945025707328 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'coop_invite_request_receipt')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'coop_invite_request_receipt'), 3) AS c0) AS n2;

-- [19] migration_20261026_official_event_attendance.sql · 必须 · PRE_DEPLOY
SELECT 19 AS exec_order, '20261026_official_event_attendance' AS migration_id,
  CASE WHEN GREATEST(c0, c1) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 4 THEN 'DATA_PREVIEW' WHEN GREATEST(c0, c1) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 official_event_attendance(_config)' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 5 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 522559710578164264
       AND (SELECT COUNT(*) = 1 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 14494209268198970070 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'official_event_attendance_config')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_event_attendance_config'), 3) AS c0,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 8 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 953998888564537766
       AND (SELECT COUNT(*) = 4 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 6967273786435203968 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'official_event_attendance')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_event_attendance'), 3) AS c1) AS n3;

-- [20] migration_20261026_team_event_command_receipt.sql · 必须 · PRE_DEPLOY
SELECT 20 AS exec_order, '20261026_team_event_command_receipt' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 team_event_command_receipt' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 10 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 9596879265546310952
       AND (SELECT COUNT(*) = 5 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 14619782478392098117 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'team_event_command_receipt')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'team_event_command_receipt'), 3) AS c0) AS n4;

-- [21] migration_20261026_theme_attribute_registry_version.sql · 必须 · PRE_DEPLOY
SELECT 21 AS exec_order, '20261026_theme_attribute_registry_version' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 theme_attribute_registry_version' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 2 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 4210680533194302826
       AND (SELECT COUNT(*) = 1 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 14128333254474228543 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'theme_attribute_registry_version')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'theme_attribute_registry_version'), 3) AS c0) AS n5;

-- [22] migration_20261026_user_invoice_processing.sql · 必须 · PRE_DEPLOY
SELECT 22 AS exec_order, '20261026_user_invoice_processing' AS migration_id,
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
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'user_invoice_issued_reference'), 3) AS c5) AS n6;

-- [23] migration_20261026_user_invoice_title_receipt.sql · 必须 · PRE_DEPLOY
SELECT 23 AS exec_order, '20261026_user_invoice_title_receipt' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 user_invoice_title_receipt' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 5 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 10065551186701799896
       AND (SELECT COUNT(*) = 2 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 5863983891022771450 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'user_invoice_title_receipt')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'user_invoice_title_receipt'), 3) AS c0) AS n7;

-- [24] migration_20261027_template_adoption_receipt.sql · 必须 · PRE_DEPLOY
-- 也接受 20261107_template_adoption_inherited_config_hash 落地后的形状（f-rehearsal：否则全部执行后重跑预检会误判 CONFLICT）
SELECT 24 AS exec_order, '20261027_template_adoption_receipt' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 template_adoption_receipt' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 12 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 11451000278466319056
       AND (SELECT COUNT(*) = 2 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 8511979612919902689 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'template_adoption_receipt')
       THEN 1 WHEN COUNT(*) = 13 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 15081007648298938700
       AND (SELECT COUNT(*) = 6 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 7132261291781051820 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'template_adoption_receipt')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'template_adoption_receipt'), 3) AS c0) AS n8;

-- [25] migration_20261027_user_invoice_processing_backfill.sql · 需业主授权 · PRE_DEPLOY
SELECT 25 AS exec_order, '20261027_user_invoice_processing_backfill' AS migration_id,
  CASE WHEN c0 = 4 THEN 'DATA_PREVIEW' ELSE 'NEEDED' END AS status,
  '数据回填：处理行就位后须跑附录 C1 只读预览；DATA_PREVIEW 不是回填完成证明' AS detail
FROM (SELECT
    IF(EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'user_invoice_application')
       AND EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'user_invoice_processing')
       AND EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'user_invoice_issuer_owner'), 4, 0) AS c0) AS n28;

-- [26] migration_20261028_topic_play_setup.sql · 必须 · PRE_DEPLOY
SELECT 26 AS exec_order, '20261028_topic_play_setup' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 cms_topic_play_setup' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 7 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 16548424480571454463
       AND (SELECT COUNT(*) = 1 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 14128333254474228543 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'cms_topic_play_setup')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cms_topic_play_setup'), 3) AS c0) AS n9;

-- [27] migration_20261029_activity_cancellation_receipt.sql · 必须 · PRE_DEPLOY
SELECT 27 AS step, '20261029_activity_cancellation_receipt' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '列 cms_activity.cancellation_receipt_json' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cms_activity' AND column_name = 'cancellation_receipt_json'), 0)) AS present, 1 AS expected) x;

-- [28] migration_20261029_topic_cancellation_receipt.sql · 必须 · PRE_DEPLOY
SELECT 28 AS step, '20261029_topic_cancellation_receipt' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '表 cms_topic_cancellation_receipt；列 cms_topic.cancellation_epoch' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'cms_topic_cancellation_receipt'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cms_topic' AND column_name = 'cancellation_epoch'), 0)) AS present, 2 AS expected) x;

-- [29] migration_20261031_coop_invite_read_time.sql · 必须 · PRE_DEPLOY
SELECT 29 AS step, '20261031_coop_invite_read_time' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '列 coop_invite.read_time' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'coop_invite' AND column_name = 'read_time'), 0)) AS present, 1 AS expected) x;

-- [30] migration_20261101_author_write_receipt.sql · 必须 · PRE_DEPLOY
SELECT 30 AS step, '20261101_author_write_receipt' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '表 app_author_write_receipt' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'app_author_write_receipt'), 0)) AS present, 1 AS expected) x;

-- [31] migration_20261101_stagger_sys_job_seconds.sql · 可选 · PRE_DEPLOY
SELECT 31 AS step, '20261101_stagger_sys_job_seconds' AS migration_id, '可选' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '迁移自身只读断言' AS detail
FROM (SELECT (IFNULL((SELECT (SELECT COUNT(*) FROM sys_job WHERE status = '0' AND cron_expression IN ('0 * * * * ?', '0 0/2 * * * ?', '0 0/10 * * * ?', '0 0/15 * * * ?', '0 0/30 * * * ?')) = 0
   AND (SELECT COUNT(*) FROM sys_job WHERE status = '0' AND cron_expression = '0 0/5 * * * ?') <= 1 AS sys_job_staggered), 0)) AS present, 1 AS expected) x;

-- [32] migration_20261101_theme_categories.sql · 必须 · PRE_DEPLOY
SELECT 32 AS step, '20261101_theme_categories' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '迁移自身只读断言' AS detail
FROM (SELECT (IFNULL((SELECT (SELECT COUNT(*) FROM sys_category WHERE type = 1 AND parent_id = 0 AND status = 1 AND del_flag = 0
          AND category_name IN ('衣物洗护', '家政维修', '出行服务', '科技数码')) = 0
   AND (SELECT COUNT(*) FROM sys_category WHERE type = 1 AND parent_id = 0 AND status = 1 AND del_flag = 0
          AND category_name IN ('城市漫步', '历史人文', '美食探店', '解谜冒险', '夜游', '摄影打卡', '艺术展览', '城市运动', '户外徒步', '亲子同行', '娱乐休闲', '生活美学')) = 12
   AS theme_categories_ready), 0)) AS present, 1 AS expected) x;

-- [33] migration_20261102_merchant_industry_categories.sql · 必须 · PRE_DEPLOY
SELECT 33 AS step, '20261102_merchant_industry_categories' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '迁移自身只读断言' AS detail
FROM (SELECT (IFNULL((SELECT (SELECT COUNT(*) FROM sys_category WHERE type IN (1, 6) AND parent_id = 0 AND status = 1 AND del_flag = 0
          AND (category_name LIKE '%咖啡%' OR category_name LIKE '%茶馆%' OR category_name LIKE '%书店%'
               OR category_name LIKE '%手作%' OR category_name LIKE '%古着%' OR category_name LIKE '%文创%'
               OR category_name LIKE '%花%' OR category_name LIKE '%烘焙%' OR category_name LIKE '%酒%'
               OR category_name LIKE '%宠物%')) >= 10
   AS merchant_industry_categories_ready), 0)) AS present, 1 AS expected) x;

-- [34] migration_20261103_content_safety_recheck.sql · 必须 · PRE_DEPLOY
SELECT 34 AS step, '20261103_content_safety_recheck' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '表 content_safety_recheck' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'content_safety_recheck'), 0)) AS present, 1 AS expected) x;

-- [35] migration_20261103_sensitive_content_library.sql · 必须 · PRE_DEPLOY
SELECT 35 AS step, '20261103_sensitive_content_library' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '表 content_sensitive_word、content_sensitive_allow、content_sensitive_image、content_sensitive_hit；菜单行 2 个；配置键 content.sensitive.rescan.remove' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'content_sensitive_word'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'content_sensitive_allow'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'content_sensitive_image'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'content_sensitive_hit'), 0) + IFNULL((SELECT COUNT(*) > 0 FROM sys_menu WHERE perms = 'business:sensitive:edit'), 0) + IFNULL((SELECT COUNT(*) > 0 FROM sys_menu WHERE perms = 'business:sensitive:list'), 0) + IFNULL((SELECT COUNT(*) > 0 FROM sys_config WHERE config_key = 'content.sensitive.rescan.remove'), 0)) AS present, 7 AS expected) x;

-- [36] migration_20261104_content_safety_jobs_post_deploy.sql · 必须 · POST_DEPLOY
SELECT 36 AS step, '20261104_content_safety_jobs_post_deploy' AS migration_id, '必须' AS release_class, 'POST_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '定时任务 contentSafetyRecheckTask.run()、sensitiveContentRescanTask.run()' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) > 0 FROM sys_job WHERE invoke_target = 'contentSafetyRecheckTask.run()'), 0) + IFNULL((SELECT COUNT(*) > 0 FROM sys_job WHERE invoke_target = 'sensitiveContentRescanTask.run()'), 0)) AS present, 2 AS expected) x;

-- [37] migration_20261104_invitee_signup_points_off.sql · 需业主授权 · PRE_DEPLOY
SELECT 37 AS step, '20261104_invitee_signup_points_off' AS migration_id, '需业主授权' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '迁移自身只读断言' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) = 0 AS invitee_signup_points_off
FROM cms_points_setting
WHERE id = 6
  AND (status IS NULL OR status <> 0 OR value IS NULL OR value <> 0)), 0)) AS present, 1 AS expected) x;

-- [38] migration_20261104_merchant_industry_categories_readback.sql · 必须 · PRE_DEPLOY
SELECT 38 AS step, '20261104_merchant_industry_categories_readback' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '迁移自身只读断言' AS detail
FROM (SELECT (IFNULL((SELECT (SELECT COUNT(*) FROM sys_category
        WHERE type = 6 AND parent_id = 0 AND status = 1 AND del_flag = 0) >= 1
   AND (SELECT COUNT(*) FROM (
          SELECT '咖啡' AS kw UNION ALL SELECT '茶馆' UNION ALL SELECT '书店' UNION ALL SELECT '手作'
          UNION ALL SELECT '古着' UNION ALL SELECT '文创' UNION ALL SELECT '花' UNION ALL SELECT '烘焙'
          UNION ALL SELECT '酒' UNION ALL SELECT '宠物') k
        WHERE EXISTS (SELECT 1 FROM sys_category c
                      WHERE c.type IN (1, 6) AND c.parent_id = 0 AND c.status = 1 AND c.del_flag = 0
                        AND c.category_name LIKE CONCAT('%', k.kw, '%'))) = 10
   AS merchant_industry_categories_per_keyword), 0)) AS present, 1 AS expected) x;

-- [39] migration_20261104_topic_total_time_seconds.sql · 需业主授权 · POST_DEPLOY
SELECT 39 AS step, '20261104_topic_total_time_seconds' AS migration_id, '需业主授权' AS release_class, 'POST_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '迁移自身只读断言' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) = 0 AS topic_total_time_in_seconds
FROM cms_topic t
JOIN (
    SELECT topic_id,
           SUM(IFNULL(node_time, 0)) AS all_minutes,
           SUM(CASE WHEN merchant_member_id IS NULL THEN IFNULL(node_time, 0) ELSE 0 END) AS host_minutes
    FROM cms_topic_node
    WHERE topic_id IS NOT NULL
    GROUP BY topic_id
) n ON n.topic_id = t.id
WHERE t.total_time > 0
  AND (t.total_time = n.all_minutes OR t.total_time = n.host_minutes)
  AND t.total_time <> n.all_minutes * 60
  AND t.total_time <> n.host_minutes * 60), 0)) AS present, 1 AS expected) x;

-- [40] migration_20261105_chapter_video.sql · 必须 · PRE_DEPLOY
SELECT 40 AS step, '20261105_chapter_video' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '列 cms_topic_chapter.video_url、cms_topic_chapter.video_poster_url' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cms_topic_chapter' AND column_name = 'video_url'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cms_topic_chapter' AND column_name = 'video_poster_url'), 0)) AS present, 2 AS expected) x;

-- [41] migration_20261105_welcome_points_fifty.sql · 需业主授权 · PRE_DEPLOY
SELECT 41 AS step, '20261105_welcome_points_fifty' AS migration_id, '需业主授权' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '迁移自身只读断言' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) = 0 AS welcome_points_fifty
FROM cms_points_setting
WHERE id = 1
  AND (value IS NULL OR value <> 50)), 0)) AS present, 1 AS expected) x;

-- [42] migration_20261107_template_adoption_inherited_config_hash.sql · 必须 · PRE_DEPLOY
SELECT 42 AS step, '20261107_template_adoption_inherited_config_hash' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '列 template_adoption_receipt.inherited_advanced_config_hash；索引 template_adoption_receipt.idx_adoption_inherited_config' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'template_adoption_receipt' AND column_name = 'inherited_advanced_config_hash'), 0) + IFNULL((SELECT COUNT(*) > 0 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'template_adoption_receipt' AND index_name = 'idx_adoption_inherited_config'), 0)) AS present, 2 AS expected) x;

-- [43] migration_20261108_play_help_run_quota.sql · 必须 · PRE_DEPLOY
SELECT 43 AS step, '20261108_play_help_run_quota' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '表 play_help_run_quota' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'play_help_run_quota'), 0)) AS present, 1 AS expected) x;

-- [44] migration_20261108_registration_seat_invitation.sql · 必须 · PRE_DEPLOY
SELECT 44 AS step, '20261108_registration_seat_invitation' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '表 cms_registration_seat_invitation、cms_registration_seat_invitation_request' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'cms_registration_seat_invitation'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'cms_registration_seat_invitation_request'), 0)) AS present, 2 AS expected) x;

-- [45] migration_20261108_topic_content_revision.sql · 必须 · PRE_DEPLOY
SELECT 45 AS step, '20261108_topic_content_revision' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '表 cms_topic_content_revision' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'cms_topic_content_revision'), 0)) AS present, 1 AS expected) x;

-- [46] migration_20261109_app_media_provenance.sql · 必须 · PRE_DEPLOY
SELECT 46 AS step, '20261109_app_media_provenance' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '列 app_media_asset.source_type、app_media_asset.license_note、app_media_asset.attribution、app_media_asset.ai_model、app_media_asset.source_created_at' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'app_media_asset' AND column_name = 'source_type'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'app_media_asset' AND column_name = 'license_note'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'app_media_asset' AND column_name = 'attribution'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'app_media_asset' AND column_name = 'ai_model'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'app_media_asset' AND column_name = 'source_created_at'), 0)) AS present, 5 AS expected) x;

-- [47] migration_20261109_play_run_feedback.sql · 必须 · PRE_DEPLOY
SELECT 47 AS step, '20261109_play_run_feedback' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '表 play_run_feedback' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'play_run_feedback'), 0)) AS present, 1 AS expected) x;

-- [48] migration_20261110_ecommerce_payout.sql · 必须 · PRE_DEPLOY
SELECT 48 AS step, '20261110_ecommerce_payout' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '表 ecom_sub_merchant、ecom_order_seller、ecom_profit_share_order、ecom_profit_share_line、ecom_profit_share_return、creator_payout_request' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'ecom_sub_merchant'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'ecom_order_seller'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'ecom_profit_share_order'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'ecom_profit_share_line'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'ecom_profit_share_return'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'creator_payout_request'), 0)) AS present, 6 AS expected) x;

-- [49] migration_20261110_ecommerce_payout_wiring.sql · 必须 · POST_DEPLOY
SELECT 49 AS step, '20261110_ecommerce_payout_wiring' AS migration_id, '必须' AS release_class, 'POST_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '表 ecom_refund；菜单行 2 个；定时任务 ecommercePayoutTask.shareAndRetry()、ecommercePayoutTask.syncOnboarding()' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'ecom_refund'), 0) + IFNULL((SELECT COUNT(*) > 0 FROM sys_menu WHERE perms = 'business:ecommercepayout:edit'), 0) + IFNULL((SELECT COUNT(*) > 0 FROM sys_menu WHERE perms = 'business:ecommercepayout:query'), 0) + IFNULL((SELECT COUNT(*) > 0 FROM sys_job WHERE invoke_target = 'ecommercePayoutTask.shareAndRetry()'), 0) + IFNULL((SELECT COUNT(*) > 0 FROM sys_job WHERE invoke_target = 'ecommercePayoutTask.syncOnboarding()'), 0)) AS present, 5 AS expected) x;

-- [50] migration_20261110_platform_pass.sql · 必须 · PRE_DEPLOY
SELECT 50 AS step, '20261110_platform_pass' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '表 platform_pass_plan、platform_pass_order、platform_pass_membership、platform_pass_claim' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'platform_pass_plan'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'platform_pass_order'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'platform_pass_membership'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'platform_pass_claim'), 0)) AS present, 4 AS expected) x;

-- [51] migration_20261111_aigc_filing_config_seed.sql · 必须 · PRE_DEPLOY
SELECT 51 AS step, '20261111_aigc_filing_config_seed' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '迁移自身只读断言' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) = 1 AS assertion_ok FROM sys_config WHERE config_key = 'aigc.algorithm.filing-no'), 0)) AS present, 1 AS expected) x;

-- [52] migration_20261111_ecommerce_combine.sql · 必须 · POST_DEPLOY
SELECT 52 AS step, '20261111_ecommerce_combine' AS migration_id, '必须' AS release_class, 'POST_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '表 ecom_combine_order、ecom_combine_sub_order、ecom_combine_refund、ecom_combine_settlement、ecom_combine_return、ecom_supplier_payable' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'ecom_combine_order'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'ecom_combine_sub_order'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'ecom_combine_refund'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'ecom_combine_settlement'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'ecom_combine_return'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'ecom_supplier_payable'), 0)) AS present, 6 AS expected) x;

-- [53] migration_20261112_chapter_reward.sql · 必须 · PRE_DEPLOY
SELECT 53 AS step, '20261112_chapter_reward' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '迁移自身只读断言' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) = 1 FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'cms_topic_chapter'
  AND column_name = 'reward_text' AND character_maximum_length = 64), 0)) AS present, 1 AS expected) x;

-- [54] migration_20261112_dec_c_payout_jobs.sql · 必须 · POST_DEPLOY
SELECT 54 AS step, '20261112_dec_c_payout_jobs' AS migration_id, '必须' AS release_class, 'POST_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '迁移自身只读断言' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*)=2 AND SUM(status='0' AND concurrent='1' AND misfire_policy='3')=2
FROM sys_job WHERE invoke_target IN ('ecommercePayoutTask.shareAndRetry()', 'ecommercePayoutTask.syncOnboarding()')), 0)) AS present, 1 AS expected) x;

-- [55] migration_20261112_dec_c_safety.sql · 必须 · PRE_DEPLOY
SELECT 55 AS step, '20261112_dec_c_safety' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '列 app_media_asset.safety_rejected' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'app_media_asset' AND column_name = 'safety_rejected'), 0)) AS present, 1 AS expected) x;

-- [56] migration_20261112_dec_c_team_codes.sql · 必须 · PRE_DEPLOY
SELECT 56 AS step, '20261112_dec_c_team_codes' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '列 play_team.legacy_invite_code、play_team.legacy_invite_expire_time；索引 play_team.uk_play_team_legacy_invite' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'play_team' AND column_name = 'legacy_invite_code'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'play_team' AND column_name = 'legacy_invite_expire_time'), 0) + IFNULL((SELECT COUNT(*) > 0 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'play_team' AND index_name = 'uk_play_team_legacy_invite'), 0)) AS present, 3 AS expected) x;

-- [57] migration_20261111_ai_generation_log_retention_index.sql · 必须 · PRE_DEPLOY
SELECT 57 AS step, '20261111_ai_generation_log_retention_index' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '索引 ai_generation_log.idx_ai_gen_create_time' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) > 0 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'ai_generation_log' AND index_name = 'idx_ai_gen_create_time'), 0)) AS present, 1 AS expected) x;

-- [58] migration_ai_theme_draft_request_intent_20261024.sql · 必须 · PRE_DEPLOY
SELECT 58 AS exec_order, 'ai_theme_draft_request_intent_20261024' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 ai_theme_draft_request_intent（单次执行）' AS detail
FROM (SELECT
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_name IN ('id', 'member_id', 'prepare_key', 'request_id', 'request_hash', 'product_type', 'plan_hash', 'role', 'pool', 'original_day', 'counter_key', 'nonce', 'receipt_key', 'reservation', 'creator_config_hash', 'gateway_config_hash', 'state', 'provider_state', 'reason_code', 'claim_token', 'result_hash', 'outcome_hash', 'claimed_at', 'created_at', 'updated_at', 'completed_at', 'execute_before', 'context_expires_at', 'result_expires_at', 'context_purged_at', 'result_purged_at')) = 31 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ai_theme_draft_request_intent') AS c0) AS z7;

-- [59] migration_audit_problem_20261022.sql · 可选 · PRE_DEPLOY
SELECT 59 AS exec_order, 'audit_problem_20261022' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 audit_problem' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 11 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 10367431202118961532
       AND (SELECT COUNT(*) = 4 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 4534874757148141377 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'audit_problem')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'audit_problem'), 3) AS c0) AS x6;

-- [60] migration_20261102_club_session_gather_point.sql · 需业主授权 · PRE_DEPLOY
SELECT 60 AS step, '20261102_club_session_gather_point' AS migration_id, '需业主授权' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '复用 20261104 readback 的精确谓词：该填未填的场次为 0' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) = 0 AS club_session_gather_point_none_missing
FROM cms_activity a
WHERE a.club_id IS NOT NULL AND a.club_id > 0
  AND a.del_flag = 0 AND a.cancel_time IS NULL
  AND (a.end_date IS NULL OR a.end_date > NOW())
  AND (a.latitude IS NULL OR a.latitude = '')
  AND (a.longitude IS NULL OR a.longitude = '')
  AND EXISTS (
      SELECT 1 FROM oms_ticket t
      WHERE t.owner_type = 2 AND t.owner_id = a.id AND t.del_flag = 0
        AND t.gather_lat IS NOT NULL AND t.gather_lng IS NOT NULL)), 0)) AS present, 1 AS expected) x;

-- [61] migration_20261104_club_session_gather_point_readback.sql · 需业主授权 · PRE_DEPLOY
SELECT 61 AS step, '20261104_club_session_gather_point_readback' AS migration_id, '需业主授权' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '迁移自身只读断言' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) = 0 AS club_session_gather_point_none_missing
FROM cms_activity a
WHERE a.club_id IS NOT NULL AND a.club_id > 0
  AND a.del_flag = 0 AND a.cancel_time IS NULL
  AND (a.end_date IS NULL OR a.end_date > NOW())
  AND (a.latitude IS NULL OR a.latitude = '')
  AND (a.longitude IS NULL OR a.longitude = '')
  AND EXISTS (
      SELECT 1 FROM oms_ticket t
      WHERE t.owner_type = 2 AND t.owner_id = a.id AND t.del_flag = 0
        AND t.gather_lat IS NOT NULL AND t.gather_lng IS NOT NULL)), 0)) AS present, 1 AS expected) x;

-- [62] migration_20261024_d_chapter_application_round.sql · 必须 · PRE_DEPLOY
SELECT 62 AS exec_order, '20261024_d_chapter_application_round' AS migration_id,
  CASE WHEN GREATEST(c0, c1) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'merchant_chapter_application.application_round + CHECK（单次执行）' AS detail
FROM (SELECT
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'merchant_chapter_application' AND column_name = 'application_round') AS c0,
    (SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND constraint_name = 'ck_merchant_application_round') AS c1) AS z8;

-- [63] migration_20261029_club_post_repost.sql · 必须 · PRE_DEPLOY
SELECT 63 AS step, '20261029_club_post_repost' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '表 club_post_repost；列 club_post.repost_count' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'club_post_repost'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'club_post' AND column_name = 'repost_count'), 0)) AS present, 2 AS expected) x;

-- [64] migration_club_welcome_note_20261022.sql · 可选 · PRE_DEPLOY
SELECT 64 AS exec_order, 'club_welcome_note_20261022' AS migration_id,
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
  'welcome_note 普通可空列；NEEDED 才执行一次，APPLIED 必须跳过原 SQL' AS detail;

-- [65] migration_doc2_f3_bundle_ledger_20261022.sql · 可选 · PRE_DEPLOY
SELECT 65 AS exec_order, 'doc2_f3_bundle_ledger_20261022' AS migration_id,
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
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'explore_bundle_exit_case'), 3) AS c5) AS x7;

-- [66] migration_doc2_f3_refund_hold_20261026.sql · 可选 · PRE_DEPLOY
SELECT 66 AS exec_order, 'doc2_f3_refund_hold_20261026' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 explore_bundle_refund_hold' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 10 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 11813387773646313411
       AND (SELECT COUNT(*) = 2 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 12929690359715079447 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'explore_bundle_refund_hold')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'explore_bundle_refund_hold'), 3) AS c0) AS n10;

-- [67] migration_marketing_attribution_20261108.sql · 必须 · PRE_DEPLOY
SELECT 67 AS step, 'marketing_attribution_20261108' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '表 marketing_referral_credential、marketing_attribution' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'marketing_referral_credential'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'marketing_attribution'), 0)) AS present, 2 AS expected) x;

-- [68] migration_marketing_product_20261108.sql · 必须 · PRE_DEPLOY
SELECT 68 AS step, 'marketing_product_20261108' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '表 marketing_product、marketing_product_item、marketing_product_order、marketing_product_order_line、gift_entitlement' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'marketing_product'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'marketing_product_item'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'marketing_product_order'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'marketing_product_order_line'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'gift_entitlement'), 0)) AS present, 5 AS expected) x;

-- [69] migration_marketing_extra_seat_20261108.sql · 必须 · PRE_DEPLOY
SELECT 69 AS step, 'marketing_extra_seat_20261108' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '索引 marketing_product_order.idx_marketing_order_extra_seat' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) > 0 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'marketing_product_order' AND index_name = 'idx_marketing_order_extra_seat'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.check_constraints WHERE constraint_schema = DATABASE() AND constraint_name = 'ck_marketing_order_fulfillment' AND check_clause LIKE '%3%'), 0)) AS present, 2 AS expected) x;

-- [70] migration_marketing_revenue_allocation_20261108.sql · 必须 · PRE_DEPLOY
SELECT 70 AS step, 'marketing_revenue_allocation_20261108' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '表 marketing_revenue_rule、marketing_revenue_allocation、marketing_revenue_allocation_line、marketing_revenue_posting_task' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'marketing_revenue_rule'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'marketing_revenue_allocation'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'marketing_revenue_allocation_line'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'marketing_revenue_posting_task'), 0)) AS present, 4 AS expected) x;

-- [71] migration_marketing_z_channel_voucher_20261108.sql · 必须 · PRE_DEPLOY
SELECT 71 AS step, 'marketing_z_channel_voucher_20261108' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '表 marketing_channel_voucher_account、marketing_channel_order_verification、marketing_channel_voucher、marketing_channel_redemption、marketing_channel_refund_verification' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'marketing_channel_voucher_account'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'marketing_channel_order_verification'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'marketing_channel_voucher'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'marketing_channel_redemption'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'marketing_channel_refund_verification'), 0)) AS present, 5 AS expected) x;

-- [72] migration_marketing_task_registration_20261108.sql · 必须 · POST_DEPLOY
SELECT 72 AS step, 'marketing_task_registration_20261108' AS migration_id, '必须' AS release_class, 'POST_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '定时任务 channelRefundConsumptionTask.run()、marketingRevenuePostingTask.run()' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) > 0 FROM sys_job WHERE invoke_target = 'channelRefundConsumptionTask.run()'), 0) + IFNULL((SELECT COUNT(*) > 0 FROM sys_job WHERE invoke_target = 'marketingRevenuePostingTask.run()'), 0)) AS present, 2 AS expected) x;

-- [73] migration_20261023_cash_withdraw_fund_fact.sql · 必须 · PRE_DEPLOY
SELECT 73 AS exec_order, '20261023_cash_withdraw_fund_fact' AS migration_id,
  CASE WHEN GREATEST(c0, c1, c2) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1, c2) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1, c2) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1, c2) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 member_cash_withdraw_fund_fact + 两个只追加触发器（单次执行）' AS detail
FROM (SELECT
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_name IN ('id', 'withdrawal_id', 'member_id', 'amount', 'out_batch_no', 'out_detail_no', 'kind', 'submission_intent_id', 'balance_before', 'balance_after', 'channel_batch_status', 'channel_detail_status', 'channel_detail_id', 'channel_error_code', 'create_time')) = 15 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'member_cash_withdraw_fund_fact') AS c0,
    (SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema = DATABASE() AND trigger_name = 'cash_fund_fact_no_update') AS c1,
    (SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema = DATABASE() AND trigger_name = 'cash_fund_fact_no_delete') AS c2) AS z9;

-- [74] migration_20261024_cash_withdraw_transfer_intent.sql · 必须 · PRE_DEPLOY
SELECT 74 AS exec_order, '20261024_cash_withdraw_transfer_intent' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 member_cash_withdraw_transfer_intent（单次执行）' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 16 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 11618201005589884506
       AND (SELECT COUNT(*) = 3 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 12031721957538158388 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'member_cash_withdraw_transfer_intent')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'member_cash_withdraw_transfer_intent'), 3) AS c0) AS n1;

-- [75] migration_20261025_cash_withdraw_fund_request_compat.sql · 必须 · PRE_DEPLOY
SELECT 75 AS exec_order, '20261025_cash_withdraw_fund_request_compat' AS migration_id,
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
    (SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND constraint_name = 'ck_cash_fund_request') AS c8) AS z10;

-- [76] migration_member_profile_version_20261022.sql · 必须 · PRE_DEPLOY
SELECT 76 AS exec_order, 'member_profile_version_20261022' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 ums_member_profile_version' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 7 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 6303910045254689405
       AND (SELECT COUNT(*) = 3 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 16657050347810390992 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'ums_member_profile_version')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ums_member_profile_version'), 3) AS c0) AS x8;

-- [77] migration_member_visited_poi_poi_key_20261022.sql · 可选 · PRE_DEPLOY
SELECT 77 AS exec_order, 'member_visited_poi_poi_key_20261022' AS migration_id,
  CASE WHEN p0 = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 4 THEN 'DATA_PREVIEW' WHEN GREATEST(c0, c1) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'member_visited_poi.poi_key + uk_member_poi_key' AS detail
FROM (SELECT
    IFNULL((SELECT IF(COUNT(*) = 1, 1, 3) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'member_visited_poi' AND engine = 'InnoDB'), 3) AS p0,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'varchar(32)' AND is_nullable = 'YES' AND column_default IS NULL) = 1 THEN 1 ELSE 2 END
   FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'member_visited_poi' AND column_name = 'poi_key'), 3) AS c0,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 2 AND SUM(non_unique = 0) = 2
       AND GROUP_CONCAT(column_name ORDER BY seq_in_index) = 'member_id,poi_key' THEN 1 ELSE 2 END
   FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'member_visited_poi' AND index_name = 'uk_member_poi_key'), 3) AS c1) AS x9;

-- [78] migration_merchant_category_ids_20261022.sql · 必须 · PRE_DEPLOY
SELECT 78 AS exec_order, 'merchant_category_ids_20261022' AS migration_id,
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
  'category_ids 结构齐全仍须附录 B3 核对数据；DATA_PREVIEW 不是迁移已完成证明' AS detail;

-- [79] migration_20261027_merchant_brand_images_to_gallery.sql · 需业主授权 · PRE_DEPLOY
SELECT 79 AS exec_order, '20261027_merchant_brand_images_to_gallery' AS migration_id,
  CASE WHEN p0 = 3 OR c0 = 3 THEN 'BLOCKED_PREREQ' ELSE 'DATA_PREVIEW' END AS status,
  '数据搬迁：有品牌形象图的商家须跑附录 C2 预览；DATA_PREVIEW 不是搬迁完成证明' AS detail
FROM (SELECT
    IFNULL((SELECT IF(COUNT(*) = 1, 1, 3) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'mms_merchant' AND engine = 'InnoDB'), 3) AS p0,
    IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'mms_merchant'
         AND column_name IN ('derivatives', 'gallery')) = 2, 4, 3) AS c0) AS n29;

-- [80] migration_merchant_desk_receipt_20261022.sql · 可选 · PRE_DEPLOY
SELECT 80 AS exec_order, 'merchant_desk_receipt_20261022' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 merchant_desk_receipt' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 9 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 12383343389863775861
       AND (SELECT COUNT(*) = 3 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 3217512040811375428 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'merchant_desk_receipt')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'merchant_desk_receipt'), 3) AS c0) AS x10;

-- [81] migration_merchant_redemption_anchor_20261022.sql · 可选 · PRE_DEPLOY
SELECT 81 AS exec_order, 'merchant_redemption_anchor_20261022' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 merchant_redemption_anchor' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 11 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 17232808289079433777
       AND (SELECT COUNT(*) = 2 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 10133716487572001376 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'merchant_redemption_anchor')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'merchant_redemption_anchor'), 3) AS c0) AS x11;

-- [82] migration_merchant_wechat_qrcode_20261022.sql · 必须 · PRE_DEPLOY
SELECT 82 AS exec_order, 'merchant_wechat_qrcode_20261022' AS migration_id,
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
  'wechat_qrcode 结构齐全仍须附录 B4 核对数据；DATA_PREVIEW 不是迁移已完成证明' AS detail;

-- [83] migration_npc_voice_enrollment_intent_20261024.sql · 必须 · PRE_DEPLOY
SELECT 83 AS exec_order, 'npc_voice_enrollment_intent_20261024' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 npc_voice_enrollment_intent（单次执行）' AS detail
FROM (SELECT
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_name IN ('id', 'npc_id', 'scope_type', 'scope_id', 'actor_member_id', 'sample_sha256', 'sample_reference', 'state', 'voice_id', 'reason_code', 'profile_version', 'created_at', 'updated_at')) = 13 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'npc_voice_enrollment_intent') AS c0) AS z11;

-- [84] migration_npc_voice_confirmed_attempt_20261024.sql · 必须 · PRE_DEPLOY
SELECT 84 AS exec_order, 'npc_voice_confirmed_attempt_20261024' AS migration_id,
  CASE WHEN GREATEST(c0, c1, c2, c3) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1, c2, c3) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1, c2, c3) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1, c2, c3) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'npc_voice_enrollment_intent 请求身份 2 列 + 唯一键 + CHECK（单次执行）' AS detail
FROM (SELECT
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'npc_voice_enrollment_intent' AND column_name = 'request_id') AS c0,
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'npc_voice_enrollment_intent' AND column_name = 'previous_intent_id') AS c1,
    (SELECT IF(COUNT(*) > 0, 1, 0) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'npc_voice_enrollment_intent' AND index_name = 'uk_nvei_request') AS c2,
    (SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND constraint_name = 'chk_nvei_request') AS c3) AS z12;

-- [85] migration_o14_city_code_backfill_log_20261022.sql · 可选 · PRE_DEPLOY
SELECT 85 AS exec_order, 'o14_city_code_backfill_log_20261022' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 o14_city_code_backfill_log' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 5 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 7322861083250056029
       AND (SELECT COUNT(*) = 2 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 15028227347182792313 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'o14_city_code_backfill_log')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'o14_city_code_backfill_log'), 3) AS c0) AS x12;

-- [86] migration_o14_cms_activity_city_code_20261022.sql · 可选 · PRE_DEPLOY
SELECT 86 AS exec_order, 'o14_cms_activity_city_code_20261022' AS migration_id,
  CASE WHEN p0 = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 4 THEN 'DATA_PREVIEW' WHEN GREATEST(c0, c1) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'cms_activity.city_code + 索引' AS detail
FROM (SELECT
    IFNULL((SELECT IF(COUNT(*) = 1, 1, 3) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'cms_activity' AND engine = 'InnoDB'), 3) AS p0,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'varchar(32)' AND is_nullable = 'YES' AND column_default IS NULL) = 1 THEN 1 ELSE 2 END
   FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cms_activity' AND column_name = 'city_code'), 3) AS c0,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 1 AND SUM(non_unique = 1) = 1
       AND GROUP_CONCAT(column_name ORDER BY seq_in_index) = 'city_code' THEN 1 ELSE 2 END
   FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'cms_activity' AND index_name = 'idx_cms_activity_city_code'), 3) AS c1) AS x13;

-- [87] migration_o14_cms_topic_city_code_20261022.sql · 必须 · PRE_DEPLOY
SELECT 87 AS exec_order, 'o14_cms_topic_city_code_20261022' AS migration_id,
  CASE WHEN p0 = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 4 THEN 'DATA_PREVIEW' WHEN GREATEST(c0, c1) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'cms_topic.city_code + 索引' AS detail
FROM (SELECT
    IFNULL((SELECT IF(COUNT(*) = 1, 1, 3) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'cms_topic' AND engine = 'InnoDB'), 3) AS p0,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'varchar(32)' AND is_nullable = 'YES' AND column_default IS NULL) = 1 THEN 1 ELSE 2 END
   FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cms_topic' AND column_name = 'city_code'), 3) AS c0,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 1 AND SUM(non_unique = 1) = 1
       AND GROUP_CONCAT(column_name ORDER BY seq_in_index) = 'city_code' THEN 1 ELSE 2 END
   FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'cms_topic' AND index_name = 'idx_cms_topic_city_code'), 3) AS c1) AS x14;

-- [88] migration_o14_content_city_code_backfill_20261022.sql · 需业主授权 · PRE_DEPLOY
SELECT 88 AS exec_order, 'o14_content_city_code_backfill_20261022' AS migration_id,
  CASE WHEN p0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '数据回填:前置列就位后须跑 _precheck_o14_content_city_code_backfill_20261022.sql 预览' AS detail
FROM (SELECT
    IFNULL((SELECT IF(COUNT(*) = 1, 1, 3) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'club' AND engine = 'InnoDB'), 3) AS p0,
    IFNULL((SELECT IF(EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cms_topic' AND column_name = 'city_code') AND EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cms_activity' AND column_name = 'city_code') AND EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'o14_city_code_backfill_log'), 4, 0)), 3) AS c0) AS x15;

-- [89] migration_o14_content_search_document_20261022.sql · 可选 · PRE_DEPLOY
SELECT 89 AS exec_order, 'o14_content_search_document_20261022' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 content_search_document' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 10 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 8667109254857782881
       AND (SELECT COUNT(*) = 7 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 3318759553424233406 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'content_search_document')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'content_search_document'), 3) AS c0) AS x16;

-- [90] migration_20261023_d_official_broadcast_delivery.sql · 必须 · PRE_DEPLOY
SELECT 90 AS exec_order, '20261023_d_official_broadcast_delivery' AS migration_id,
  CASE WHEN pre <> 2 THEN 'BLOCKED_PREREQ'
       WHEN d = 4 AND s = 2 THEN 'APPLIED'
       WHEN d = 0 AND s = 0 AND o = 0 THEN 'NEEDED'
       WHEN d = 0 AND (o > 0 OR s > 0) THEN 'CONFLICT'
       ELSE 'PARTIAL' END AS status,
  '广播 D 协议 6 列；CONFLICT = 10-26 旧协议已单独落库（D 与 union 均不可执行，停下）' AS detail
FROM (SELECT (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('official_broadcast','official_broadcast_log') AND engine = 'InnoDB') AS pre, (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_broadcast_log' AND column_name IN ('delivery_title', 'delivery_sub', 'failure_code', 'reached_time')) AS d, (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_broadcast_log' AND column_name IN ('frozen_title', 'frozen_sub', 'intent_id', 'attempts', 'update_time')) AS o, (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_broadcast_log' AND column_name IN ('delivery_state','message_id')) AS s, (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_broadcast' AND column_name IN ('audience_frozen','client_request_id','request_owner','request_hash')) AS b, (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'official_broadcast_creation') AS cr, (SELECT MAX(column_type) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_broadcast_log' AND column_name = 'delivery_state') AS st) AS z13;

-- [91] migration_20261026_official_broadcast_delivery.sql · 不执行 · PRE_DEPLOY
SELECT 91 AS exec_order, '20261026_official_broadcast_delivery' AS migration_id,
  CASE WHEN pre <> 2 THEN 'BLOCKED_PREREQ'
       WHEN d = 4 AND o = 5 AND b = 4 AND cr = 1 THEN 'APPLIED'
       WHEN d = 0 AND (o > 0 OR b > 0 OR cr > 0) THEN 'CONFLICT'
       ELSE 'REPLACED_UNAPPLIED' END AS status,
  '永不执行（被 D + union 取代）；APPLIED = union 已组合出超集；CONFLICT = 旧协议已单独落库' AS detail
FROM (SELECT (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('official_broadcast','official_broadcast_log') AND engine = 'InnoDB') AS pre, (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_broadcast_log' AND column_name IN ('delivery_title', 'delivery_sub', 'failure_code', 'reached_time')) AS d, (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_broadcast_log' AND column_name IN ('frozen_title', 'frozen_sub', 'intent_id', 'attempts', 'update_time')) AS o, (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_broadcast_log' AND column_name IN ('delivery_state','message_id')) AS s, (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_broadcast' AND column_name IN ('audience_frozen','client_request_id','request_owner','request_hash')) AS b, (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'official_broadcast_creation') AS cr, (SELECT MAX(column_type) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_broadcast_log' AND column_name = 'delivery_state') AS st) AS z14;

-- [92] migration_20261028_official_broadcast_protocol_union.sql · 必须 · PRE_DEPLOY
SELECT 92 AS exec_order, '20261028_official_broadcast_protocol_union' AS migration_id,
  CASE WHEN pre <> 2 THEN 'BLOCKED_PREREQ'
       WHEN d = 4 AND s = 2 AND o = 5 AND b = 4 AND cr = 1 AND st = 'varchar(32)' THEN 'APPLIED'
       WHEN o = 0 AND b = 0 AND cr = 0 AND (d = 4 OR (d = 0 AND s = 0)) THEN 'NEEDED'
       WHEN d = 0 AND (o > 0 OR b > 0 OR cr > 0) THEN 'CONFLICT'
       ELSE 'PARTIAL' END AS status,
  '广播双协议组合（单次执行；只接受 D 前缀）；CONFLICT = 10-26 旧协议已单独落库' AS detail
FROM (SELECT (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('official_broadcast','official_broadcast_log') AND engine = 'InnoDB') AS pre, (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_broadcast_log' AND column_name IN ('delivery_title', 'delivery_sub', 'failure_code', 'reached_time')) AS d, (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_broadcast_log' AND column_name IN ('frozen_title', 'frozen_sub', 'intent_id', 'attempts', 'update_time')) AS o, (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_broadcast_log' AND column_name IN ('delivery_state','message_id')) AS s, (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_broadcast' AND column_name IN ('audience_frozen','client_request_id','request_owner','request_hash')) AS b, (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'official_broadcast_creation') AS cr, (SELECT MAX(column_type) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_broadcast_log' AND column_name = 'delivery_state') AS st) AS z15;

-- [93] migration_orient_geo_ar_team_20261022.sql · 可选 · PRE_DEPLOY
SELECT 93 AS exec_order, 'orient_geo_ar_team_20261022' AS migration_id,
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
    IFNULL((SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_config WHERE config_key = 'orient.safety.enabled'), 3) AS c9) AS x17;

-- [94] migration_orient_score_recovery_20261026.sql · 必须 · PRE_DEPLOY
SELECT 94 AS exec_order, 'orient_score_recovery_20261026' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 orient_score_recovery' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 10 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 887350978483314695
       AND (SELECT COUNT(*) = 5 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 7890895288392942713 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'orient_score_recovery')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'orient_score_recovery'), 3) AS c0) AS n11;

-- [95] migration_orient_score_sync_receipt_20261026.sql · 必须 · PRE_DEPLOY
SELECT 95 AS exec_order, 'orient_score_sync_receipt_20261026' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 orient_score_sync_receipt' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 6 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 419381447761362976
       AND (SELECT COUNT(*) = 2 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 7518263432094455407 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'orient_score_sync_receipt')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'orient_score_sync_receipt'), 3) AS c0) AS n12;

-- [96] migration_20261111_creator_income_detail_idempotency.sql · 可选 · PRE_DEPLOY
SELECT 96 AS step, '20261111_creator_income_detail_idempotency' AS migration_id, '可选' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '迁移自身只读断言' AS detail
FROM (SELECT (IFNULL((SELECT (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema=DATABASE() AND table_name='creator_income_detail'
        AND index_name='uk_creator_income_idempotency' AND non_unique=0 AND column_name='idempotency_key')=1
       AS creator_income_idempotency_ready), 0)) AS present, 1 AS expected) x;

-- [97] migration_o14_reco_search_jobs_20261022.sql · 可选 · POST_DEPLOY
SELECT 97 AS exec_order, 'o14_reco_search_jobs_20261022' AS migration_id,
  CASE WHEN p0 = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 4 THEN 'DATA_PREVIEW' WHEN GREATEST(c0, c1) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'sys_job 2 行(暂停)' AS detail
FROM (SELECT
    IFNULL((SELECT IF(COUNT(*) = 1, 1, 3) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'recommendation_score_daily' AND engine = 'InnoDB'), 3) AS p0,
    IFNULL((SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_job WHERE invoke_target = 'recommendationScoreDailyTask.run()'), 3) AS c0,
    IFNULL((SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_job WHERE invoke_target = 'contentSearchProjectionTask.refresh()'), 3) AS c1) AS x18;

-- [98] migration_marketing_redemption_constraint_name_20261108.sql · 必须 · PRE_DEPLOY
SELECT 98 AS step, 'marketing_redemption_constraint_name_20261108' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, 'CHECK 约束已是新形状' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND table_name = 'redemption' AND constraint_type = 'CHECK' AND constraint_name = 'ck_perk_redemption_status'), 0)) AS present, 1 AS expected) x;

-- [99] migration_marketing_promotion_20261108.sql · 必须 · PRE_DEPLOY
SELECT 99 AS step, 'marketing_promotion_20261108' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '表 marketing_offer、marketing_offer_item、marketing_tier、marketing_promotion、marketing_promotion_redemption、marketing_order_adjustment、marketing_promotion_refund_fact' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'marketing_offer'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'marketing_offer_item'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'marketing_tier'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'marketing_promotion'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'marketing_promotion_redemption'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'marketing_order_adjustment'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'marketing_promotion_refund_fact'), 0)) AS present, 7 AS expected) x;

-- [100] migration_play_arrive_guard_log_20261022.sql · 可选 · PRE_DEPLOY
SELECT 100 AS exec_order, 'play_arrive_guard_log_20261022' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 play_arrive_guard_log' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 12 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 6759212771035083568
       AND (SELECT COUNT(*) = 3 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 15745635712475062580 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'play_arrive_guard_log')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'play_arrive_guard_log'), 3) AS c0) AS x19;

-- [101] migration_pms_sku_booking_20261022.sql · 可选 · PRE_DEPLOY
SELECT 101 AS exec_order, 'pms_sku_booking_20261022' AS migration_id,
  CASE WHEN GREATEST(c0, c1) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 4 THEN 'DATA_PREVIEW' WHEN GREATEST(c0, c1) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 pms_sku_booking_rule / pms_sku_capacity_slot' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 11 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 14842119991963441123
       AND (SELECT COUNT(*) = 4 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 9562436739962736901 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'pms_sku_booking_rule')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'pms_sku_booking_rule'), 3) AS c0,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 12 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 4804660983078453308
       AND (SELECT COUNT(*) = 5 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 6569897251089178890 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'pms_sku_capacity_slot')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'pms_sku_capacity_slot'), 3) AS c1) AS x20;

-- [102] migration_preset_draft_source_20261108.sql · 必须 · PRE_DEPLOY
SELECT 102 AS step, 'preset_draft_source_20261108' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '列 cms_member_template.preset_source' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cms_member_template' AND column_name = 'preset_source'), 0)) AS present, 1 AS expected) x;

-- [103] migration_public_content_catalog_20261108.sql · 不执行 · PRE_DEPLOY
SELECT 103 AS step, 'public_content_catalog_20261108' AS migration_id, '不执行' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '表 content_landing_metadata、content_landing_translation' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'content_landing_metadata'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'content_landing_translation'), 0)) AS present, 2 AS expected) x;

-- [104] migration_public_content_catalog_index_shape_20261109.sql · 必须 · PRE_DEPLOY
SELECT 104 AS step, 'public_content_catalog_index_shape_20261109' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '表 content_landing_metadata、content_landing_translation' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'content_landing_metadata'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'content_landing_translation'), 0)) AS present, 2 AS expected) x;

-- [105] migration_public_content_catalog_node_topic_index_20261108.sql · 必须 · PRE_DEPLOY
SELECT 105 AS step, 'public_content_catalog_node_topic_index_20261108' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '索引 cms_topic_node.idx_topic_node_topic' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) > 0 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'cms_topic_node' AND index_name = 'idx_topic_node_topic'), 0)) AS present, 1 AS expected) x;

-- [106] migration_20261023_advanced_command_fingerprint.sql · 必须 · PRE_DEPLOY
SELECT 106 AS exec_order, '20261023_advanced_command_fingerprint' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'play_attempt_log.request_fingerprint（可重入）' AS detail
FROM (SELECT
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(data_type = 'char' AND character_maximum_length = 64 AND character_set_name = 'ascii' AND collation_name = 'ascii_bin' AND is_nullable = 'YES' AND column_default IS NULL) = 1 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'play_attempt_log' AND column_name = 'request_fingerprint') AS c0) AS z16;

-- [107] migration_20261029_unlist_test_topics.sql · 需业主授权 · PRE_DEPLOY
SELECT 107 AS exec_order, '20261029_unlist_test_topics' AS migration_id,
  CASE WHEN todo > 0 THEN 'DATA_PREVIEW' ELSE 'APPLIED' END AS status,
  '下架 5 个测试主题：DATA_PREVIEW = 仍有可软删的在架目标行；被守卫跳过的行不计入，见附录 C5' AS detail
FROM (SELECT (SELECT COUNT(*) FROM cms_topic t WHERE (t.id, t.name, t.member_id) IN ((990062, '预制人生 · 第一站', 100092), (8, '西安大唐不夜城灯光秀', 100000), (7, '上海沉浸式戏剧', 100000), (6, '成都宽窄巷子辣味食材寻味', 100000), (1, '上海滨江 10KM 骑行', 100086)) AND t.del_flag = 0 AND NOT (EXISTS (SELECT 1 FROM cms_registration r WHERE (r.topic_id = t.id OR (r.owner_type = 1 AND r.owner_id = t.id) OR (r.owner_type = 2 AND r.owner_id IN (SELECT a.id FROM cms_activity a WHERE a.topic_id = t.id))) AND (r.registration_status <> 3 OR r.payment_status IN (1, 2))) OR EXISTS (SELECT 1 FROM player_run_session s WHERE s.topic_id = t.id AND s.run_state <> 'ENDED') OR EXISTS (SELECT 1 FROM topic_route_session s WHERE s.topic_id = t.id AND s.status = 0))) AS todo) AS z28;

-- [108] migration_orient_scoring_20261022.sql · 可选 · PRE_DEPLOY
SELECT 108 AS exec_order, 'orient_scoring_20261022' AS migration_id,
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
    IFNULL((SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_config WHERE config_key = 'orient.scoring.dispute_window_hours'), 3) AS c8) AS x21;

-- [109] migration_orient_session_lifecycle_20261022.sql · 必须 · PRE_DEPLOY
SELECT 109 AS exec_order, 'orient_session_lifecycle_20261022' AS migration_id,
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
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'orient_session_clock'), 3) AS c2) AS x22;

-- [110] migration_photo_check_intent_20261023.sql · 必须 · PRE_DEPLOY
SELECT 110 AS exec_order, 'photo_check_intent_20261023' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 photo_check_intent（单次执行）' AS detail
FROM (SELECT
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_name IN ('id', 'member_id', 'session_id', 'action_key', 'request_fingerprint', 'context_json', 'image_reference', 'reservation', 'original_day', 'nonce', 'state', 'provider_state', 'reason_code', 'encoded_input_sha256', 'model_name', 'prompt_version', 'encoder_version', 'result_pass', 'result_confidence', 'result_reason', 'result_degraded', 'attempt_log_id', 'created_at', 'updated_at')) = 24 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'photo_check_intent') AS c0) AS z17;

-- [111] migration_20261103_settlement_rule_three_percent.sql · 需业主授权 · PRE_DEPLOY
SELECT 111 AS step, '20261103_settlement_rule_three_percent' AS migration_id, '需业主授权' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '迁移自身只读断言' AS detail
FROM (SELECT (IFNULL((SELECT (SELECT COUNT(*) FROM settlement_rule
        WHERE merchant_id = 0 AND biz_type IN ('activity', 'topic', 'topic_club')
          AND status = 1 AND del_flag = 0
          AND NOT (platform_commission_rate = 0.0300 AND merchant_rate = 0.9700 AND creator_rate = 0.0000)) = 0
   AND (SELECT COUNT(*) FROM settlement_rule
        WHERE merchant_id = 0 AND biz_type IN ('activity', 'topic')
          AND status = 1 AND del_flag = 0
          AND platform_commission_rate = 0.0300 AND merchant_rate = 0.9700 AND creator_rate = 0.0000) = 2
   AS settlement_rule_three_percent), 0)) AS present, 1 AS expected) x;

-- [112] migration_20261102_club_checkin_phone_visible.sql · 必须 · PRE_DEPLOY
SELECT 112 AS step, '20261102_club_checkin_phone_visible' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '列 club_role_assignment.customer_phone_visible' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'club_role_assignment' AND column_name = 'customer_phone_visible'), 0)) AS present, 1 AS expected) x;

-- [113] migration_club_host_reservation_20261109.sql · 必须 · PRE_DEPLOY
SELECT 113 AS step, 'club_host_reservation_20261109' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '列 club_event_series.host_reservation_required' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'club_event_series' AND column_name = 'host_reservation_required'), 0)) AS present, 1 AS expected) x;

-- [114] migration_20261030_merchant_game_mechanics.sql · 需业主授权 · PRE_DEPLOY
SELECT 114 AS step, '20261030_merchant_game_mechanics' AS migration_id, '需业主授权' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '迁移自身只读断言' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) = 1 AS assertion_ok
  FROM user_permission
 WHERE role = 'merchant' AND membership_level IS NULL AND can_branch = 1 AND can_game_mechanic = 1), 0)) AS present, 1 AS expected) x;

-- [115] migration_20261104_merchant_activity_limit.sql · 需业主授权 · PRE_DEPLOY
SELECT 115 AS step, '20261104_merchant_activity_limit' AS migration_id, '需业主授权' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '迁移自身只读断言' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) = 1 AS merchant_activity_limit_three
FROM user_permission
WHERE role = 'merchant' AND membership_level IS NULL
  AND can_create_event = 1 AND max_events = 3), 0)) AS present, 1 AS expected) x;

-- [116] migration_member_visited_poi_poi_key_backfill_20261022.sql · 需业主授权 · PRE_DEPLOY
SELECT 116 AS exec_order, 'member_visited_poi_poi_key_backfill_20261022' AS migration_id,
  CASE WHEN p0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '数据回填:poi_key 列就位后须跑附录 B 的只读预览' AS detail
FROM (SELECT
    IFNULL((SELECT IF(COUNT(*) = 1, 1, 3) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'roam_poi' AND engine = 'InnoDB'), 3) AS p0,
    IFNULL((SELECT IF(EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'member_visited_poi' AND column_name = 'poi_key'), 4, 0)), 3) AS c0) AS x23;

-- [117] migration_20261031_roam_session_track.sql · 必须 · PRE_DEPLOY
SELECT 117 AS step, '20261031_roam_session_track' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '列 roam_session.track_json' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'roam_session' AND column_name = 'track_json'), 0)) AS present, 1 AS expected) x;

-- [118] migration_roam_share_version_20261022.sql · 必须 · PRE_DEPLOY
SELECT 118 AS exec_order, 'roam_share_version_20261022' AS migration_id,
  CASE WHEN p0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'roam_share_snapshot.version' AS detail
FROM (SELECT
    IFNULL((SELECT IF(COUNT(*) = 1, 1, 3) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'roam_share_snapshot' AND engine = 'InnoDB'), 3) AS p0,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'int' AND is_nullable = 'NO' AND column_default = '1') = 1 THEN 1 ELSE 2 END
   FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'roam_share_snapshot' AND column_name = 'version'), 3) AS c0) AS x24;

-- [119] migration_20261024_official_roam_challenges.sql · 必须 · PRE_DEPLOY
SELECT 119 AS exec_order, '20261024_official_roam_challenges' AS migration_id,
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
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'varchar(255)' AND is_nullable = 'YES' AND column_default IS NULL) = 1 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'roam_poi_reward_log' AND column_name = 'submitted_answer') AS c4) AS n22;

-- [120] migration_20261025_official_poi_quotes.sql · 必须 · PRE_DEPLOY
SELECT 120 AS exec_order, '20261025_official_poi_quotes' AS migration_id,
  CASE WHEN p0 = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 4 THEN 'DATA_PREVIEW' WHEN GREATEST(c0, c1) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'roam_poi.quotes_json / photo_credit' AS detail
FROM (SELECT
    IFNULL((SELECT IF(COUNT(*) = 1, 1, 3) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'roam_poi' AND engine = 'InnoDB'), 3) AS p0,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'text' AND is_nullable = 'YES' AND column_default IS NULL) = 1 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'roam_poi' AND column_name = 'quotes_json') AS c0,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'text' AND is_nullable = 'YES' AND column_default IS NULL) = 1 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'roam_poi' AND column_name = 'photo_credit') AS c1) AS n23;

-- [121] migration_20261023_g_cash_evidence_expand.sql · 必须 · PRE_DEPLOY
SELECT 121 AS exec_order, '20261023_g_cash_evidence_expand' AS migration_id,
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
    (SELECT IF((SELECT COUNT(*) FROM coop_settlement h LEFT JOIN ums_member m ON m.id = h.merchant_id WHERE h.merchant_id IS NULL OR h.merchant_id <= 0 OR m.id IS NULL OR h.payee_type = 'platform' OR h.line_type IN ('PLATFORM','DEPOSIT_DEDUCTION')) + (SELECT COUNT(*) FROM coop_settlement_adjustment a LEFT JOIN coop_settlement h ON h.id = a.settlement_id WHERE a.claim_id IS NULL OR a.claim_id <= 0 OR h.id IS NULL) = 0, 0, 3)) AS dirty) AS z18;

-- [122] migration_20261022_theme_state_key_attribute_def.sql · 必须 · PRE_DEPLOY
SELECT 122 AS exec_order, '20261022_theme_state_key_attribute_def' AS migration_id,
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
   FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'theme_state_key' AND column_name = 'rules_json'), 3) AS c8) AS x25;

-- [123] migration_20261022_theme_character.sql · 必须 · PRE_DEPLOY
SELECT 123 AS exec_order, '20261022_theme_character' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 theme_character' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 8 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 852904077189281626
       AND (SELECT COUNT(*) = 3 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 44189788068057420 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'theme_character')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'theme_character'), 3) AS c0) AS x26;

-- [124] migration_20261028_d20_attribute_registry.sql · 必须 · PRE_DEPLOY
SELECT 124 AS exec_order, '20261028_d20_attribute_registry' AS migration_id,
  CASE WHEN p0 = 3 THEN 'BLOCKED_PREREQ' WHEN p1 = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 4 THEN 'DATA_PREVIEW' WHEN GREATEST(c0, c1) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'theme_state_key.is_attribute / attribute_preset；同文件回填为 ON DUPLICATE KEY UPDATE，可重跑' AS detail
FROM (SELECT
    IFNULL((SELECT IF(COUNT(*) = 1, 1, 3) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'theme_state_key' AND engine = 'InnoDB'), 3) AS p0,
    IFNULL((SELECT IF(COUNT(*) = 1, 1, 3) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'cms_topic' AND engine = 'InnoDB'), 3) AS p1,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'tinyint(1)' AND is_nullable = 'NO' AND column_default = '0') = 1 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'theme_state_key' AND column_name = 'is_attribute') AS c0,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'varchar(16)' AND is_nullable = 'YES' AND column_default IS NULL) = 1 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'theme_state_key' AND column_name = 'attribute_preset') AS c1) AS n24;

-- [125] migration_20261101_prefab_life_template_copy.sql · 需业主授权 · PRE_DEPLOY
SELECT 125 AS step, '20261101_prefab_life_template_copy' AS migration_id, '需业主授权' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '迁移自身只读断言' AS detail
FROM (SELECT (IFNULL((SELECT (SELECT COUNT(*) FROM cms_topic WHERE id = 990059) = 0
    OR ((SELECT COUNT(*) FROM cms_member_template WHERE id BETWEEN 133 AND 137 AND title LIKE 'PL0%') = 0
        AND (SELECT COUNT(*) FROM cms_member_template WHERE id = 135 AND advanced_config_json LIKE '%hello world%') = 0
        AND (SELECT JSON_UNQUOTE(JSON_EXTRACT(blocks, '$[0].type')) FROM cms_topic_chapter WHERE id = 212) = 'text'
        AND (SELECT COUNT(*) FROM cms_topic_chapter WHERE id = 213 AND blocks LIKE '%一根烟%') = 0
        AND (SELECT COUNT(*) FROM cms_topic WHERE id = 990059 AND description LIKE '%内部试玩版%') = 0) AS prefab_life_copy_fixed), 0)) AS present, 1 AS expected) x;

-- [126] migration_20261101_prefab_life_template_state_keys.sql · 可选 · PRE_DEPLOY
SELECT 126 AS step, '20261101_prefab_life_template_state_keys' AS migration_id, '可选' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '迁移自身只读断言' AS detail
FROM (SELECT (IFNULL((SELECT (
  (SELECT COUNT(*) FROM cms_topic WHERE id = 990059 AND create_by = 'seed_prefab_life_20260918') = 0
  OR (SELECT COUNT(*) FROM theme_state_key WHERE topic_id = 990059
        AND state_key IN ('counter.energy', 'counter.instability') AND key_type = 'counter') = 2
) AS prefab_life_state_keys_ready), 0)) AS present, 1 AS expected) x;

-- [127] migration_topic_location_contract_20261107.sql · 必须 · PRE_DEPLOY
SELECT 127 AS step, 'topic_location_contract_20261107' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '列 cms_topic.content_contract_json' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cms_topic' AND column_name = 'content_contract_json'), 0)) AS present, 1 AS expected) x;

-- [128] migration_20261028_remove_wildlife_theme.sql · 需业主授权 · PRE_DEPLOY
SELECT 128 AS exec_order, '20261028_remove_wildlife_theme' AS migration_id,
  CASE WHEN c0 > 1 THEN 'CONFLICT' WHEN c0 = 1 THEN 'DATA_PREVIEW' ELSE 'APPLIED' END AS status,
  '在架目标行数：1=须先跑 _precheck_remove_wildlife_theme_20261028.sql（引用计数全 0）；0=无事可做' AS detail
FROM (SELECT (SELECT COUNT(*) FROM cms_topic WHERE id BETWEEN 990052 AND 990056 AND name = '野生邻居观察局' AND del_flag = 0) AS c0) AS n30;

-- [129] migration_20261028_template_shelf_list_free_explore.sql · 需业主授权 · PRE_DEPLOY
SELECT 129 AS exec_order, '20261028_template_shelf_list_free_explore' AS migration_id,
  CASE WHEN c1 > 0 THEN 'CONFLICT' WHEN c0 > 0 THEN 'DATA_PREVIEW' ELSE 'APPLIED' END AS status,
  '待上架模板数（>0 先核对 6 个名称各恰好 1 行且 is_template=1）；同名多行判 CONFLICT' AS detail
FROM (SELECT
    (SELECT COUNT(*) FROM cms_topic WHERE del_flag = 0 AND is_template = 1 AND status = 1 AND user_status = 0
      AND name IN ('风味巡游', '不惑之年', '预制人生', '高温末日逃生', '上海像一部旧电影', '生活不掉线')) AS c0,
    (SELECT COUNT(*) FROM (SELECT name FROM cms_topic WHERE del_flag = 0 AND name IN ('风味巡游', '不惑之年', '预制人生', '高温末日逃生', '上海像一部旧电影', '生活不掉线')
      GROUP BY name HAVING COUNT(*) > 1) d) AS c1) AS n31;

-- [130] migration_20261030_merchant_game_covers_to_oss.sql · 需业主授权 · PRE_DEPLOY
SELECT 130 AS step, '20261030_merchant_game_covers_to_oss' AS migration_id, '需业主授权' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '迁移自身只读断言' AS detail
FROM (SELECT (IFNULL((SELECT (
  NOT EXISTS (SELECT 1 FROM cms_template_library WHERE img_url LIKE '%merchant-game-covers%')
  AND NOT EXISTS (SELECT 1 FROM cms_member_template WHERE img_url LIKE '%merchant-game-covers%')
  AND (SELECT COUNT(*) FROM cms_template_library WHERE img_url IN (
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/d8881349a8b12d125eefa6d0b3f8865e.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/7e78a8daffcba3a2c94e03c458b50d5a.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/acd7612ec11ea88bc0e0b25fce626e10.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/363d3554bf573a675755ca1abcfc309b.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/c2034bb011bca6d8c5d27dd9a4a2626e.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/22a004404b88798e87c503293ee5585d.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/baa606560cc76d59bc1fbdaac7d0acdc.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/1bc3ecdf1b75c3b58a5fd02d4bd0529e.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/f1ebe784e730fefa3fdebb48c48f9656.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/8fe329c85e9172be1f759d8c78fd8d00.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/d15ed80bd6ace7ab3fdb78ccaccfae5e.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/f7682e0a27bfb39a718306b15cfc4a33.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/e87e7812f002d3846ff78f97d8386db8.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/0c9b46e2f4549e0cee636549cab1c352.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/2e497a3d84ab87029fa43abec1819c77.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/074fbd63e1a74a7487cb79a0ec60b5e2.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/35bc60793ac37154318c49c1f58b2ae9.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/49c397217cff9ef7709992f942fa3283.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/47e2df8dca943e9d2cf4bd1e586e677a.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/4eb7a1de7f7a73bef3b833a739866576.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/c122f18e626eb647224cd2723f3aafaa.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/2ccf78cf28130b62e4778b4e98801782.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/660817d5c22bdbc4f9c7a49da64353da.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/d355ec00c57c54965de94e28f0125fd5.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/739742f12cd264de1d250011fbf55f64.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/dea4b76bba8684ed4737a9fb95738645.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/7054204bc8082141ca068c9443a9862f.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/44635f8ce9ccb2be03c24420830c18f5.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/285cb232b98dd4a36ed6de332614d532.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/ca9be0c6ee70e647d4d7e1bd7b302dac.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/4e93e2f8bdbf7015bf066bd482bfb1d3.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/b01a09009cb9912161aa27793819d9e0.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/dc42e036bcdc86ac602bd04a9247532a.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/53981dd73de1c3d9dc7fd736a487e289.jpg',
  'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/54079a8c0d978ec61aa270e9b78216a6.jpg')) >= 35
) AS merchant_game_covers_on_oss), 0)) AS present, 1 AS expected) x;

-- [131] migration_20261111_rule_seed_split_steps.sql · 需业主授权 · PRE_DEPLOY
SELECT 131 AS step, '20261111_rule_seed_split_steps' AS migration_id, '需业主授权' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '迁移自身只读断言' AS detail
FROM (SELECT (IFNULL((SELECT (SELECT COUNT(*) FROM cms_template_library WHERE rule_instructions IN ('1. 到达指定门店。2. 向店员说出今日暗号。3. 输入暗号或扫码完成任务,领取隐藏奖励。(运营提示:暗号每天可换一次;商家必须提前知道暗号和奖励,否则现场体验会断)',
  '1. 阅读店铺故事或观察现场线索。2. 回答老板留下的问题。3. 答对后点亮店铺故事徽章。(运营提示:题目必须能从现场找到答案,不要出只有老板知道、玩家猜不到的问题)',
  '1. 找到店内指定拍照点。2. 拍下符合要求的照片。3. 上传照片后完成任务,生成分享素材。(运营提示:拍照要求必须具体,例如「包含店铺招牌」或「拍下今日限定甜品」,不要写「拍一张好看的照片」)',
  '1. 到达合作门店。2. 扫码或由店员核销。3. 点亮风味印章,集满指定数量兑换奖励。(运营提示:必须提前定义集几枚章可兑换什么,建议 3 家给基础奖励,5 家给进阶奖励;二维码在路线/点位层绑定)',
  '1. 到店领取新品体验。2. 完成试吃、试喝或试用。3. 提交你的判断,领取体验官奖励。(运营提示:商家改造时把「正确项」设为最想传达的卖点;如要收集真实偏好,等问卷能力上线)',
  '1. 按任务要求选择商品或套餐组合。2. 拍下你的搭配成果。3. 上传后获得风格称号或专属优惠。(运营提示:商家要提前准备 2-3 套推荐搭配,降低用户选择成本)',
  '1. 到达集合点。2. 找到领队完成签到。3. 领取今日身份,准备出发。(运营提示:默认放在俱乐部主题的第一个节点,解决迟到、散乱、陌生人尴尬;小规模熟人局可改为无需验证)',
  '1. 小队同时出发。2. 在限定时间内到达目标点。3. 完成点亮后进入下一站,最终按完成时间和任务数排名。(运营提示:坐标在路线/点位层绑定;GPS 偏差大的点位可改扫码)',
  '1. 到达指定地点。2. 观察现场细节,找到线索。3. 输入答案,解锁下一段故事。(运营提示:谜题答案必须稳定存在,不要依赖临时海报、会被遮挡的物件或容易变化的菜单)',
  '1. 阅读今日拍摄主题。2. 在指定区域内寻找画面。3. 上传照片,完成取景任务。(运营提示:如做评选,规则要提前说明——按主题契合、构图、故事感评分)',
  '1. 完成本队线索。2. 得到接力暗号。3. 将暗号交给下一队,完成接力。(运营提示:第一版队伍顺序由领队线下控制,小程序负责节点完成和记录)',
  '1. 小队成员抽取角色。2. 按角色分工完成任务。3. 上传证据或回答问题,解锁小队奖励。(运营提示:第一版角色可先线下抽卡,小程序记录「角色任务完成」;系统自动分配角色上线后自动升级)',
  '1. 先观察桌面或展示柜中的招牌线索。2. 启动 60 秒倒计时。3. 在四个选项中选出本店招牌。4. 用时更短的玩家进入本场榜单。商家采用后必须把题目和四个选项改成本店真实商品。',
  '1. 抽取一张风味线索卡。2. 回答两道偏好题。3. 并列时完成最后取舍。4. 获得明亮探索家或醇厚治愈派结果。5. 选择对应体验分支并请店员推荐。结果仅用于本局展示 不写入跨节点偏好标签。商家采用后可改结果文案 但不得把偏好推断为健康或消费能力。',
  '1. 启动五分钟倒计时。2. 从菜单路径或陈列路径选一条。3. 按分支找到隐藏口令。4. 回答最终验证题。商家采用后必须现场走通两条路径 且不能依赖临时海报。',
  '1. 阅读现场安全提示。2. 启动 60 秒倒计时。3. 选出正确做法。4. 按正确完成用时进入榜单。本玩法只做知识反应 不宣称识别动作质量或运动消耗。',
  '1. 依次抽出三轮节奏并点击播放。2. 队员根据听到的节拍轻拍桌面。3. 播放并复刻完一段后再抽下一轮。4. 三轮结束后按完赛用时进榜。模板音频为程序合成击打音 不含歌曲采样。',
  '1. 抽取一个原创故事开场。2. 系统自动分配讲述者与选择者。3. 按角色顺序完成四轮接力。4. 队伍共同选择故事分支并解锁结局。本模板是公开信息的协作接力 不冒充完整隐藏身份推理。',
  '1. 启动三分钟倒计时。2. 观察示例污渍。3. 选择油性或水性线索分支。4. 在最终题中选出稳妥做法。本玩法只做常识互动 不承诺清洁效果 真实贵重衣物应交由专业人员判断。',
  '1. 阅读安全情境 不设置速度奖励。2. 选择电器或燃气分支。3. 完成避险顺序。4. 回答最终安全题。本玩法不远程诊断真实故障 遇到危险应立即离开并联系当地紧急服务或合格专业人员。',
  '1. 向店员领取盲品小样。2. 闭眼尝。3. 睁眼在四个选项中作答。4. 答错可以再答 答对才给分。商家采用后必须把四个选项和正确答案改成本店真实商品 答案由服务端保管 不会下发给玩家。',
  '1. 全程不开口 用比划 眼神或演技表达你要的东西。2. 开口即失败 由玩家自己认输。3. 店员猜中后扫玩家出示的见证码完成核销。商家采用后请先和店员说明规则 高峰时段不建议开启。',
  '1. 用店里现有的东西配出你的作品。2. 拍一张照片。3. 给它起一个名字并提交。名字会公开展示 提交前会做内容安全检测。商家采用后可把备选名改成本店风格 并决定是否把作品上今日推荐。',
  '1. 找个位置坐下。2. 播放店主的歌单。3. 听完。本玩法不判定通关 也不给奖励分 它只是让你在这一站停一会儿。商家采用后请填入曲目地址 并确认已获得播放授权。',
  '1. 只有在开放时段内才能开局 时段由服务端判定。2. 不在时段内会看到倒计时和订阅提醒。3. 时段内进入即可开始。商家采用后必须把开放时段改成本店真实营业时间 跨夜时段(如 23:00 到 01:00)是允许的。',
  '1. 授权微信运动。2. 刷新步数 步数由服务端解密微信运动数据得到。3. 达到目标步数即落章。客户端自己填的步数不作数。商家采用后可按线路长度调整目标步数 建议 3000 到 8000 之间。',
  '1. 到达后领取今日城市签。2. 一天只有一条 由服务端按日期选定。3. 同一天重复领会拿到同一条 不会换。商家采用后可把签文改成本店口吻 每条 1 至 4 行 每行不超过 24 字。',
  '1. 从外白渡桥起步，沿苏州河向西。2. 每到一座桥，拍下桥头的桥名牌。3. 走累了随时收工，下次接着走。4. 九张集齐视为完成。全程走人行道，不翻护栏，不在桥面车行道一侧停留拍摄。',
  '1. 进馆后先不看导览，随便走到哪算哪。2. 选一件让你自己停下来的作品。3. 坐下或站定看满十分钟，中途不看手机。4. 拍下展签，写一句话。展厅拍摄请遵守场馆规定，禁拍区域不拍。',
  '1. 每家店只做一件事：找一本你没听过的书。2. 站着读完前三页。3. 拍下封面，放回原位或结账带走，都算完成。4. 五家店集满五张。请遵守各店的拍照与试读规定，不拆封、不长时间占用座位。',
  '1. 早上七点半到八点半之间到场。2. 找三样你叫不出名字的时令菜，各拍一张。3. 挑其中一样，问摊主怎么做。4. 买一样今天就吃得掉的东西收工。摊主在做生意，问一句就好，别长时间占着摊位。',
  '1. 天黑后到达，手机就够。2. 拍第一张：完全不含人的建筑局部。3. 拍第二张：有陌生人但看不清脸的街景。4. 拍第三张：你自己的影子。5. 选一张留下，另外两张当场删掉——这一步才是任务本身。不为取景越过围栏或站上台阶边沿。',
  '1. 出发前把配速播报关掉。2. 沿武康路、五原路、安福路跑一圈，约五公里。3. 想停就停，遇到好看的路口可以走过去。4. 回到武康大楼路口拍一张，记下当时第一个念头。人行道行人多，请降速避让、礼让行人。',
  '1. 从龙美术馆前广场出发，沿骑行道往南骑约七点五公里。2. 找一处江边台阶停下。3. 待够十分钟，不刷手机、不听播客，什么都不做。4. 拍一张你面前的江面，然后原路折返。遵守骑行道限速与单向指引。',
  '1. 只走公共街面，不进私人院落。2. 找那些几乎不会被拍到的细节：旧门牌编号、砖缝里的植物、被漆盖过的招牌、修过三次的排水口。3. 找到就拍，凑满十张结束。这一带住着人：请压低音量，不拍摄居民与室内，遇施工围挡绕行。')) = 0
   AND (SELECT COUNT(*) FROM cms_member_template WHERE rule_instructions IN ('1. 到达指定门店。2. 向店员说出今日暗号。3. 输入暗号或扫码完成任务,领取隐藏奖励。(运营提示:暗号每天可换一次;商家必须提前知道暗号和奖励,否则现场体验会断)',
  '1. 阅读店铺故事或观察现场线索。2. 回答老板留下的问题。3. 答对后点亮店铺故事徽章。(运营提示:题目必须能从现场找到答案,不要出只有老板知道、玩家猜不到的问题)',
  '1. 找到店内指定拍照点。2. 拍下符合要求的照片。3. 上传照片后完成任务,生成分享素材。(运营提示:拍照要求必须具体,例如「包含店铺招牌」或「拍下今日限定甜品」,不要写「拍一张好看的照片」)',
  '1. 到达合作门店。2. 扫码或由店员核销。3. 点亮风味印章,集满指定数量兑换奖励。(运营提示:必须提前定义集几枚章可兑换什么,建议 3 家给基础奖励,5 家给进阶奖励;二维码在路线/点位层绑定)',
  '1. 到店领取新品体验。2. 完成试吃、试喝或试用。3. 提交你的判断,领取体验官奖励。(运营提示:商家改造时把「正确项」设为最想传达的卖点;如要收集真实偏好,等问卷能力上线)',
  '1. 按任务要求选择商品或套餐组合。2. 拍下你的搭配成果。3. 上传后获得风格称号或专属优惠。(运营提示:商家要提前准备 2-3 套推荐搭配,降低用户选择成本)',
  '1. 到达集合点。2. 找到领队完成签到。3. 领取今日身份,准备出发。(运营提示:默认放在俱乐部主题的第一个节点,解决迟到、散乱、陌生人尴尬;小规模熟人局可改为无需验证)',
  '1. 小队同时出发。2. 在限定时间内到达目标点。3. 完成点亮后进入下一站,最终按完成时间和任务数排名。(运营提示:坐标在路线/点位层绑定;GPS 偏差大的点位可改扫码)',
  '1. 到达指定地点。2. 观察现场细节,找到线索。3. 输入答案,解锁下一段故事。(运营提示:谜题答案必须稳定存在,不要依赖临时海报、会被遮挡的物件或容易变化的菜单)',
  '1. 阅读今日拍摄主题。2. 在指定区域内寻找画面。3. 上传照片,完成取景任务。(运营提示:如做评选,规则要提前说明——按主题契合、构图、故事感评分)',
  '1. 完成本队线索。2. 得到接力暗号。3. 将暗号交给下一队,完成接力。(运营提示:第一版队伍顺序由领队线下控制,小程序负责节点完成和记录)',
  '1. 小队成员抽取角色。2. 按角色分工完成任务。3. 上传证据或回答问题,解锁小队奖励。(运营提示:第一版角色可先线下抽卡,小程序记录「角色任务完成」;系统自动分配角色上线后自动升级)',
  '1. 先观察桌面或展示柜中的招牌线索。2. 启动 60 秒倒计时。3. 在四个选项中选出本店招牌。4. 用时更短的玩家进入本场榜单。商家采用后必须把题目和四个选项改成本店真实商品。',
  '1. 抽取一张风味线索卡。2. 回答两道偏好题。3. 并列时完成最后取舍。4. 获得明亮探索家或醇厚治愈派结果。5. 选择对应体验分支并请店员推荐。结果仅用于本局展示 不写入跨节点偏好标签。商家采用后可改结果文案 但不得把偏好推断为健康或消费能力。',
  '1. 启动五分钟倒计时。2. 从菜单路径或陈列路径选一条。3. 按分支找到隐藏口令。4. 回答最终验证题。商家采用后必须现场走通两条路径 且不能依赖临时海报。',
  '1. 阅读现场安全提示。2. 启动 60 秒倒计时。3. 选出正确做法。4. 按正确完成用时进入榜单。本玩法只做知识反应 不宣称识别动作质量或运动消耗。',
  '1. 依次抽出三轮节奏并点击播放。2. 队员根据听到的节拍轻拍桌面。3. 播放并复刻完一段后再抽下一轮。4. 三轮结束后按完赛用时进榜。模板音频为程序合成击打音 不含歌曲采样。',
  '1. 抽取一个原创故事开场。2. 系统自动分配讲述者与选择者。3. 按角色顺序完成四轮接力。4. 队伍共同选择故事分支并解锁结局。本模板是公开信息的协作接力 不冒充完整隐藏身份推理。',
  '1. 启动三分钟倒计时。2. 观察示例污渍。3. 选择油性或水性线索分支。4. 在最终题中选出稳妥做法。本玩法只做常识互动 不承诺清洁效果 真实贵重衣物应交由专业人员判断。',
  '1. 阅读安全情境 不设置速度奖励。2. 选择电器或燃气分支。3. 完成避险顺序。4. 回答最终安全题。本玩法不远程诊断真实故障 遇到危险应立即离开并联系当地紧急服务或合格专业人员。',
  '1. 向店员领取盲品小样。2. 闭眼尝。3. 睁眼在四个选项中作答。4. 答错可以再答 答对才给分。商家采用后必须把四个选项和正确答案改成本店真实商品 答案由服务端保管 不会下发给玩家。',
  '1. 全程不开口 用比划 眼神或演技表达你要的东西。2. 开口即失败 由玩家自己认输。3. 店员猜中后扫玩家出示的见证码完成核销。商家采用后请先和店员说明规则 高峰时段不建议开启。',
  '1. 用店里现有的东西配出你的作品。2. 拍一张照片。3. 给它起一个名字并提交。名字会公开展示 提交前会做内容安全检测。商家采用后可把备选名改成本店风格 并决定是否把作品上今日推荐。',
  '1. 找个位置坐下。2. 播放店主的歌单。3. 听完。本玩法不判定通关 也不给奖励分 它只是让你在这一站停一会儿。商家采用后请填入曲目地址 并确认已获得播放授权。',
  '1. 只有在开放时段内才能开局 时段由服务端判定。2. 不在时段内会看到倒计时和订阅提醒。3. 时段内进入即可开始。商家采用后必须把开放时段改成本店真实营业时间 跨夜时段(如 23:00 到 01:00)是允许的。',
  '1. 授权微信运动。2. 刷新步数 步数由服务端解密微信运动数据得到。3. 达到目标步数即落章。客户端自己填的步数不作数。商家采用后可按线路长度调整目标步数 建议 3000 到 8000 之间。',
  '1. 到达后领取今日城市签。2. 一天只有一条 由服务端按日期选定。3. 同一天重复领会拿到同一条 不会换。商家采用后可把签文改成本店口吻 每条 1 至 4 行 每行不超过 24 字。',
  '1. 从外白渡桥起步，沿苏州河向西。2. 每到一座桥，拍下桥头的桥名牌。3. 走累了随时收工，下次接着走。4. 九张集齐视为完成。全程走人行道，不翻护栏，不在桥面车行道一侧停留拍摄。',
  '1. 进馆后先不看导览，随便走到哪算哪。2. 选一件让你自己停下来的作品。3. 坐下或站定看满十分钟，中途不看手机。4. 拍下展签，写一句话。展厅拍摄请遵守场馆规定，禁拍区域不拍。',
  '1. 每家店只做一件事：找一本你没听过的书。2. 站着读完前三页。3. 拍下封面，放回原位或结账带走，都算完成。4. 五家店集满五张。请遵守各店的拍照与试读规定，不拆封、不长时间占用座位。',
  '1. 早上七点半到八点半之间到场。2. 找三样你叫不出名字的时令菜，各拍一张。3. 挑其中一样，问摊主怎么做。4. 买一样今天就吃得掉的东西收工。摊主在做生意，问一句就好，别长时间占着摊位。',
  '1. 天黑后到达，手机就够。2. 拍第一张：完全不含人的建筑局部。3. 拍第二张：有陌生人但看不清脸的街景。4. 拍第三张：你自己的影子。5. 选一张留下，另外两张当场删掉——这一步才是任务本身。不为取景越过围栏或站上台阶边沿。',
  '1. 出发前把配速播报关掉。2. 沿武康路、五原路、安福路跑一圈，约五公里。3. 想停就停，遇到好看的路口可以走过去。4. 回到武康大楼路口拍一张，记下当时第一个念头。人行道行人多，请降速避让、礼让行人。',
  '1. 从龙美术馆前广场出发，沿骑行道往南骑约七点五公里。2. 找一处江边台阶停下。3. 待够十分钟，不刷手机、不听播客，什么都不做。4. 拍一张你面前的江面，然后原路折返。遵守骑行道限速与单向指引。',
  '1. 只走公共街面，不进私人院落。2. 找那些几乎不会被拍到的细节：旧门牌编号、砖缝里的植物、被漆盖过的招牌、修过三次的排水口。3. 找到就拍，凑满十张结束。这一带住着人：请压低音量，不拍摄居民与室内，遇施工围挡绕行。')) = 0 AS rule_seed_split_done), 0)) AS present, 1 AS expected) x;

-- [132] migration_20261112_template_usage_count.sql · 必须 · PRE_DEPLOY
SELECT 132 AS step, '20261112_template_usage_count' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '迁移自身只读断言' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*)=1 FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name='cms_template_library' AND column_name='use_num'
 AND data_type='int' AND is_nullable='NO' AND column_default='0'
 AND column_comment='D89 distinct first adopters'), 0)) AS present, 1 AS expected) x;

-- [133] migration_20261112_template_market_status.sql · 必须 · PRE_DEPLOY
SELECT 133 AS step, '20261112_template_market_status' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '迁移自身只读断言' AS detail
FROM (SELECT (IFNULL((SELECT (SELECT COUNT(*)=0 FROM information_schema.columns WHERE table_schema=DATABASE()
 AND table_name='cms_template_library' AND column_name='publish_status')
 AND (SELECT COUNT(*)=1 FROM information_schema.columns WHERE table_schema=DATABASE()
 AND table_name='cms_template_library' AND column_name='status'
 AND column_comment='D90 market state 0 pending 1 live 2 rejected 3 approved-offline')
 AND (SELECT COUNT(*)=0 FROM cms_template_library WHERE status IS NULL OR status NOT IN (0,1,2,3))), 0)) AS present, 1 AS expected) x;

-- [134] migration_20261112_template_author_title_unique.sql · 必须 · PRE_DEPLOY
SELECT 134 AS step, '20261112_template_author_title_unique' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '迁移自身只读断言' AS detail
FROM (SELECT (IFNULL((SELECT (SELECT COUNT(*)=1 FROM information_schema.columns WHERE table_schema=DATABASE()
 AND table_name='cms_template_library' AND column_name='active_title_key'
 AND data_type='varbinary' AND character_maximum_length=1024 AND extra='STORED GENERATED'
 AND generation_expression LIKE '%regexp_replace%')
 AND (SELECT COUNT(*)=2 AND SUM(non_unique=0 AND
  ((seq_in_index=1 AND column_name='member_id') OR (seq_in_index=2 AND column_name='active_title_key')))=2
 FROM information_schema.statistics WHERE table_schema=DATABASE()
 AND table_name='cms_template_library' AND index_name='uk_template_author_live_title')), 0)) AS present, 1 AS expected) x;

-- [135] migration_verification_review_evidence_20261022.sql · 可选 · PRE_DEPLOY
SELECT 135 AS exec_order, 'verification_review_evidence_20261022' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 verification_review_evidence' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 8 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 14781193881360100961
       AND (SELECT COUNT(*) = 5 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 17353113222738040501 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'verification_review_evidence')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'verification_review_evidence'), 3) AS c0) AS x27;

-- [136] migration_video_bounty_host_tip_20261108.sql · 必须 · PRE_DEPLOY
SELECT 136 AS step, 'video_bounty_host_tip_20261108' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '表 video_bounty_campaign、video_bounty_submission、video_bounty_evidence、video_bounty_accrual、host_tip_config、host_tip_intent' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'video_bounty_campaign'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'video_bounty_submission'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'video_bounty_evidence'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'video_bounty_accrual'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'host_tip_config'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'host_tip_intent'), 0)) AS present, 6 AS expected) x;

-- [137] migration_video_bounty_group_verification_20261108.sql · 必须 · PRE_DEPLOY
SELECT 137 AS step, 'video_bounty_group_verification_20261108' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '列 video_bounty_submission.verified_group_key、video_bounty_submission.verified_by、video_bounty_submission.verified_at、video_bounty_submission.verified_source_ref、video_bounty_submission.verified_group_version；索引 video_bounty_submission.idx_vbs_verified_group' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'video_bounty_submission' AND column_name = 'verified_group_key'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'video_bounty_submission' AND column_name = 'verified_by'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'video_bounty_submission' AND column_name = 'verified_at'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'video_bounty_submission' AND column_name = 'verified_source_ref'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'video_bounty_submission' AND column_name = 'verified_group_version'), 0) + IFNULL((SELECT COUNT(*) > 0 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'video_bounty_submission' AND index_name = 'idx_vbs_verified_group'), 0)) AS present, 6 AS expected) x;

-- [138] migration_20261025_coop_order_station_fulfillment.sql · 必须 · PRE_DEPLOY
SELECT 138 AS exec_order, '20261025_coop_order_station_fulfillment' AS migration_id,
  CASE WHEN GREATEST(c0, c1, c2, c3) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1, c2, c3) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1, c2, c3) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1, c2, c3) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 coop_order_station_fulfillment + coop_invite.supply_station_id + 2 触发器（单次执行）' AS detail
FROM (SELECT
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_name IN ('id', 'order_id', 'topic_id', 'beneficiary_member_id', 'registration_id', 'station_id', 'verification_audit_id', 'sales_basis_amount', 'order_terms_sha256', 'source_fingerprint', 'fulfilled_at', 'recorded_at')) = 12 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'coop_order_station_fulfillment') AS c0,
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'coop_invite' AND column_name = 'supply_station_id') AS c1,
    (SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema = DATABASE() AND trigger_name = 'tr_corsf_immutable_update') AS c2,
    (SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema = DATABASE() AND trigger_name = 'tr_corsf_immutable_delete') AS c3) AS z19;

-- [139] migration_w8_refund_fulfillment_recovery_20261026.sql · 可选 · PRE_DEPLOY
SELECT 139 AS exec_order, 'w8_refund_fulfillment_recovery_20261026' AS migration_id,
  CASE WHEN p0 = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 4 THEN 'DATA_PREVIEW' WHEN GREATEST(c0, c1) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'refund_application.fulfillment_sync_queue_at + 恢复队列索引' AS detail
FROM (SELECT
    IFNULL((SELECT IF(COUNT(*) = 1, 1, 3) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'refund_application' AND engine = 'InnoDB'), 3) AS p0,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'datetime(6)' AND is_nullable = 'NO' AND column_default = 'CURRENT_TIMESTAMP(6)') = 1 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'refund_application' AND column_name = 'fulfillment_sync_queue_at') AS c0,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 4 AND SUM(non_unique = 1 AND ((seq_in_index = 1 AND column_name = 'source_type') OR (seq_in_index = 2 AND column_name = 'del_flag') OR (seq_in_index = 3 AND column_name = 'fulfillment_sync_queue_at') OR (seq_in_index = 4 AND column_name = 'id'))) = 4 THEN 1 ELSE 2 END FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'refund_application' AND index_name = 'idx_refund_fulfillment_recovery') AS c1) AS n25;

-- [140] migration_20261026_user_invoice_menu.sql · 可选 · PRE_DEPLOY
SELECT 140 AS exec_order, '20261026_user_invoice_menu' AS migration_id,
  CASE WHEN p0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 2 THEN 'CONFLICT' WHEN c0 = 1 THEN 'APPLIED'
       WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'sys_menu 用户发票处理 C 菜单 + 4 个 F 按钮；父菜单 funds-aftercare 须恰好 1 行；PARTIAL 可重跑（逐行 NOT EXISTS + 形状守卫）' AS detail
FROM (SELECT
    (SELECT IF(COUNT(*) = 1, 1, 3) FROM sys_menu WHERE menu_type = 'M' AND path = 'funds-aftercare') AS p0,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) > COUNT(DISTINCT perms) OR SUM(NOT ((perms = 'business:userInvoice:list' AND menu_type <=> 'C' AND component <=> 'business/userInvoice/index' AND route_name <=> 'UserInvoiceProcessing' AND path <=> 'userInvoice' AND parent_id <=> (SELECT MIN(menu_id) FROM sys_menu WHERE menu_type = 'M' AND path = 'funds-aftercare')) OR (perms <> 'business:userInvoice:list' AND menu_type <=> 'F' AND parent_id <=> (SELECT MIN(menu_id) FROM sys_menu WHERE perms = 'business:userInvoice:list')))) > 0 THEN 2 WHEN COUNT(*) = 5 THEN 1 ELSE 5 END FROM sys_menu WHERE perms IN ('business:userInvoice:list', 'business:userInvoice:query', 'business:userInvoice:taxView', 'business:userInvoice:process', 'business:userInvoice:reconcile')) AS c0) AS n26;

-- [141] migration_20261013_admin_role_page_grants.sql · 必须 · PRE_DEPLOY
SELECT 141 AS exec_order, '20261013_admin_role_page_grants' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'APPLIED = 4 个后台角色均已有页面授权(本迁移 no-op);NEEDED = 有角色无页面授权,禁止执行,交 owner(见 pack D-PAYEE 节);checksum 对账见附录 A' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(DISTINCT r.role_key) < 4 THEN 3
   WHEN SUM(EXISTS (SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id = rm.menu_id
     WHERE rm.role_id = r.role_id AND m.menu_type = 'C')) = COUNT(*) THEN 1 ELSE 0 END
   FROM sys_role r WHERE r.del_flag = '0' AND r.role_key IN ('cy_operator', 'cy_auditor', 'cy_support', 'cy_finance')), 3) AS c0) AS x28;

-- [142] migration_20261016_admin_role_payee_separation_forward.sql · 必须 · PRE_DEPLOY
SELECT 142 AS exec_order, '20261016_admin_role_payee_separation_forward' AS migration_id,
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
     WHERE r.del_flag = '0' AND r.role_key = 'cy_finance' AND m.perms = 'business:clubpayee:payout') = 0, 1, 0)), 3) AS c0) AS x29;

-- [143] migration_20261027_orient_scoring_menu_grants.sql · 可选 · PRE_DEPLOY
SELECT 143 AS exec_order, '20261027_orient_scoring_menu_grants' AS migration_id,
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
                  WHERE x.role_id = r.role_id AND x.menu_id = m.menu_id)) THEN 1 ELSE 5 END) AS c1) AS n27;

-- [144] migration_20261104_review_wording_seeded_text.sql · 需业主授权 · PRE_DEPLOY
SELECT 144 AS step, '20261104_review_wording_seeded_text' AS migration_id, '需业主授权' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '迁移自身只读断言' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) = 0 AS seeded_job_text_renamed
FROM sys_job
WHERE invoke_target = 'predictVoidExpiredTask.run()'
  AND (job_name = '竞猜轮次超时作废' COLLATE utf8mb4_bin OR remark = '商家超过48小时不给竞猜答案的轮次作废且不发奖;自收敛任务,无过期轮次时空跑' COLLATE utf8mb4_bin)), 0)) AS present, 1 AS expected) x;

-- [145] migration_20261109_play_help_audit_permission.sql · 必须 · PRE_DEPLOY
SELECT 145 AS step, '20261109_play_help_audit_permission' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '后台按钮权限 business:playhelp:query（零角色授权）' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) = 1 FROM sys_menu WHERE perms = 'business:playhelp:query' AND menu_type = 'F'), 0)) AS present, 1 AS expected) x;

-- [146] migration_20261109_questo_admin_menus.sql · 必须 · PRE_DEPLOY
SELECT 146 AS step, '20261109_questo_admin_menus' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '菜单行 12 个' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) > 0 FROM sys_menu WHERE perms = 'business:contentlanding:review'), 0) + IFNULL((SELECT COUNT(*) > 0 FROM sys_menu WHERE perms = 'business:hosttip:audit'), 0) + IFNULL((SELECT COUNT(*) > 0 FROM sys_menu WHERE perms = 'business:marketing:channel:issue'), 0) + IFNULL((SELECT COUNT(*) > 0 FROM sys_menu WHERE perms = 'business:marketing:channel:list'), 0) + IFNULL((SELECT COUNT(*) > 0 FROM sys_menu WHERE perms = 'business:marketing:channel:manage'), 0) + IFNULL((SELECT COUNT(*) > 0 FROM sys_menu WHERE perms = 'business:marketing:channel:reconcile'), 0) + IFNULL((SELECT COUNT(*) > 0 FROM sys_menu WHERE perms = 'business:marketing:channel:revoke'), 0) + IFNULL((SELECT COUNT(*) > 0 FROM sys_menu WHERE perms = 'business:marketing:channel:verify'), 0) + IFNULL((SELECT COUNT(*) > 0 FROM sys_menu WHERE perms = 'business:revenue:list'), 0) + IFNULL((SELECT COUNT(*) > 0 FROM sys_menu WHERE perms = 'business:videobounty:config'), 0) + IFNULL((SELECT COUNT(*) > 0 FROM sys_menu WHERE perms = 'business:videobounty:review'), 0) + IFNULL((SELECT COUNT(*) > 0 FROM sys_menu WHERE component = 'business/analytics/postgame'), 0)) AS present, 12 AS expected) x;

-- [147] migration_20261023_g_wp08_delivery_jobs_paused.sql · 需业主授权 · POST_DEPLOY
SELECT 147 AS exec_order, '20261023_g_wp08_delivery_jobs_paused' AS migration_id,
  CASE WHEN GREATEST(c0, c1) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'sys_job 两个暂停的补偿任务（20261013 种子已登记时为 APPLIED）' AS detail
FROM (SELECT
    (SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_job WHERE CAST(TRIM(invoke_target) AS BINARY) = CAST('imNotificationDeliveryTask.deliverPending()' AS BINARY)) AS c0,
    (SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_job WHERE CAST(TRIM(invoke_target) AS BINARY) = CAST('roamRewardDeliveryTask.run()' AS BINARY)) AS c1) AS z20;

-- [148] migration_club_post_media_review_budget_20261024.sql · 必须 · PRE_DEPLOY
SELECT 148 AS exec_order, 'club_post_media_review_budget_20261024' AS migration_id,
  CASE WHEN t <> 1 THEN 'BLOCKED_PREREQ' WHEN s IS NULL THEN 'CONFLICT'
       WHEN s LIKE '%orstate=5or%|%orstate=5or%' THEN 'APPLIED' WHEN s LIKE '%if%|%if%' THEN 'NEEDED' ELSE 'CONFLICT' END AS status,
  '帖子媒体审核外发 CHECK 允许 state=5（可重入）' AS detail
FROM (SELECT (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'club_post_media_review_outbox' AND engine = 'InnoDB') AS t, (SELECT GROUP_CONCAT(REGEXP_REPLACE(LOWER(cc.check_clause), '[[:space:]`()]', '') ORDER BY cc.constraint_name SEPARATOR '|') FROM information_schema.check_constraints cc JOIN information_schema.table_constraints tc ON tc.constraint_schema = cc.constraint_schema AND tc.constraint_name = cc.constraint_name WHERE cc.constraint_schema = DATABASE() AND tc.table_name = 'club_post_media_review_outbox' AND cc.constraint_name IN ('chk_club_post_media_review_owner','chk_club_post_media_review_trace')) AS s) AS z21;

-- [149] migration_20261024_wx_verified_payment_fact.sql · 必须 · PRE_DEPLOY
SELECT 149 AS exec_order, '20261024_wx_verified_payment_fact' AS migration_id,
  CASE WHEN GREATEST(c0, c1, c2) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1, c2) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1, c2) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1, c2) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 wx_verified_payment_fact / _rejection / wx_payment_callback_fact_link（单次执行）' AS detail
FROM (SELECT
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_name IN ('id', 'fact_kind', 'mchid', 'appid', 'business_out_trade_no', 'wx_out_trade_no', 'merchant_event_no', 'provider_id', 'transaction_id', 'trade_type', 'currency', 'total_fen', 'amount_fen', 'success_at', 'intent_request_hash', 'fact_hash', 'verification_version', 'verification_outcome', 'verification_method', 'original_payment_state', 'created_at')) = 21 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'wx_verified_payment_fact') AS c0,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_name IN ('id', 'rejection_key', 'rejection_reason', 'original_fact_id', 'candidate_fact_hash', 'notify_hash', 'candidate_facts', 'created_at')) = 8 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'wx_verified_payment_fact_rejection') AS c1,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_name IN ('inbox_id', 'notify_hash', 'fact_id', 'fact_hash', 'created_at')) = 5 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'wx_payment_callback_fact_link') AS c2) AS z22;

-- [150] migration_20261024_clm_commerce_payment_proof.sql · 必须 · PRE_DEPLOY
SELECT 150 AS exec_order, '20261024_clm_commerce_payment_proof' AS migration_id,
  CASE WHEN GREATEST(c0, c1, c2, c3) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1, c2, c3) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1, c2, c3) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1, c2, c3) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '会员发票 / 商城订单支付证明 2+2 列（单次执行）' AS detail
FROM (SELECT
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'club_membership_invoice' AND column_name = 'verified_payment_fact_id') AS c0,
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'club_membership_invoice' AND column_name = 'verified_payment_fact_hash') AS c1,
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'oms_order' AND column_name = 'verified_payment_fact_id') AS c2,
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'oms_order' AND column_name = 'verified_payment_fact_hash') AS c3) AS z23;

-- [151] migration_20261024_coop_original_refund_components.sql · 必须 · PRE_DEPLOY
SELECT 151 AS exec_order, '20261024_coop_original_refund_components' AS migration_id,
  CASE WHEN GREATEST(c0, c1, c2) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1, c2) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1, c2) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1, c2) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 coop_settlement_original_component / refund_receipt / original_adjustment（单次执行）' AS detail
FROM (SELECT
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_name IN ('id', 'cash_close_id', 'topic_id', 'source_registration_id', 'payer_registration_id', 'settlement_id', 'source_claim_id', 'payment_fact_id', 'payment_fact_hash', 'receiver_kind', 'receiver_member_id', 'line_type', 'original_amount', 'original_order_cash', 'calculation_version', 'source_fingerprint', 'created_at')) = 17 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'coop_settlement_original_component') AS c0,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_name IN ('id', 'cash_close_id', 'topic_id', 'refund_id', 'verified_fact_id', 'verified_fact_kind', 'verified_fact_hash', 'wx_fact_id', 'source_registration_id', 'payer_registration_id', 'currency', 'refund_amount', 'total_amount', 'cumulative_before', 'cumulative_after', 'reversed_amount', 'calculation_version', 'source_fingerprint', 'created_at')) = 19 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'coop_settlement_refund_receipt') AS c1,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_name IN ('id', 'cash_close_id', 'component_id', 'receipt_id', 'settlement_id', 'payer_registration_id', 'amount', 'head_was_paid', 'wallet_debit', 'debt_amount', 'created_at')) = 11 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'coop_settlement_original_adjustment') AS c2) AS z24;

-- [152] migration_20261024_coop_negative_margin_manual.sql · 必须 · PRE_DEPLOY
SELECT 152 AS exec_order, '20261024_coop_negative_margin_manual' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 coop_settlement_manual_case（单次执行）' AS detail
FROM (SELECT
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_name IN ('id', 'case_key', 'topic_id', 'reason', 'negative_amount', 'source_snapshot', 'state', 'created_at')) = 8 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'coop_settlement_manual_case') AS c0) AS z25;

-- [153] migration_20261024_registration_payment_fact.sql · 必须 · PRE_DEPLOY
SELECT 153 AS exec_order, '20261024_registration_payment_fact' AS migration_id,
  CASE WHEN GREATEST(c0, c1, c2) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1, c2) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1, c2) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1, c2) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 cms_registration_payment_fact + 两个只追加触发器（单次执行）' AS detail
FROM (SELECT
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_name IN ('registration_id', 'registration_no', 'payment_fact_id', 'payment_fact_hash', 'transaction_id', 'consumed_at')) = 6 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cms_registration_payment_fact') AS c0,
    (SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema = DATABASE() AND trigger_name = 'registration_paid_fact_no_update') AS c1,
    (SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema = DATABASE() AND trigger_name = 'registration_paid_fact_no_delete') AS c2) AS z26;

-- [154] migration_20261024_wx_success_fact_business_index.sql · 必须 · PRE_DEPLOY
SELECT 154 AS exec_order, '20261024_wx_success_fact_business_index' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'wx_verified_payment_fact 业务单号索引（单次执行）' AS detail
FROM (SELECT
    (SELECT IF(COUNT(*) > 0, 1, 0) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'wx_verified_payment_fact' AND index_name = 'ix_wx_fact_business') AS c0) AS z27;

-- [155] migration_20261112_dec_c_payout.sql · 必须 · POST_DEPLOY
SELECT 155 AS step, '20261112_dec_c_payout' AS migration_id, '必须' AS release_class, 'POST_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '表 ecom_personal_receiver、club_personal_payout_beneficiary；列 coop_settlement.club_id、coop_settlement.club_name、coop_settlement.payout_hold_reason、coop_settlement_original_component.club_id、coop_settlement_original_component.club_name、settlement_detail.club_id、settlement_detail.club_name、ecom_profit_share_line.receiver_type、ecom_profit_share_line.receiver_appid、ecom_profit_share_line.club_id、ecom_profit_share_line.club_name、ecom_profit_share_return.return_type、ecom_profit_share_return.return_appid；索引 ecom_profit_share_return.uk_ecom_share_return_identity' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'ecom_personal_receiver'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'club_personal_payout_beneficiary'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'coop_settlement' AND column_name = 'club_id'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'coop_settlement' AND column_name = 'club_name'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'coop_settlement' AND column_name = 'payout_hold_reason'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'coop_settlement_original_component' AND column_name = 'club_id'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'coop_settlement_original_component' AND column_name = 'club_name'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'settlement_detail' AND column_name = 'club_id'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'settlement_detail' AND column_name = 'club_name'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ecom_profit_share_line' AND column_name = 'receiver_type'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ecom_profit_share_line' AND column_name = 'receiver_appid'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ecom_profit_share_line' AND column_name = 'club_id'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ecom_profit_share_line' AND column_name = 'club_name'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ecom_profit_share_return' AND column_name = 'return_type'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ecom_profit_share_return' AND column_name = 'return_appid'), 0) + IFNULL((SELECT COUNT(*) > 0 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'ecom_profit_share_return' AND index_name = 'uk_ecom_share_return_identity'), 0)) AS present, 16 AS expected) x;

-- [156] migration_20261112_dec_c_perks.sql · 必须 · PRE_DEPLOY
SELECT 156 AS step, '20261112_dec_c_perks' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '表 merchant_commerce_refund；列 merchant_subscription.source_order_id；索引 merchant_subscription.uk_subscription_source_order' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'merchant_commerce_refund'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'merchant_subscription' AND column_name = 'source_order_id'), 0) + IFNULL((SELECT COUNT(*) > 0 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'merchant_subscription' AND index_name = 'uk_subscription_source_order'), 0)) AS present, 3 AS expected) x;

-- [157] migration_x12_topic_look_20261029.sql · 必须 · PRE_DEPLOY
SELECT 157 AS step, 'x12_topic_look_20261029' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '列 cms_topic.look_json' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cms_topic' AND column_name = 'look_json'), 0)) AS present, 1 AS expected) x;

-- [158] migration_z_channel_voucher_snapshot_20261109.sql · 必须 · PRE_DEPLOY
SELECT 158 AS step, 'z_channel_voucher_snapshot_20261109' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '列 marketing_channel_voucher.grant_snapshot_json' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'marketing_channel_voucher' AND column_name = 'grant_snapshot_json'), 0)) AS present, 1 AS expected) x;

-- [159] migration_z_marketing_revenue_channel_reverse_kind_20261108.sql · 不执行 · PRE_DEPLOY
SELECT 159 AS step, 'z_marketing_revenue_channel_reverse_kind_20261108' AS migration_id, '不执行' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, 'CHECK 约束已是新形状' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) FROM information_schema.check_constraints WHERE constraint_schema = DATABASE() AND constraint_name = 'ck_revenue_posting_kind' AND check_clause LIKE '%3%'), 0)) AS present, 1 AS expected) x;

-- [160] migration_z_marketing_revenue_channel_reverse_kind_index_shape_20261109.sql · 必须 · PRE_DEPLOY
SELECT 160 AS step, 'z_marketing_revenue_channel_reverse_kind_index_shape_20261109' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, 'CHECK 约束已是新形状' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) FROM information_schema.check_constraints WHERE constraint_schema = DATABASE() AND constraint_name = 'ck_revenue_posting_kind' AND check_clause LIKE '%3%'), 0)) AS present, 1 AS expected) x;

-- [161] migration_z_workshop_apple_iap_20261109.sql · 必须 · PRE_DEPLOY
SELECT 161 AS step, 'z_workshop_apple_iap_20261109' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '表 workshop_apple_original、workshop_apple_transaction、workshop_apple_notification' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'workshop_apple_original'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'workshop_apple_transaction'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'workshop_apple_notification'), 0)) AS present, 3 AS expected) x;

-- [162] migration_z_workshop_purchase_20261109.sql · 必须 · PRE_DEPLOY
SELECT 162 AS step, 'z_workshop_purchase_20261109' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '表 workshop_version、workshop_order、workshop_install' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'workshop_version'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'workshop_order'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'workshop_install'), 0)) AS present, 3 AS expected) x;

-- [163] migration_z_workshop_paid_review_20261109.sql · 必须 · PRE_DEPLOY
SELECT 163 AS step, 'z_workshop_paid_review_20261109' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '表 workshop_apple_product、workshop_draft_lineage；列 workshop_version.id、workshop_version.source_kind、workshop_version.review_state、workshop_version.audit_task_id、workshop_version.review_hash、workshop_version.review_snapshot、workshop_version.approved_at、workshop_order.version_review_hash、workshop_order.currency、workshop_order.amount_milliunits、workshop_order.app_account_token、workshop_order.apple_bundle_id、workshop_order.apple_environment、workshop_order.apple_product_id、workshop_order.apple_transaction_id、workshop_order.apple_original_transaction_id、workshop_order.apple_fact_hash、workshop_order.apple_revocation_hash；索引 workshop_version.uk_workshop_version_audit、workshop_version.idx_workshop_source、workshop_version.idx_workshop_shelf、workshop_order.uk_workshop_apple_account、workshop_order.uk_workshop_apple_transaction、workshop_order.uk_workshop_apple_original' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'workshop_apple_product'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'workshop_draft_lineage'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'workshop_version' AND column_name = 'id'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'workshop_version' AND column_name = 'source_kind'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'workshop_version' AND column_name = 'review_state'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'workshop_version' AND column_name = 'audit_task_id'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'workshop_version' AND column_name = 'review_hash'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'workshop_version' AND column_name = 'review_snapshot'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'workshop_version' AND column_name = 'approved_at'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'workshop_order' AND column_name = 'version_review_hash'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'workshop_order' AND column_name = 'currency'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'workshop_order' AND column_name = 'amount_milliunits'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'workshop_order' AND column_name = 'app_account_token'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'workshop_order' AND column_name = 'apple_bundle_id'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'workshop_order' AND column_name = 'apple_environment'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'workshop_order' AND column_name = 'apple_product_id'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'workshop_order' AND column_name = 'apple_transaction_id'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'workshop_order' AND column_name = 'apple_original_transaction_id'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'workshop_order' AND column_name = 'apple_fact_hash'), 0) + IFNULL((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'workshop_order' AND column_name = 'apple_revocation_hash'), 0) + IFNULL((SELECT COUNT(*) > 0 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'workshop_version' AND index_name = 'uk_workshop_version_audit'), 0) + IFNULL((SELECT COUNT(*) > 0 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'workshop_version' AND index_name = 'idx_workshop_source'), 0) + IFNULL((SELECT COUNT(*) > 0 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'workshop_version' AND index_name = 'idx_workshop_shelf'), 0) + IFNULL((SELECT COUNT(*) > 0 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'workshop_order' AND index_name = 'uk_workshop_apple_account'), 0) + IFNULL((SELECT COUNT(*) > 0 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'workshop_order' AND index_name = 'uk_workshop_apple_transaction'), 0) + IFNULL((SELECT COUNT(*) > 0 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'workshop_order' AND index_name = 'uk_workshop_apple_original'), 0)) AS present, 26 AS expected) x;

-- [164] migration_zz_questo_collation_align_20261109.sql · 必须 · PRE_DEPLOY
SELECT 164 AS step, 'zz_questo_collation_align_20261109' AS migration_id, '必须' AS release_class, 'PRE_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '这些表里隐式 utf8mb4_0900_ai_ci 的列数为 0（38 张表）' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) = 0 FROM information_schema.columns WHERE table_schema = DATABASE() AND collation_name = 'utf8mb4_0900_ai_ci' AND table_name IN ('cms_registration_seat_invitation', 'cms_registration_seat_invitation_request', 'cms_topic_content_revision', 'content_landing_metadata', 'content_landing_translation', 'gift_entitlement', 'host_tip_config', 'host_tip_intent', 'marketing_attribution', 'marketing_channel_order_verification', 'marketing_channel_redemption', 'marketing_channel_refund_verification', 'marketing_channel_voucher', 'marketing_channel_voucher_account', 'marketing_offer', 'marketing_offer_item', 'marketing_order_adjustment', 'marketing_product', 'marketing_product_item', 'marketing_product_order', 'marketing_product_order_line', 'marketing_promotion', 'marketing_promotion_redemption', 'marketing_promotion_refund_fact', 'marketing_referral_credential', 'marketing_revenue_allocation', 'marketing_revenue_allocation_line', 'marketing_revenue_posting_task', 'marketing_revenue_rule', 'marketing_tier', 'play_help_run_quota', 'video_bounty_accrual', 'video_bounty_campaign', 'video_bounty_evidence', 'video_bounty_submission', 'workshop_apple_notification', 'workshop_apple_original', 'workshop_apple_transaction')), 0)) AS present, 1 AS expected) x;

-- [165] migration_zzz_committed_official_notice_job_reregister_20261111.sql · 必须 · POST_DEPLOY
SELECT 165 AS step, 'zzz_committed_official_notice_job_reregister_20261111' AS migration_id, '必须' AS release_class, 'POST_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '定时任务 committedOfficialNoticeTask.deliverPending()' AS detail
FROM (SELECT (IFNULL((SELECT COUNT(*) > 0 FROM sys_job WHERE invoke_target = 'committedOfficialNoticeTask.deliverPending()'), 0)) AS present, 1 AS expected) x;

-- [166] migration_20261113_stagger_payout_jobs.sql · 必须 · POST_DEPLOY
SELECT 166 AS step, '20261113_stagger_payout_jobs' AS migration_id, '必须' AS release_class, 'POST_DEPLOY' AS phase,
  CASE WHEN present = expected THEN 'APPLIED' WHEN present = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  CONCAT(present, '/', expected) AS objects_present, '迁移自身只读断言' AS detail
FROM (SELECT (IFNULL((SELECT (SELECT COUNT(*) FROM sys_job WHERE invoke_target IN ('ecommercePayoutTask.shareAndRetry()', 'ecommercePayoutTask.syncOnboarding()', 'committedOfficialNoticeTask.deliverPending()')
          AND cron_expression IN ('0 0/10 * * * ?', '0 15/30 * * * ?', '0 0/1 * * * ?')) = 0
   AND (SELECT COUNT(*) FROM sys_job WHERE status = '0' AND cron_expression IN ('0 * * * * ?', '0 0/2 * * * ?', '0 0/10 * * * ?', '0 0/15 * * * ?', '0 0/30 * * * ?')) = 0 AS payout_jobs_staggered), 0)) AS present, 1 AS expected) x;

