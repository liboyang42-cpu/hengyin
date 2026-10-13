-- 修复候选阶段已提前落库的两张空表旧形态：售后缺门店行 ID，CRM 导出仍混用 owner/member 与 merchant row。
-- 依赖：migration_merchant_operator_aftercare_20260823.sql、
--       migration_merchant_operator_crm_phase1a_20260823.sql。
-- 仅接受“当前终态”或“已确认的精确空旧表”；任何有数据的旧形态或第三种结构都在 DDL 前 fail closed。

SET SESSION group_concat_max_len = 65535;
SET @merchant_aftercare_before_rows := (SELECT COUNT(*) FROM merchant_aftercare_response);
SET @merchant_crm_export_before_rows := (SELECT COUNT(*) FROM merchant_crm_export_task);

SET @merchant_aftercare_checks_exact_ok := (
  SELECT
    (SELECT COUNT(*) FROM information_schema.table_constraints
     WHERE constraint_schema = DATABASE()
       AND table_name = 'merchant_aftercare_response') = 4
    AND (SELECT COUNT(*) FROM information_schema.table_constraints
     WHERE constraint_schema = DATABASE()
       AND table_name = 'merchant_aftercare_response'
       AND constraint_type = 'CHECK') = 2
    AND (SELECT COUNT(*) FROM (
           SELECT tc.constraint_name, tc.enforced,
                  SHA2(REPLACE(REPLACE(LOWER(cc.check_clause), '`', ''), ' ', ''), 256)
                    AS clause_hash
           FROM information_schema.table_constraints tc
           JOIN information_schema.check_constraints cc
             ON cc.constraint_schema = tc.constraint_schema
            AND cc.constraint_name = tc.constraint_name
           WHERE tc.constraint_schema = DATABASE()
             AND tc.table_name = 'merchant_aftercare_response'
             AND tc.constraint_type = 'CHECK'
         ) exact_check
         WHERE enforced = 'YES'
           AND ((constraint_name = 'chk_merchant_aftercare_decision'
                 AND clause_hash <=>
                   '31905bed0c12d0b0eaf0bba0e254501e6244eced35f42c2d80e78b5be3654ba1')
             OR (constraint_name = 'chk_merchant_aftercare_actor_role'
                 AND clause_hash <=>
                   '2609dc47a1aa6cc45d8f85c4f3d99dddcb2fe122f04038a3d72a8ff073a919f6'))) = 2
);

SET @merchant_crm_export_checks_exact_ok := (
  SELECT
    (SELECT COUNT(*) FROM information_schema.table_constraints
     WHERE constraint_schema = DATABASE()
       AND table_name = 'merchant_crm_export_task') = 5
    AND (SELECT COUNT(*) FROM information_schema.table_constraints
     WHERE constraint_schema = DATABASE()
       AND table_name = 'merchant_crm_export_task'
       AND constraint_type = 'CHECK') = 3
    AND (SELECT COUNT(*) FROM (
           SELECT tc.constraint_name, tc.enforced,
                  SHA2(REPLACE(REPLACE(LOWER(cc.check_clause), '`', ''), ' ', ''), 256)
                    AS clause_hash
           FROM information_schema.table_constraints tc
           JOIN information_schema.check_constraints cc
             ON cc.constraint_schema = tc.constraint_schema
            AND cc.constraint_name = tc.constraint_name
           WHERE tc.constraint_schema = DATABASE()
             AND tc.table_name = 'merchant_crm_export_task'
             AND tc.constraint_type = 'CHECK'
         ) exact_check
         WHERE enforced = 'YES'
           AND ((constraint_name = 'chk_merchant_crm_export_paid'
                 AND clause_hash <=>
                   'e2f1899b8f0410c40d7ecbc91993b624073acba0fa72bb7e6d3833d69d1c33d2')
             OR (constraint_name = 'chk_merchant_crm_export_status'
                 AND clause_hash <=>
                   '05f9d00e2cd77ffb0cd7bc19700b6c4a0ef3916960d0b06ef3678fff31102eb4')
             OR (constraint_name = 'chk_merchant_crm_export_limit'
                 AND clause_hash <=>
                   '0c6fe7c7409460131c2fada15dc9d1b1fca39605bec02ae6bb307a9ecb29779d'))) = 3
);

