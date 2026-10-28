-- =====================================================================================
-- release-migration-precheck.sql —— 发布迁移只读预检（release-closeout-20261028）
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
SELECT 68 AS exec_order, 'audit_problem_20261022' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 audit_problem' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 11 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 10367431202118961532
       AND (SELECT COUNT(*) = 4 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 4534874757148141377 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'audit_problem')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'audit_problem'), 3) AS c0) AS x6
UNION ALL
SELECT 107 AS exec_order, 'club_welcome_note_20261022' AS migration_id,
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
SELECT 140 AS exec_order, 'doc2_f3_bundle_ledger_20261022' AS migration_id,
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
SELECT 185 AS exec_order, 'member_profile_version_20261022' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 ums_member_profile_version' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 7 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 6303910045254689405
       AND (SELECT COUNT(*) = 3 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 16657050347810390992 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'ums_member_profile_version')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ums_member_profile_version'), 3) AS c0) AS x8
UNION ALL
SELECT 187 AS exec_order, 'member_visited_poi_poi_key_20261022' AS migration_id,
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
SELECT 193 AS exec_order, 'merchant_category_ids_20261022' AS migration_id,
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
SELECT 200 AS exec_order, 'merchant_desk_receipt_20261022' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 merchant_desk_receipt' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 9 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 12383343389863775861
       AND (SELECT COUNT(*) = 3 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 3217512040811375428 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'merchant_desk_receipt')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'merchant_desk_receipt'), 3) AS c0) AS x10
UNION ALL
SELECT 211 AS exec_order, 'merchant_redemption_anchor_20261022' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 merchant_redemption_anchor' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 11 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 17232808289079433777
       AND (SELECT COUNT(*) = 2 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 10133716487572001376 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'merchant_redemption_anchor')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'merchant_redemption_anchor'), 3) AS c0) AS x11
UNION ALL
SELECT 215 AS exec_order, 'merchant_wechat_qrcode_20261022' AS migration_id,
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
SELECT 237 AS exec_order, 'o14_city_code_backfill_log_20261022' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 o14_city_code_backfill_log' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 5 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 7322861083250056029
       AND (SELECT COUNT(*) = 2 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 15028227347182792313 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'o14_city_code_backfill_log')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'o14_city_code_backfill_log'), 3) AS c0) AS x12
UNION ALL
SELECT 238 AS exec_order, 'o14_cms_activity_city_code_20261022' AS migration_id,
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
SELECT 239 AS exec_order, 'o14_cms_topic_city_code_20261022' AS migration_id,
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
SELECT 240 AS exec_order, 'o14_content_city_code_backfill_20261022' AS migration_id,
  CASE WHEN p0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '数据回填:前置列就位后须跑 _precheck_o14_content_city_code_backfill_20261022.sql 预览' AS detail
FROM (SELECT
    IFNULL((SELECT IF(COUNT(*) = 1, 1, 3) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'club' AND engine = 'InnoDB'), 3) AS p0,
    IFNULL((SELECT IF(EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cms_topic' AND column_name = 'city_code') AND EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cms_activity' AND column_name = 'city_code') AND EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'o14_city_code_backfill_log'), 4, 0)), 3) AS c0) AS x15
UNION ALL
SELECT 241 AS exec_order, 'o14_content_search_document_20261022' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 content_search_document' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 10 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 8667109254857782881
       AND (SELECT COUNT(*) = 7 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 3318759553424233406 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'content_search_document')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'content_search_document'), 3) AS c0) AS x16
UNION ALL
SELECT 257 AS exec_order, 'orient_geo_ar_team_20261022' AS migration_id,
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
SELECT 288 AS exec_order, 'o14_reco_search_jobs_20261022' AS migration_id,
  CASE WHEN p0 = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 4 THEN 'DATA_PREVIEW' WHEN GREATEST(c0, c1) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'sys_job 2 行(暂停)' AS detail
FROM (SELECT
    IFNULL((SELECT IF(COUNT(*) = 1, 1, 3) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'recommendation_score_daily' AND engine = 'InnoDB'), 3) AS p0,
    IFNULL((SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_job WHERE invoke_target = 'recommendationScoreDailyTask.run()'), 3) AS c0,
    IFNULL((SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_job WHERE invoke_target = 'contentSearchProjectionTask.refresh()'), 3) AS c1) AS x18
UNION ALL
SELECT 302 AS exec_order, 'play_arrive_guard_log_20261022' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 play_arrive_guard_log' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 12 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 6759212771035083568
       AND (SELECT COUNT(*) = 3 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 15745635712475062580 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'play_arrive_guard_log')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'play_arrive_guard_log'), 3) AS c0) AS x19
UNION ALL
SELECT 311 AS exec_order, 'pms_sku_booking_20261022' AS migration_id,
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
SELECT 333 AS exec_order, 'orient_scoring_20261022' AS migration_id,
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
SELECT 334 AS exec_order, 'orient_session_lifecycle_20261022' AS migration_id,
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
SELECT 376 AS exec_order, 'member_visited_poi_poi_key_backfill_20261022' AS migration_id,
  CASE WHEN p0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '数据回填:poi_key 列就位后须跑附录 B 的只读预览' AS detail
FROM (SELECT
    IFNULL((SELECT IF(COUNT(*) = 1, 1, 3) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'roam_poi' AND engine = 'InnoDB'), 3) AS p0,
    IFNULL((SELECT IF(EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'member_visited_poi' AND column_name = 'poi_key'), 4, 0)), 3) AS c0) AS x23
UNION ALL
SELECT 387 AS exec_order, 'roam_share_version_20261022' AS migration_id,
  CASE WHEN p0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'roam_share_snapshot.version' AS detail
FROM (SELECT
    IFNULL((SELECT IF(COUNT(*) = 1, 1, 3) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'roam_share_snapshot' AND engine = 'InnoDB'), 3) AS p0,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'int' AND is_nullable = 'NO' AND column_default = '1') = 1 THEN 1 ELSE 2 END
   FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'roam_share_snapshot' AND column_name = 'version'), 3) AS c0) AS x24
UNION ALL
SELECT 425 AS exec_order, '20261022_theme_state_key_attribute_def' AS migration_id,
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
SELECT 426 AS exec_order, '20261022_theme_character' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 theme_character' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 8 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 852904077189281626
       AND (SELECT COUNT(*) = 3 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 44189788068057420 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'theme_character')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'theme_character'), 3) AS c0) AS x26
UNION ALL
SELECT 479 AS exec_order, 'verification_review_evidence_20261022' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 verification_review_evidence' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 8 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 14781193881360100961
       AND (SELECT COUNT(*) = 5 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 17353113222738040501 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'verification_review_evidence')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'verification_review_evidence'), 3) AS c0) AS x27
UNION ALL
SELECT 500 AS exec_order, '20261013_admin_role_page_grants' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'APPLIED = 4 个后台角色均已有页面授权(本迁移 no-op);NEEDED = 有角色无页面授权,禁止执行,交 owner(见 pack D-PAYEE 节);checksum 对账见附录 A' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(DISTINCT r.role_key) < 4 THEN 3
   WHEN SUM(EXISTS (SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id = rm.menu_id
     WHERE rm.role_id = r.role_id AND m.menu_type = 'C')) = COUNT(*) THEN 1 ELSE 0 END
   FROM sys_role r WHERE r.del_flag = '0' AND r.role_key IN ('cy_operator', 'cy_auditor', 'cy_support', 'cy_finance')), 3) AS c0) AS x28
UNION ALL
SELECT 501 AS exec_order, '20261016_admin_role_payee_separation_forward' AS migration_id,
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
SELECT 0 AS exec_order, '20261023_wx_pay_order_intent' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 wx_pay_order_intent（单次执行）' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 17 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 17851509776243270029
       AND (SELECT COUNT(*) = 13 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 9627256126155115312 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'wx_pay_order_intent')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'wx_pay_order_intent'), 3) AS c0) AS n0
UNION ALL
SELECT 0 AS exec_order, '20261024_cash_withdraw_transfer_intent' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 member_cash_withdraw_transfer_intent（单次执行）' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 16 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 11618201005589884506
       AND (SELECT COUNT(*) = 3 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 12031721957538158388 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'member_cash_withdraw_transfer_intent')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'member_cash_withdraw_transfer_intent'), 3) AS c0) AS n1