SET @merchant_aftercare_legacy_shape_ok := (
  SELECT
    (SELECT COUNT(*) FROM information_schema.tables
     WHERE table_schema = DATABASE() AND table_name = 'merchant_aftercare_response'
       AND engine = 'InnoDB' AND table_collation = 'utf8mb4_0900_ai_ci') = 1
    AND (SELECT COUNT(*) FROM information_schema.triggers
         WHERE event_object_schema = DATABASE()
           AND event_object_table = 'merchant_aftercare_response') = 0
    AND (SELECT COUNT(*) = 10
           AND GROUP_CONCAT(column_name ORDER BY ordinal_position SEPARATOR ',') =
             'id,refund_application_id,merchant_owner_member_id,actor_member_id,actor_role_code,decision,content,evidence_url,request_id,create_time'
           AND SUM(column_name = 'merchant_id') = 0
           AND SUM(column_name = 'refund_application_id' AND column_type = 'bigint'
                   AND is_nullable = 'NO') = 1
           AND SUM(column_name = 'merchant_owner_member_id' AND column_type = 'bigint'
                   AND is_nullable = 'NO') = 1
           AND SUM(column_name = 'request_id' AND column_type = 'varchar(64)'
                   AND is_nullable = 'NO' AND character_set_name = 'ascii'
                   AND collation_name = 'ascii_bin') = 1
         FROM information_schema.columns
         WHERE table_schema = DATABASE() AND table_name = 'merchant_aftercare_response')
    AND (SELECT SHA2(GROUP_CONCAT(CONCAT(
             'n=', IF(column_name IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(column_name AS BINARY)), ':',
                      HEX(CAST(column_name AS BINARY)))),
             '|t=', IF(column_type IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(column_type AS BINARY)), ':',
                      HEX(CAST(column_type AS BINARY)))),
             '|u=', IF(is_nullable IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(is_nullable AS BINARY)), ':',
                      HEX(CAST(is_nullable AS BINARY)))),
             '|d=', IF(column_default IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(column_default AS BINARY)), ':',
                      HEX(CAST(column_default AS BINARY)))),
             '|e=', IF(extra IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(extra AS BINARY)), ':',
                      HEX(CAST(extra AS BINARY)))),
             '|c=', IF(character_set_name IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(character_set_name AS BINARY)), ':',
                      HEX(CAST(character_set_name AS BINARY)))),
             '|l=', IF(collation_name IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(collation_name AS BINARY)), ':',
                      HEX(CAST(collation_name AS BINARY)))),
             '|g=', IF(generation_expression IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(generation_expression AS BINARY)), ':',
                      HEX(CAST(generation_expression AS BINARY)))))
           ORDER BY ordinal_position SEPARATOR ';'), 256)
         FROM information_schema.columns
         WHERE table_schema = DATABASE() AND table_name = 'merchant_aftercare_response')
         = 'a090f2a0eee9648787292cf17dafb7f6c0f163f1af3c23455452b1f3c22863f1'
    AND (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
         WHERE table_schema = DATABASE()
           AND table_name = 'merchant_aftercare_response') = 3
    AND (SELECT COUNT(*) FROM (
           SELECT index_name, MIN(non_unique) AS non_unique, MIN(index_type) AS index_type,
                  MIN(is_visible) AS is_visible, COUNT(sub_part) AS prefix_count,
                  GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') AS cols
           FROM information_schema.statistics
           WHERE table_schema = DATABASE() AND table_name = 'merchant_aftercare_response'
           GROUP BY index_name
         ) exact_index
         WHERE prefix_count = 0 AND index_type = 'BTREE' AND is_visible = 'YES'
           AND ((index_name = 'PRIMARY' AND non_unique = 0 AND cols = 'id')
             OR (index_name = 'uk_merchant_aftercare_request' AND non_unique = 0
                 AND cols = 'merchant_owner_member_id,request_id')
             OR (index_name = 'idx_merchant_aftercare_refund' AND non_unique = 1
                 AND cols = 'merchant_owner_member_id,refund_application_id,id'))) = 3
    AND @merchant_aftercare_checks_exact_ok
);