UNION ALL
SELECT 0 AS exec_order, '20261025_coop_invite_request_receipt' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 coop_invite_request_receipt' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 16 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 17316570512569307862
       AND (SELECT COUNT(*) = 6 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 8173663945025707328 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'coop_invite_request_receipt')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'coop_invite_request_receipt'), 3) AS c0) AS n2
UNION ALL
SELECT 0 AS exec_order, '20261026_official_event_attendance' AS migration_id,
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
SELECT 0 AS exec_order, '20261026_team_event_command_receipt' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 team_event_command_receipt' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 10 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 9596879265546310952
       AND (SELECT COUNT(*) = 5 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 14619782478392098117 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'team_event_command_receipt')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'team_event_command_receipt'), 3) AS c0) AS n4
UNION ALL
SELECT 0 AS exec_order, '20261026_theme_attribute_registry_version' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 theme_attribute_registry_version' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 2 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 4210680533194302826
       AND (SELECT COUNT(*) = 1 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 14128333254474228543 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'theme_attribute_registry_version')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'theme_attribute_registry_version'), 3) AS c0) AS n5
UNION ALL
SELECT 0 AS exec_order, '20261026_user_invoice_processing' AS migration_id,
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
SELECT 0 AS exec_order, '20261026_user_invoice_title_receipt' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 user_invoice_title_receipt' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 5 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 10065551186701799896
       AND (SELECT COUNT(*) = 2 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 5863983891022771450 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'user_invoice_title_receipt')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'user_invoice_title_receipt'), 3) AS c0) AS n7
UNION ALL
SELECT 0 AS exec_order, '20261027_template_adoption_receipt' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 template_adoption_receipt' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 12 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 11451000278466319056
       AND (SELECT COUNT(*) = 2 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 8511979612919902689 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'template_adoption_receipt')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'template_adoption_receipt'), 3) AS c0) AS n8
UNION ALL
SELECT 0 AS exec_order, '20261028_topic_play_setup' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 cms_topic_play_setup' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 7 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 16548424480571454463
       AND (SELECT COUNT(*) = 1 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 14128333254474228543 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'cms_topic_play_setup')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cms_topic_play_setup'), 3) AS c0) AS n9
UNION ALL
SELECT 0 AS exec_order, 'doc2_f3_refund_hold_20261026' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 explore_bundle_refund_hold' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 10 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 11813387773646313411
       AND (SELECT COUNT(*) = 2 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 12929690359715079447 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'explore_bundle_refund_hold')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'explore_bundle_refund_hold'), 3) AS c0) AS n10
UNION ALL
SELECT 0 AS exec_order, 'orient_score_recovery_20261026' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 orient_score_recovery' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 10 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 887350978483314695
       AND (SELECT COUNT(*) = 5 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 7890895288392942713 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'orient_score_recovery')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'orient_score_recovery'), 3) AS c0) AS n11
UNION ALL
SELECT 0 AS exec_order, 'orient_score_sync_receipt_20261026' AS migration_id,
  CASE WHEN c0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 4 THEN 'DATA_PREVIEW' WHEN c0 = 2 THEN 'CONFLICT'
       WHEN c0 = 1 THEN 'APPLIED' WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  '新表 orient_score_sync_receipt' AS detail
FROM (SELECT
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 6 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 419381447761362976
       AND (SELECT COUNT(*) = 2 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 7518263432094455407 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'orient_score_sync_receipt')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'orient_score_sync_receipt'), 3) AS c0) AS n12
UNION ALL
SELECT 0 AS exec_order, '20261024_community_open_flags' AS migration_id,
  CASE WHEN GREATEST(c0, c1, c2) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1, c2) = 4 THEN 'DATA_PREVIEW' WHEN GREATEST(c0, c1, c2) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1, c2) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1, c2) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'sys_config 3 键（缺失才插入；已配置值保持原样）' AS detail
FROM (SELECT
    (SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_config WHERE config_key = 'feature.flag.communityPostRead') AS c0,
    (SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_config WHERE config_key = 'feature.flag.communityPostWrite') AS c1,
    (SELECT CASE COUNT(*) WHEN 0 THEN 0 WHEN 1 THEN 1 ELSE 2 END FROM sys_config WHERE config_key = 'feature.flag.communityPostPilotMembers') AS c2) AS n20
UNION ALL
SELECT 0 AS exec_order, '20261026_official_broadcast_delivery' AS migration_id,
  CASE WHEN p0 = 3 THEN 'BLOCKED_PREREQ' WHEN p1 = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12) = 4 THEN 'DATA_PREVIEW' WHEN GREATEST(c0, c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'official_broadcast 4 列+唯一键、official_broadcast_log 7 列、新表 official_broadcast_creation（单次执行；PARTIAL 不可重跑）' AS detail
FROM (SELECT
    IFNULL((SELECT IF(COUNT(*) = 1, 1, 3) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'official_broadcast' AND engine = 'InnoDB'), 3) AS p0,
    IFNULL((SELECT IF(COUNT(*) = 1, 1, 3) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'official_broadcast_log' AND engine = 'InnoDB'), 3) AS p1,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'tinyint' AND is_nullable = 'NO' AND column_default = '0') = 1 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_broadcast' AND column_name = 'audience_frozen') AS c0,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'varchar(32)' AND is_nullable = 'YES' AND column_default IS NULL) = 1 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_broadcast' AND column_name = 'client_request_id') AS c1,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'varchar(64)' AND is_nullable = 'YES' AND column_default IS NULL) = 1 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_broadcast' AND column_name = 'request_owner') AS c2,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'varchar(64)' AND is_nullable = 'YES' AND column_default IS NULL) = 1 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_broadcast' AND column_name = 'request_hash') AS c3,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'varchar(32)' AND is_nullable = 'YES' AND column_default IS NULL) = 1 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_broadcast_log' AND column_name = 'delivery_state') AS c4,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'varchar(128)' AND is_nullable = 'YES' AND column_default IS NULL) = 1 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_broadcast_log' AND column_name = 'frozen_title') AS c5,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'text' AND is_nullable = 'YES' AND column_default IS NULL) = 1 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_broadcast_log' AND column_name = 'frozen_sub') AS c6,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'bigint' AND is_nullable = 'YES' AND column_default IS NULL) = 1 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_broadcast_log' AND column_name = 'intent_id') AS c7,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'bigint' AND is_nullable = 'YES' AND column_default IS NULL) = 1 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_broadcast_log' AND column_name = 'message_id') AS c8,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'int' AND is_nullable = 'NO' AND column_default = '0') = 1 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_broadcast_log' AND column_name = 'attempts') AS c9,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'datetime(3)' AND is_nullable = 'YES' AND column_default IS NULL) = 1 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_broadcast_log' AND column_name = 'update_time') AS c10,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 2 AND SUM(non_unique = 0 AND ((seq_in_index = 1 AND column_name = 'request_owner') OR (seq_in_index = 2 AND column_name = 'client_request_id'))) = 2 THEN 1 ELSE 2 END FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'official_broadcast' AND index_name = 'uk_broadcast_request') AS c11,
    IFNULL((SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 4 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',ordinal_position,column_name,column_type,is_nullable,IFNULL(column_default,'~')),256),16),16,10) AS UNSIGNED)) = 3111680458731418328
       AND (SELECT COUNT(*) = 2 AND BIT_XOR(CAST(CONV(LEFT(SHA2(CONCAT_WS('|',index_name,non_unique,seq_in_index,column_name),256),16),16,10) AS UNSIGNED)) = 2398059144972562845 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'official_broadcast_creation')
       THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'official_broadcast_creation'), 3) AS c12) AS n21
UNION ALL
SELECT 0 AS exec_order, '20261024_official_roam_challenges' AS migration_id,
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
SELECT 0 AS exec_order, '20261025_official_poi_quotes' AS migration_id,
  CASE WHEN p0 = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 4 THEN 'DATA_PREVIEW' WHEN GREATEST(c0, c1) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'roam_poi.quotes_json / photo_credit' AS detail
FROM (SELECT
    IFNULL((SELECT IF(COUNT(*) = 1, 1, 3) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'roam_poi' AND engine = 'InnoDB'), 3) AS p0,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'text' AND is_nullable = 'YES' AND column_default IS NULL) = 1 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'roam_poi' AND column_name = 'quotes_json') AS c0,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'text' AND is_nullable = 'YES' AND column_default IS NULL) = 1 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'roam_poi' AND column_name = 'photo_credit') AS c1) AS n23