SET @merchant_aftercare_target_shape_ok := (
  SELECT
    (SELECT COUNT(*) FROM information_schema.tables
     WHERE table_schema = DATABASE() AND table_name = 'merchant_aftercare_response'
       AND engine = 'InnoDB' AND table_collation = 'utf8mb4_0900_ai_ci') = 1
    AND (SELECT COUNT(*) FROM information_schema.triggers
         WHERE event_object_schema = DATABASE()
           AND event_object_table = 'merchant_aftercare_response') = 0
    AND (SELECT COUNT(*) = 11
           AND GROUP_CONCAT(column_name ORDER BY ordinal_position SEPARATOR ',') =
             'id,refund_application_id,merchant_id,merchant_owner_member_id,actor_member_id,actor_role_code,decision,content,evidence_url,request_id,create_time'
           AND SUM(column_name = 'merchant_id' AND column_type = 'bigint'
                   AND is_nullable = 'NO') = 1
           AND SUM(column_name = 'merchant_owner_member_id' AND column_type = 'bigint'
                   AND is_nullable = 'NO') = 1
           AND SUM(column_name = 'request_id' AND column_type = 'varchar(64)'
                   AND is_nullable = 'NO' AND character_set_name = 'ascii'
                   AND collation_name = 'ascii_bin') = 1
         FROM information_schema.columns
         WHERE table_schema = DATABASE() AND table_name = 'merchant_aftercare_response')
    AND (SELECT SHA2(GROUP_CONCAT(CONCAT(
             'n=', IF(column_name IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(column_name AS BINARY)), ':',
                      HEX(CAST(column_name AS BINARY)))),
             '|t=', IF(column_type IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(column_type AS BINARY)), ':',
                      HEX(CAST(column_type AS BINARY)))),
             '|u=', IF(is_nullable IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(is_nullable AS BINARY)), ':',
                      HEX(CAST(is_nullable AS BINARY)))),
             '|d=', IF(column_default IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(column_default AS BINARY)), ':',
                      HEX(CAST(column_default AS BINARY)))),
             '|e=', IF(extra IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(extra AS BINARY)), ':',
                      HEX(CAST(extra AS BINARY)))),
             '|c=', IF(character_set_name IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(character_set_name AS BINARY)), ':',
                      HEX(CAST(character_set_name AS BINARY)))),
             '|l=', IF(collation_name IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(collation_name AS BINARY)), ':',
                      HEX(CAST(collation_name AS BINARY)))),
             '|g=', IF(generation_expression IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(generation_expression AS BINARY)), ':',
                      HEX(CAST(generation_expression AS BINARY)))))
           ORDER BY ordinal_position SEPARATOR ';'), 256)
         FROM information_schema.columns
         WHERE table_schema = DATABASE() AND table_name = 'merchant_aftercare_response')
         = '5153d3c140c6b9a9489484c911ddac1695f3e6cf0a2db90ed72f4327a460d2ad'
    AND (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
         WHERE table_schema = DATABASE()
           AND table_name = 'merchant_aftercare_response') = 4
    AND (SELECT COUNT(*) FROM (
           SELECT index_name, MIN(non_unique) AS non_unique, MIN(index_type) AS index_type,
                  MIN(is_visible) AS is_visible, COUNT(sub_part) AS prefix_count,
                  GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') AS cols
           FROM information_schema.statistics
           WHERE table_schema = DATABASE() AND table_name = 'merchant_aftercare_response'
           GROUP BY index_name
         ) exact_index
         WHERE prefix_count = 0 AND index_type = 'BTREE' AND is_visible = 'YES'
           AND ((index_name = 'PRIMARY' AND non_unique = 0 AND cols = 'id')
             OR (index_name = 'uk_merchant_aftercare_request' AND non_unique = 0
                 AND cols = 'merchant_id,request_id')
             OR (index_name = 'idx_merchant_aftercare_refund' AND non_unique = 1
                 AND cols = 'merchant_id,refund_application_id,id')
             OR (index_name = 'idx_merchant_aftercare_refund_lookup' AND non_unique = 1
                 AND cols = 'refund_application_id,merchant_id,id'))) = 4
    AND @merchant_aftercare_checks_exact_ok
);

SET @merchant_crm_export_legacy_shape_ok := (
  SELECT
    (SELECT COUNT(*) FROM information_schema.tables
     WHERE table_schema = DATABASE() AND table_name = 'merchant_crm_export_task'
       AND engine = 'InnoDB' AND table_collation = 'utf8mb4_0900_ai_ci') = 1
    AND (SELECT COUNT(*) FROM information_schema.triggers
         WHERE event_object_schema = DATABASE()
           AND event_object_table = 'merchant_crm_export_task') = 0
    AND (SELECT COUNT(*) = 22
           AND GROUP_CONCAT(column_name ORDER BY ordinal_position SEPARATOR ',') =
             'id,merchant_id,merchant_row_id,requester_member_id,requester_role_code,filter_json,include_paid_amount,status,version,row_limit,row_count,storage_key,token_hash,request_id,error_message,expires_at,started_at,finished_at,downloaded_at,download_count,create_time,update_time'
           AND SUM(column_name = 'merchant_id' AND column_type = 'bigint'
                   AND is_nullable = 'NO') = 1
           AND SUM(column_name = 'merchant_row_id' AND column_type = 'bigint'
                   AND is_nullable = 'NO') = 1
           AND SUM(column_name = 'merchant_owner_member_id') = 0
           AND SUM(column_name = 'request_id' AND column_type = 'varchar(64)'
                   AND is_nullable = 'NO' AND character_set_name = 'utf8mb4'
                   AND collation_name LIKE 'utf8mb4%') = 1
         FROM information_schema.columns
         WHERE table_schema = DATABASE() AND table_name = 'merchant_crm_export_task')
    AND (SELECT SHA2(GROUP_CONCAT(CONCAT(
             'n=', IF(column_name IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(column_name AS BINARY)), ':',
                      HEX(CAST(column_name AS BINARY)))),
             '|t=', IF(column_type IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(column_type AS BINARY)), ':',
                      HEX(CAST(column_type AS BINARY)))),
             '|u=', IF(is_nullable IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(is_nullable AS BINARY)), ':',
                      HEX(CAST(is_nullable AS BINARY)))),
             '|d=', IF(column_default IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(column_default AS BINARY)), ':',
                      HEX(CAST(column_default AS BINARY)))),
             '|e=', IF(extra IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(extra AS BINARY)), ':',
                      HEX(CAST(extra AS BINARY)))),
             '|c=', IF(character_set_name IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(character_set_name AS BINARY)), ':',
                      HEX(CAST(character_set_name AS BINARY)))),
             '|l=', IF(collation_name IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(collation_name AS BINARY)), ':',
                      HEX(CAST(collation_name AS BINARY)))),
             '|g=', IF(generation_expression IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(generation_expression AS BINARY)), ':',
                      HEX(CAST(generation_expression AS BINARY)))))
           ORDER BY ordinal_position SEPARATOR ';'), 256)
         FROM information_schema.columns
         WHERE table_schema = DATABASE() AND table_name = 'merchant_crm_export_task')
         = '5191f181d4abbeacf1db7d1cee6e605da419e386ce2cc65571b719c2f7006d17'
    AND (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
         WHERE table_schema = DATABASE()
           AND table_name = 'merchant_crm_export_task') = 4
    AND (SELECT COUNT(*) FROM (
           SELECT index_name, MIN(non_unique) AS non_unique, MIN(index_type) AS index_type,
                  MIN(is_visible) AS is_visible, COUNT(sub_part) AS prefix_count,
                  GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') AS cols
           FROM information_schema.statistics
           WHERE table_schema = DATABASE() AND table_name = 'merchant_crm_export_task'
           GROUP BY index_name
         ) exact_index
         WHERE prefix_count = 0 AND index_type = 'BTREE' AND is_visible = 'YES'
           AND ((index_name = 'PRIMARY' AND non_unique = 0 AND cols = 'id')
             OR (index_name = 'uk_merchant_crm_export_request' AND non_unique = 0
                 AND cols = 'merchant_id,request_id')
             OR (index_name = 'idx_merchant_crm_export_owner' AND non_unique = 1
                 AND cols = 'merchant_id,id')
             OR (index_name = 'idx_merchant_crm_export_runner' AND non_unique = 1
                 AND cols = 'status,expires_at,create_time,id'))) = 4
    AND @merchant_crm_export_checks_exact_ok
);