UNION ALL
SELECT 0 AS exec_order, '20261028_d20_attribute_registry' AS migration_id,
  CASE WHEN p0 = 3 THEN 'BLOCKED_PREREQ' WHEN p1 = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 4 THEN 'DATA_PREVIEW' WHEN GREATEST(c0, c1) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'theme_state_key.is_attribute / attribute_preset；同文件回填为 ON DUPLICATE KEY UPDATE，可重跑' AS detail
FROM (SELECT
    IFNULL((SELECT IF(COUNT(*) = 1, 1, 3) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'theme_state_key' AND engine = 'InnoDB'), 3) AS p0,
    IFNULL((SELECT IF(COUNT(*) = 1, 1, 3) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'cms_topic' AND engine = 'InnoDB'), 3) AS p1,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'tinyint(1)' AND is_nullable = 'NO' AND column_default = '0') = 1 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'theme_state_key' AND column_name = 'is_attribute') AS c0,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'varchar(16)' AND is_nullable = 'YES' AND column_default IS NULL) = 1 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'theme_state_key' AND column_name = 'attribute_preset') AS c1) AS n24
UNION ALL
SELECT 0 AS exec_order, 'w8_refund_fulfillment_recovery_20261026' AS migration_id,
  CASE WHEN p0 = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 3 THEN 'BLOCKED_PREREQ' WHEN GREATEST(c0, c1) = 4 THEN 'DATA_PREVIEW' WHEN GREATEST(c0, c1) = 2 THEN 'CONFLICT'
       WHEN LEAST(c0, c1) = 1 THEN 'APPLIED' WHEN GREATEST(c0, c1) = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'refund_application.fulfillment_sync_queue_at + 恢复队列索引' AS detail
FROM (SELECT
    IFNULL((SELECT IF(COUNT(*) = 1, 1, 3) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'refund_application' AND engine = 'InnoDB'), 3) AS p0,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN SUM(column_type = 'datetime(6)' AND is_nullable = 'NO' AND column_default = 'CURRENT_TIMESTAMP(6)') = 1 THEN 1 ELSE 2 END FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'refund_application' AND column_name = 'fulfillment_sync_queue_at') AS c0,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) = 4 AND SUM(non_unique = 1 AND ((seq_in_index = 1 AND column_name = 'source_type') OR (seq_in_index = 2 AND column_name = 'del_flag') OR (seq_in_index = 3 AND column_name = 'fulfillment_sync_queue_at') OR (seq_in_index = 4 AND column_name = 'id'))) = 4 THEN 1 ELSE 2 END FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'refund_application' AND index_name = 'idx_refund_fulfillment_recovery') AS c1) AS n25
UNION ALL
SELECT 0 AS exec_order, '20261026_user_invoice_menu' AS migration_id,
  CASE WHEN p0 = 3 THEN 'BLOCKED_PREREQ' WHEN c0 = 2 THEN 'CONFLICT' WHEN c0 = 1 THEN 'APPLIED'
       WHEN c0 = 0 THEN 'NEEDED' ELSE 'PARTIAL' END AS status,
  'sys_menu 用户发票处理 C 菜单 + 4 个 F 按钮；父菜单 funds-aftercare 须恰好 1 行；PARTIAL 可重跑（逐行 NOT EXISTS + 形状守卫）' AS detail
FROM (SELECT
    (SELECT IF(COUNT(*) = 1, 1, 3) FROM sys_menu WHERE menu_type = 'M' AND path = 'funds-aftercare') AS p0,
    (SELECT CASE WHEN COUNT(*) = 0 THEN 0 WHEN COUNT(*) > COUNT(DISTINCT perms) OR SUM(NOT ((perms = 'business:userInvoice:list' AND menu_type <=> 'C' AND component <=> 'business/userInvoice/index' AND route_name <=> 'UserInvoiceProcessing' AND path <=> 'userInvoice' AND parent_id <=> (SELECT MIN(menu_id) FROM sys_menu WHERE menu_type = 'M' AND path = 'funds-aftercare')) OR (perms <> 'business:userInvoice:list' AND menu_type <=> 'F' AND parent_id <=> (SELECT MIN(menu_id) FROM sys_menu WHERE perms = 'business:userInvoice:list')))) > 0 THEN 2 WHEN COUNT(*) = 5 THEN 1 ELSE 5 END FROM sys_menu WHERE perms IN ('business:userInvoice:list', 'business:userInvoice:query', 'business:userInvoice:taxView', 'business:userInvoice:process', 'business:userInvoice:reconcile')) AS c0) AS n26