SET @merchant_crm_export_target_shape_ok := (
  SELECT
    (SELECT COUNT(*) FROM information_schema.tables
     WHERE table_schema = DATABASE() AND table_name = 'merchant_crm_export_task'
       AND engine = 'InnoDB' AND table_collation = 'utf8mb4_0900_ai_ci') = 1
    AND (SELECT COUNT(*) FROM information_schema.triggers
         WHERE event_object_schema = DATABASE()
           AND event_object_table = 'merchant_crm_export_task') = 0
    AND (SELECT COUNT(*) = 22
           AND GROUP_CONCAT(column_name ORDER BY ordinal_position SEPARATOR ',') =
             'id,merchant_id,merchant_owner_member_id,requester_member_id,requester_role_code,filter_json,include_paid_amount,status,version,row_limit,row_count,storage_key,token_hash,request_id,error_message,expires_at,started_at,finished_at,downloaded_at,download_count,create_time,update_time'
           AND SUM(column_name = 'merchant_id' AND column_type = 'bigint'
                   AND is_nullable = 'NO') = 1
           AND SUM(column_name = 'merchant_owner_member_id' AND column_type = 'bigint'
                   AND is_nullable = 'NO') = 1
           AND SUM(column_name = 'merchant_row_id') = 0
           AND SUM(column_name = 'request_id' AND column_type = 'varchar(64)'
                   AND is_nullable = 'NO' AND character_set_name = 'utf8mb4'
                   AND collation_name LIKE 'utf8mb4%') = 1
         FROM information_schema.columns
         WHERE table_schema = DATABASE() AND table_name = 'merchant_crm_export_task')
    AND (SELECT SHA2(GROUP_CONCAT(CONCAT(
             'n=', IF(column_name IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(column_name AS BINARY)), ':',
                      HEX(CAST(column_name AS BINARY)))),
             '|t=', IF(column_type IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(column_type AS BINARY)), ':',
                      HEX(CAST(column_type AS BINARY)))),
             '|u=', IF(is_nullable IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(is_nullable AS BINARY)), ':',
                      HEX(CAST(is_nullable AS BINARY)))),
             '|d=', IF(column_default IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(column_default AS BINARY)), ':',
                      HEX(CAST(column_default AS BINARY)))),
             '|e=', IF(extra IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(extra AS BINARY)), ':',
                      HEX(CAST(extra AS BINARY)))),
             '|c=', IF(character_set_name IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(character_set_name AS BINARY)), ':',
                      HEX(CAST(character_set_name AS BINARY)))),
             '|l=', IF(collation_name IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(collation_name AS BINARY)), ':',
                      HEX(CAST(collation_name AS BINARY)))),
             '|g=', IF(generation_expression IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(generation_expression AS BINARY)), ':',
                      HEX(CAST(generation_expression AS BINARY)))))
           ORDER BY ordinal_position SEPARATOR ';'), 256)
         FROM information_schema.columns
         WHERE table_schema = DATABASE() AND table_name = 'merchant_crm_export_task')
         = '5a67980d0a396e2a802c2e73903111165c92645a87eb8a599f231338e2f43943'
    AND (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
         WHERE table_schema = DATABASE()
           AND table_name = 'merchant_crm_export_task') = 4
    AND (SELECT COUNT(*) FROM (
           SELECT index_name, MIN(non_unique) AS non_unique, MIN(index_type) AS index_type,
                  MIN(is_visible) AS is_visible, COUNT(sub_part) AS prefix_count,
                  GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') AS cols
           FROM information_schema.statistics
           WHERE table_schema = DATABASE() AND table_name = 'merchant_crm_export_task'
           GROUP BY index_name
         ) exact_index
         WHERE prefix_count = 0 AND index_type = 'BTREE' AND is_visible = 'YES'
           AND ((index_name = 'PRIMARY' AND non_unique = 0 AND cols = 'id')
             OR (index_name = 'uk_merchant_crm_export_request' AND non_unique = 0
                 AND cols = 'merchant_id,request_id')
             OR (index_name = 'idx_merchant_crm_export_owner' AND non_unique = 1
                 AND cols = 'merchant_id,id')
             OR (index_name = 'idx_merchant_crm_export_runner' AND non_unique = 1
                 AND cols = 'status,expires_at,create_time,id'))) = 4
    AND @merchant_crm_export_checks_exact_ok
);

-- runner: assert=scalar-true
SELECT
  (@merchant_aftercare_target_shape_ok
    OR (@merchant_aftercare_legacy_shape_ok AND @merchant_aftercare_before_rows = 0))
  AND (@merchant_crm_export_target_shape_ok
    OR (@merchant_crm_export_legacy_shape_ok AND @merchant_crm_export_before_rows = 0))
  AS assertion_ok;

SET @merchant_aftercare_upgrade_sql := IF(
  @merchant_aftercare_legacy_shape_ok,
  'ALTER TABLE `merchant_aftercare_response`
     ADD COLUMN `merchant_id` BIGINT NOT NULL AFTER `refund_application_id`,
     DROP INDEX `uk_merchant_aftercare_request`,
     DROP INDEX `idx_merchant_aftercare_refund`,
     ADD UNIQUE KEY `uk_merchant_aftercare_request` (`merchant_id`, `request_id`),
     ADD KEY `idx_merchant_aftercare_refund` (`merchant_id`, `refund_application_id`, `id`),
     ADD KEY `idx_merchant_aftercare_refund_lookup` (`refund_application_id`, `merchant_id`, `id`)',
  'SELECT 1'
);
PREPARE merchant_aftercare_upgrade_stmt FROM @merchant_aftercare_upgrade_sql;
EXECUTE merchant_aftercare_upgrade_stmt;
DEALLOCATE PREPARE merchant_aftercare_upgrade_stmt;

SET @merchant_crm_export_upgrade_sql := IF(
  @merchant_crm_export_legacy_shape_ok,
  'ALTER TABLE `merchant_crm_export_task`
     DROP INDEX `uk_merchant_crm_export_request`,
     DROP INDEX `idx_merchant_crm_export_owner`,
     CHANGE COLUMN `merchant_id` `merchant_owner_member_id` BIGINT NOT NULL
       COMMENT ''mms_merchant.member_id，仅供来源查询与审计'',
     CHANGE COLUMN `merchant_row_id` `merchant_id` BIGINT NOT NULL
       COMMENT ''门店主体 mms_merchant.id'' AFTER `id`,
     ADD UNIQUE KEY `uk_merchant_crm_export_request` (`merchant_id`, `request_id`),
     ADD KEY `idx_merchant_crm_export_owner` (`merchant_id`, `id`)',
  'SELECT 1'
);
PREPARE merchant_crm_export_upgrade_stmt FROM @merchant_crm_export_upgrade_sql;
EXECUTE merchant_crm_export_upgrade_stmt;
DEALLOCATE PREPARE merchant_crm_export_upgrade_stmt;

-- 新连接仍需再跑同口径；这里先重新计算，禁止复用执行前变量制造恒真。
SET @merchant_aftercare_checks_exact_ok := (
  SELECT
    (SELECT COUNT(*) FROM information_schema.table_constraints
     WHERE constraint_schema = DATABASE()
       AND table_name = 'merchant_aftercare_response') = 4
    AND (SELECT COUNT(*) FROM information_schema.table_constraints
     WHERE constraint_schema = DATABASE()
       AND table_name = 'merchant_aftercare_response'
       AND constraint_type = 'CHECK') = 2
    AND (SELECT COUNT(*) FROM (
           SELECT tc.constraint_name, tc.enforced,
                  SHA2(REPLACE(REPLACE(LOWER(cc.check_clause), '`', ''), ' ', ''), 256)
                    AS clause_hash
           FROM information_schema.table_constraints tc
           JOIN information_schema.check_constraints cc
             ON cc.constraint_schema = tc.constraint_schema
            AND cc.constraint_name = tc.constraint_name
           WHERE tc.constraint_schema = DATABASE()
             AND tc.table_name = 'merchant_aftercare_response'
             AND tc.constraint_type = 'CHECK'
         ) exact_check
         WHERE enforced = 'YES'
           AND ((constraint_name = 'chk_merchant_aftercare_decision'
                 AND clause_hash <=>
                   '31905bed0c12d0b0eaf0bba0e254501e6244eced35f42c2d80e78b5be3654ba1')
             OR (constraint_name = 'chk_merchant_aftercare_actor_role'
                 AND clause_hash <=>
                   '2609dc47a1aa6cc45d8f85c4f3d99dddcb2fe122f04038a3d72a8ff073a919f6'))) = 2
);

SET @merchant_crm_export_checks_exact_ok := (
  SELECT
    (SELECT COUNT(*) FROM information_schema.table_constraints
     WHERE constraint_schema = DATABASE()
       AND table_name = 'merchant_crm_export_task') = 5
    AND (SELECT COUNT(*) FROM information_schema.table_constraints
     WHERE constraint_schema = DATABASE()
       AND table_name = 'merchant_crm_export_task'
       AND constraint_type = 'CHECK') = 3
    AND (SELECT COUNT(*) FROM (
           SELECT tc.constraint_name, tc.enforced,
                  SHA2(REPLACE(REPLACE(LOWER(cc.check_clause), '`', ''), ' ', ''), 256)
                    AS clause_hash
           FROM information_schema.table_constraints tc
           JOIN information_schema.check_constraints cc
             ON cc.constraint_schema = tc.constraint_schema
            AND cc.constraint_name = tc.constraint_name
           WHERE tc.constraint_schema = DATABASE()
             AND tc.table_name = 'merchant_crm_export_task'
             AND tc.constraint_type = 'CHECK'
         ) exact_check
         WHERE enforced = 'YES'
           AND ((constraint_name = 'chk_merchant_crm_export_paid'
                 AND clause_hash <=>
                   'e2f1899b8f0410c40d7ecbc91993b624073acba0fa72bb7e6d3833d69d1c33d2')
             OR (constraint_name = 'chk_merchant_crm_export_status'
                 AND clause_hash <=>
                   '05f9d00e2cd77ffb0cd7bc19700b6c4a0ef3916960d0b06ef3678fff31102eb4')
             OR (constraint_name = 'chk_merchant_crm_export_limit'
                 AND clause_hash <=>
                   '0c6fe7c7409460131c2fada15dc9d1b1fca39605bec02ae6bb307a9ecb29779d'))) = 3
);