UNION ALL
SELECT 0 AS exec_order, '20261027_orient_scoring_menu_grants' AS migration_id,
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
SELECT 0 AS exec_order, '20261027_user_invoice_processing_backfill' AS migration_id,
  CASE WHEN c0 = 4 THEN 'DATA_PREVIEW' ELSE 'NEEDED' END AS status,
  '数据回填：处理行就位后须跑附录 C1 只读预览；DATA_PREVIEW 不是回填完成证明' AS detail
FROM (SELECT
    IF(EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'user_invoice_application')
       AND EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'user_invoice_processing')
       AND EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'user_invoice_issuer_owner'), 4, 0) AS c0) AS n28
UNION ALL
SELECT 0 AS exec_order, '20261027_merchant_brand_images_to_gallery' AS migration_id,
  CASE WHEN p0 = 3 OR c0 = 3 THEN 'BLOCKED_PREREQ' ELSE 'DATA_PREVIEW' END AS status,
  '数据搬迁：有品牌形象图的商家须跑附录 C2 预览；DATA_PREVIEW 不是搬迁完成证明' AS detail
FROM (SELECT
    IFNULL((SELECT IF(COUNT(*) = 1, 1, 3) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'mms_merchant' AND engine = 'InnoDB'), 3) AS p0,
    IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'mms_merchant'
         AND column_name IN ('derivatives', 'gallery')) = 2, 4, 3) AS c0) AS n29
UNION ALL
SELECT 0 AS exec_order, '20261028_remove_wildlife_theme' AS migration_id,
  CASE WHEN c0 > 1 THEN 'CONFLICT' WHEN c0 = 1 THEN 'DATA_PREVIEW' ELSE 'APPLIED' END AS status,
  '在架目标行数：1=须先跑 _precheck_remove_wildlife_theme_20261028.sql（引用计数全 0）；0=无事可做' AS detail
FROM (SELECT (SELECT COUNT(*) FROM cms_topic WHERE id BETWEEN 990052 AND 990056 AND name = '野生邻居观察局' AND del_flag = 0) AS c0) AS n30
UNION ALL
SELECT 0 AS exec_order, '20261028_template_shelf_list_free_explore' AS migration_id,
  CASE WHEN c1 > 0 THEN 'CONFLICT' WHEN c0 > 0 THEN 'DATA_PREVIEW' ELSE 'APPLIED' END AS status,
  '待上架模板数（>0 先核对 6 个名称各恰好 1 行且 is_template=1）；同名多行判 CONFLICT' AS detail
FROM (SELECT
    (SELECT COUNT(*) FROM cms_topic WHERE del_flag = 0 AND is_template = 1 AND status = 1 AND user_status = 0
      AND name IN ('风味巡游', '不惑之年', '预制人生', '高温末日逃生', '上海像一部旧电影', '生活不掉线')) AS c0,
    (SELECT COUNT(*) FROM (SELECT name FROM cms_topic WHERE del_flag = 0 AND name IN ('风味巡游', '不惑之年', '预制人生', '高温末日逃生', '上海像一部旧电影', '生活不掉线')
      GROUP BY name HAVING COUNT(*) > 1) d) AS c1) AS n31
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
WHERE invoke_target IN ('recommendationScoreDailyTask.run()', 'contentSearchProjectionTask.refresh()')
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
UNION ALL SELECT 'topic_journey_rules_20260916 (cms_topic.journey_rules_json)', COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'cms_topic' AND column_name = 'journey_rules_json';

-- [6/7] 锁/体量：被 ALTER 或回填的既有表（table_rows 为估算值）
SELECT table_name, engine, table_rows AS approx_rows,
       ROUND((data_length + index_length) / 1024 / 1024, 1) AS approx_mb, row_format
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN ('cms_topic', 'cms_activity', 'member_visited_poi', 'topic_route_session',
                     'roam_share_snapshot', 'theme_state_key', 'sys_role_menu', 'sys_config', 'sys_job',
                     'club', 'mms_merchant', 'ums_creative_square_like', 'official_broadcast',
                     'official_broadcast_log', 'roam_poi', 'roam_poi_reward_log', 'refund_application',
                     'user_invoice_application', 'sys_menu')
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
-- =====================================================================================