SET @merchant_aftercare_target_shape_ok := (
  SELECT
    (SELECT COUNT(*) FROM information_schema.tables
     WHERE table_schema = DATABASE() AND table_name = 'merchant_aftercare_response'
       AND engine = 'InnoDB' AND table_collation = 'utf8mb4_0900_ai_ci') = 1
    AND (SELECT COUNT(*) FROM information_schema.triggers
         WHERE event_object_schema = DATABASE()
           AND event_object_table = 'merchant_aftercare_response') = 0
    AND (SELECT COUNT(*) = 11
           AND GROUP_CONCAT(column_name ORDER BY ordinal_position SEPARATOR ',') =
             'id,refund_application_id,merchant_id,merchant_owner_member_id,actor_member_id,actor_role_code,decision,content,evidence_url,request_id,create_time'
           AND SUM(column_name = 'merchant_id' AND column_type = 'bigint'
                   AND is_nullable = 'NO') = 1
           AND SUM(column_name = 'merchant_owner_member_id' AND column_type = 'bigint'
                   AND is_nullable = 'NO') = 1
           AND SUM(column_name = 'request_id' AND column_type = 'varchar(64)'
                   AND is_nullable = 'NO' AND character_set_name = 'ascii'
                   AND collation_name = 'ascii_bin') = 1
         FROM information_schema.columns
         WHERE table_schema = DATABASE() AND table_name = 'merchant_aftercare_response')
    AND (SELECT SHA2(GROUP_CONCAT(CONCAT(
             'n=', IF(column_name IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(column_name AS BINARY)), ':',
                      HEX(CAST(column_name AS BINARY)))),
             '|t=', IF(column_type IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(column_type AS BINARY)), ':',
                      HEX(CAST(column_type AS BINARY)))),
             '|u=', IF(is_nullable IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(is_nullable AS BINARY)), ':',
                      HEX(CAST(is_nullable AS BINARY)))),
             '|d=', IF(column_default IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(column_default AS BINARY)), ':',
                      HEX(CAST(column_default AS BINARY)))),
             '|e=', IF(extra IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(extra AS BINARY)), ':',
                      HEX(CAST(extra AS BINARY)))),
             '|c=', IF(character_set_name IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(character_set_name AS BINARY)), ':',
                      HEX(CAST(character_set_name AS BINARY)))),
             '|l=', IF(collation_name IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(collation_name AS BINARY)), ':',
                      HEX(CAST(collation_name AS BINARY)))),
             '|g=', IF(generation_expression IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(generation_expression AS BINARY)), ':',
                      HEX(CAST(generation_expression AS BINARY)))))
           ORDER BY ordinal_position SEPARATOR ';'), 256)
         FROM information_schema.columns
         WHERE table_schema = DATABASE() AND table_name = 'merchant_aftercare_response')
         = '5153d3c140c6b9a9489484c911ddac1695f3e6cf0a2db90ed72f4327a460d2ad'
    AND (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
         WHERE table_schema = DATABASE()
           AND table_name = 'merchant_aftercare_response') = 4
    AND (SELECT COUNT(*) FROM (
           SELECT index_name, MIN(non_unique) AS non_unique, MIN(index_type) AS index_type,
                  MIN(is_visible) AS is_visible, COUNT(sub_part) AS prefix_count,
                  GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') AS cols
           FROM information_schema.statistics
           WHERE table_schema = DATABASE() AND table_name = 'merchant_aftercare_response'
           GROUP BY index_name
         ) exact_index
         WHERE prefix_count = 0 AND index_type = 'BTREE' AND is_visible = 'YES'
           AND ((index_name = 'PRIMARY' AND non_unique = 0 AND cols = 'id')
             OR (index_name = 'uk_merchant_aftercare_request' AND non_unique = 0
                 AND cols = 'merchant_id,request_id')
             OR (index_name = 'idx_merchant_aftercare_refund' AND non_unique = 1
                 AND cols = 'merchant_id,refund_application_id,id')
             OR (index_name = 'idx_merchant_aftercare_refund_lookup' AND non_unique = 1
                 AND cols = 'refund_application_id,merchant_id,id'))) = 4
    AND @merchant_aftercare_checks_exact_ok
);

SET @merchant_crm_export_target_shape_ok := (
  SELECT
    (SELECT COUNT(*) FROM information_schema.tables
     WHERE table_schema = DATABASE() AND table_name = 'merchant_crm_export_task'
       AND engine = 'InnoDB' AND table_collation = 'utf8mb4_0900_ai_ci') = 1
    AND (SELECT COUNT(*) FROM information_schema.triggers
         WHERE event_object_schema = DATABASE()
           AND event_object_table = 'merchant_crm_export_task') = 0
    AND (SELECT COUNT(*) = 22
           AND GROUP_CONCAT(column_name ORDER BY ordinal_position SEPARATOR ',') =
             'id,merchant_id,merchant_owner_member_id,requester_member_id,requester_role_code,filter_json,include_paid_amount,status,version,row_limit,row_count,storage_key,token_hash,request_id,error_message,expires_at,started_at,finished_at,downloaded_at,download_count,create_time,update_time'
           AND SUM(column_name = 'merchant_id' AND column_type = 'bigint'
                   AND is_nullable = 'NO') = 1
           AND SUM(column_name = 'merchant_owner_member_id' AND column_type = 'bigint'
                   AND is_nullable = 'NO') = 1
           AND SUM(column_name = 'merchant_row_id') = 0
           AND SUM(column_name = 'request_id' AND column_type = 'varchar(64)'
                   AND is_nullable = 'NO' AND character_set_name = 'utf8mb4'
                   AND collation_name LIKE 'utf8mb4%') = 1
         FROM information_schema.columns
         WHERE table_schema = DATABASE() AND table_name = 'merchant_crm_export_task')
    AND (SELECT SHA2(GROUP_CONCAT(CONCAT(
             'n=', IF(column_name IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(column_name AS BINARY)), ':',
                      HEX(CAST(column_name AS BINARY)))),
             '|t=', IF(column_type IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(column_type AS BINARY)), ':',
                      HEX(CAST(column_type AS BINARY)))),
             '|u=', IF(is_nullable IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(is_nullable AS BINARY)), ':',
                      HEX(CAST(is_nullable AS BINARY)))),
             '|d=', IF(column_default IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(column_default AS BINARY)), ':',
                      HEX(CAST(column_default AS BINARY)))),
             '|e=', IF(extra IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(extra AS BINARY)), ':',
                      HEX(CAST(extra AS BINARY)))),
             '|c=', IF(character_set_name IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(character_set_name AS BINARY)), ':',
                      HEX(CAST(character_set_name AS BINARY)))),
             '|l=', IF(collation_name IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(collation_name AS BINARY)), ':',
                      HEX(CAST(collation_name AS BINARY)))),
             '|g=', IF(generation_expression IS NULL, 'N',
               CONCAT('V', OCTET_LENGTH(CAST(generation_expression AS BINARY)), ':',
                      HEX(CAST(generation_expression AS BINARY)))))
           ORDER BY ordinal_position SEPARATOR ';'), 256)
         FROM information_schema.columns
         WHERE table_schema = DATABASE() AND table_name = 'merchant_crm_export_task')
         = '5a67980d0a396e2a802c2e73903111165c92645a87eb8a599f231338e2f43943'
    AND (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
         WHERE table_schema = DATABASE()
           AND table_name = 'merchant_crm_export_task') = 4
    AND (SELECT COUNT(*) FROM (
           SELECT index_name, MIN(non_unique) AS non_unique, MIN(index_type) AS index_type,
                  MIN(is_visible) AS is_visible, COUNT(sub_part) AS prefix_count,
                  GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') AS cols
           FROM information_schema.statistics
           WHERE table_schema = DATABASE() AND table_name = 'merchant_crm_export_task'
           GROUP BY index_name
         ) exact_index
         WHERE prefix_count = 0 AND index_type = 'BTREE' AND is_visible = 'YES'
           AND ((index_name = 'PRIMARY' AND non_unique = 0 AND cols = 'id')
             OR (index_name = 'uk_merchant_crm_export_request' AND non_unique = 0
                 AND cols = 'merchant_id,request_id')
             OR (index_name = 'idx_merchant_crm_export_owner' AND non_unique = 1
                 AND cols = 'merchant_id,id')
             OR (index_name = 'idx_merchant_crm_export_runner' AND non_unique = 1
                 AND cols = 'status,expires_at,create_time,id'))) = 4
    AND @merchant_crm_export_checks_exact_ok
);

-- runner: assert=scalar-true
SELECT @merchant_aftercare_target_shape_ok
   AND @merchant_crm_export_target_shape_ok
   AND (SELECT COUNT(*) FROM merchant_aftercare_response) = @merchant_aftercare_before_rows
   AND (SELECT COUNT(*) FROM merchant_crm_export_task) = @merchant_crm_export_before_rows
   AND (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'merchant_crm_export_task'
          AND column_name = 'merchant_row_id') = 0
  AS assertion_ok;
