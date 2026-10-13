-- 商家退款售后（排在 merchant_operator 后执行）：只追加意见/凭证，不改 refund_application 审核态或打款态。
-- 前置：migration_business_audit_event_20260823.sql、migration_merchant_operator_20260823.sql。
-- 仅生成迁移文件；上线前必须单独走 chengyin-db-migrate 预检、执行与业务读回。
-- CREATE 原子提交后若在回读前中断，重跑必须识别精确目标表并 no-op；任何其他残表均 fail closed。

SET @merchant_aftercare_response_table_exists := (
  SELECT COUNT(*)
  FROM information_schema.tables
  WHERE table_schema = DATABASE() AND table_name = 'merchant_aftercare_response'
);
SET @merchant_aftercare_refund_lookup_definition := (
  SELECT IFNULL(CONCAT(MIN(non_unique), ':', MIN(index_type), ':', MIN(is_visible), ':',
                       COUNT(sub_part), ':',
                       GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')), '')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'merchant_aftercare_response'
    AND index_name = 'idx_merchant_aftercare_refund_lookup'
);
SET @merchant_aftercare_response_shape_ok := (
  SELECT (SELECT COUNT(*)
          FROM information_schema.tables
          WHERE table_schema = DATABASE() AND table_name = 'merchant_aftercare_response'
            AND engine = 'InnoDB' AND table_collation LIKE 'utf8mb4%') = 1
    AND (SELECT COUNT(*) = 11
              AND SUM(column_default IS NOT NULL) = 0
              AND SUM(column_name = 'id' AND LOWER(data_type) = 'bigint'
                      AND is_nullable = 'NO' AND LOWER(extra) = 'auto_increment') = 1
              AND SUM(column_name = 'refund_application_id' AND LOWER(data_type) = 'bigint'
                      AND is_nullable = 'NO') = 1
              AND SUM(column_name = 'merchant_id' AND LOWER(data_type) = 'bigint'
                      AND is_nullable = 'NO') = 1
              AND SUM(column_name = 'merchant_owner_member_id' AND LOWER(data_type) = 'bigint'
                      AND is_nullable = 'NO') = 1
              AND SUM(column_name = 'actor_member_id' AND LOWER(data_type) = 'bigint'
                      AND is_nullable = 'NO') = 1
              AND SUM(column_name = 'actor_role_code' AND LOWER(data_type) = 'varchar'
                      AND character_maximum_length = 32 AND is_nullable = 'NO') = 1
              AND SUM(column_name = 'decision' AND LOWER(data_type) = 'varchar'
                      AND character_maximum_length = 16 AND is_nullable = 'NO') = 1
              AND SUM(column_name = 'content' AND LOWER(data_type) = 'varchar'
                      AND character_maximum_length = 500 AND is_nullable = 'YES') = 1
              AND SUM(column_name = 'evidence_url' AND LOWER(data_type) = 'varchar'
                      AND character_maximum_length = 512 AND is_nullable = 'YES') = 1
              AND SUM(column_name = 'request_id' AND LOWER(data_type) = 'varchar'
                      AND character_maximum_length = 64 AND is_nullable = 'NO'
                      AND character_set_name = 'ascii'
                      AND collation_name = 'ascii_bin') = 1
              AND SUM(column_name = 'create_time' AND LOWER(data_type) = 'datetime'
                      AND datetime_precision = 0 AND is_nullable = 'NO') = 1
         FROM information_schema.columns
         WHERE table_schema = DATABASE() AND table_name = 'merchant_aftercare_response')
    AND (SELECT COUNT(*) FROM (
           SELECT index_name, MIN(non_unique) AS non_unique, MIN(index_type) AS index_type,
                  MIN(is_visible) AS is_visible, COUNT(sub_part) AS prefix_count,
                  GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') AS cols
           FROM information_schema.statistics
           WHERE table_schema = DATABASE() AND table_name = 'merchant_aftercare_response'
             AND index_name IN ('PRIMARY','uk_merchant_aftercare_request','idx_merchant_aftercare_refund')
           GROUP BY index_name
         ) exact_index
         WHERE prefix_count = 0 AND index_type = 'BTREE' AND is_visible = 'YES'
           AND ((index_name = 'PRIMARY' AND non_unique = 0 AND cols = 'id')
             OR (index_name = 'uk_merchant_aftercare_request' AND non_unique = 0
                 AND cols = 'merchant_id,request_id')
             OR (index_name = 'idx_merchant_aftercare_refund' AND non_unique = 1
                 AND cols = 'merchant_id,refund_application_id,id'))) = 3
    AND (SELECT COUNT(*)
         FROM information_schema.check_constraints
         WHERE constraint_schema = DATABASE()
           AND ((constraint_name = 'chk_merchant_aftercare_decision'
                 AND REGEXP_REPLACE(LOWER(check_clause),
                     '[[:space:]`\\\\()]|_utf8mb[34]|_utf8|_latin1|_gbk', '')
                     <=> 'decisionin''agree'',''reject'',''evidence''')
             OR (constraint_name = 'chk_merchant_aftercare_actor_role'
                 AND REGEXP_REPLACE(LOWER(check_clause),
                     '[[:space:]`\\\\()]|_utf8mb[34]|_utf8|_latin1|_gbk', '')
                     <=> 'actor_role_codein''merchant_owner'',''merchant_manager'',''merchant_finance'''))) = 2
);
-- runner: assert=scalar-true
SELECT @merchant_aftercare_response_table_exists IN (0, 1)
   AND @merchant_aftercare_refund_lookup_definition IN (
         '', '1:BTREE:YES:0:refund_application_id,merchant_id,id')
   AND (@merchant_aftercare_response_table_exists = 0
        OR @merchant_aftercare_response_shape_ok) AS assertion_ok;

SET @merchant_aftercare_response_create_sql := IF(
  @merchant_aftercare_response_table_exists = 0,
  'CREATE TABLE `merchant_aftercare_response` (
     `id` BIGINT NOT NULL AUTO_INCREMENT,
     `refund_application_id` BIGINT NOT NULL,
     `merchant_id` BIGINT NOT NULL,
     `merchant_owner_member_id` BIGINT NOT NULL,
     `actor_member_id` BIGINT NOT NULL,
     `actor_role_code` VARCHAR(32) NOT NULL,
     `decision` VARCHAR(16) NOT NULL,
     `content` VARCHAR(500) DEFAULT NULL,
     `evidence_url` VARCHAR(512) DEFAULT NULL,
     `request_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
     `create_time` DATETIME NOT NULL,
     PRIMARY KEY (id),
     UNIQUE KEY uk_merchant_aftercare_request (merchant_id, request_id),
     KEY idx_merchant_aftercare_refund (merchant_id, refund_application_id, id),
     KEY idx_merchant_aftercare_refund_lookup (refund_application_id, merchant_id, id),
     CONSTRAINT chk_merchant_aftercare_decision
       CHECK (decision IN (''AGREE'',''REJECT'',''EVIDENCE'')),
     CONSTRAINT chk_merchant_aftercare_actor_role
       CHECK (actor_role_code IN (''MERCHANT_OWNER'',''MERCHANT_MANAGER'',''MERCHANT_FINANCE''))
   ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT=''商家退款售后追加意见与凭证''',
  'SELECT 1'
);
PREPARE merchant_aftercare_response_create_stmt FROM @merchant_aftercare_response_create_sql;
EXECUTE merchant_aftercare_response_create_stmt;
DEALLOCATE PREPARE merchant_aftercare_response_create_stmt;

-- 兼容候选迁移早期版本已建表但尚无 refund_application_id 前导索引；精确形状才允许补齐。
SET @merchant_aftercare_refund_lookup_definition := (
  SELECT IFNULL(CONCAT(MIN(non_unique), ':', MIN(index_type), ':', MIN(is_visible), ':',
                       COUNT(sub_part), ':',
                       GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')), '')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'merchant_aftercare_response'
    AND index_name = 'idx_merchant_aftercare_refund_lookup'
);
SET @merchant_aftercare_refund_lookup_sql := IF(
  @merchant_aftercare_refund_lookup_definition = '',
  'ALTER TABLE `merchant_aftercare_response`
     ADD KEY `idx_merchant_aftercare_refund_lookup`
       (`refund_application_id`, `merchant_id`, `id`)',
  'SELECT 1'
);
PREPARE merchant_aftercare_refund_lookup_stmt FROM @merchant_aftercare_refund_lookup_sql;
EXECUTE merchant_aftercare_refund_lookup_stmt;
DEALLOCATE PREPARE merchant_aftercare_refund_lookup_stmt;

-- 执行后必须从目标库重新计算形状，不能复用 CREATE 前变量制造恒真回读。
SET @merchant_aftercare_response_table_exists := (
  SELECT COUNT(*)
  FROM information_schema.tables
  WHERE table_schema = DATABASE() AND table_name = 'merchant_aftercare_response'
);
SET @merchant_aftercare_response_shape_ok := (
  SELECT (SELECT COUNT(*)
          FROM information_schema.tables
          WHERE table_schema = DATABASE() AND table_name = 'merchant_aftercare_response'
            AND engine = 'InnoDB' AND table_collation LIKE 'utf8mb4%') = 1
    AND (SELECT COUNT(*) = 11
              AND SUM(column_default IS NOT NULL) = 0
              AND SUM(column_name = 'id' AND LOWER(data_type) = 'bigint'
                      AND is_nullable = 'NO' AND LOWER(extra) = 'auto_increment') = 1
              AND SUM(column_name = 'refund_application_id' AND LOWER(data_type) = 'bigint'
                      AND is_nullable = 'NO') = 1
              AND SUM(column_name = 'merchant_id' AND LOWER(data_type) = 'bigint'
                      AND is_nullable = 'NO') = 1
              AND SUM(column_name = 'merchant_owner_member_id' AND LOWER(data_type) = 'bigint'
                      AND is_nullable = 'NO') = 1
              AND SUM(column_name = 'actor_member_id' AND LOWER(data_type) = 'bigint'
                      AND is_nullable = 'NO') = 1
              AND SUM(column_name = 'actor_role_code' AND LOWER(data_type) = 'varchar'
                      AND character_maximum_length = 32 AND is_nullable = 'NO') = 1
              AND SUM(column_name = 'decision' AND LOWER(data_type) = 'varchar'
                      AND character_maximum_length = 16 AND is_nullable = 'NO') = 1
              AND SUM(column_name = 'content' AND LOWER(data_type) = 'varchar'
                      AND character_maximum_length = 500 AND is_nullable = 'YES') = 1
              AND SUM(column_name = 'evidence_url' AND LOWER(data_type) = 'varchar'
                      AND character_maximum_length = 512 AND is_nullable = 'YES') = 1
              AND SUM(column_name = 'request_id' AND LOWER(data_type) = 'varchar'
                      AND character_maximum_length = 64 AND is_nullable = 'NO'
                      AND character_set_name = 'ascii'
                      AND collation_name = 'ascii_bin') = 1
              AND SUM(column_name = 'create_time' AND LOWER(data_type) = 'datetime'
                      AND datetime_precision = 0 AND is_nullable = 'NO') = 1
         FROM information_schema.columns
         WHERE table_schema = DATABASE() AND table_name = 'merchant_aftercare_response')
    AND (SELECT COUNT(*) FROM (
           SELECT index_name, MIN(non_unique) AS non_unique, MIN(index_type) AS index_type,
                  MIN(is_visible) AS is_visible, COUNT(sub_part) AS prefix_count,
                  GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') AS cols
           FROM information_schema.statistics
           WHERE table_schema = DATABASE() AND table_name = 'merchant_aftercare_response'
             AND index_name IN ('PRIMARY','uk_merchant_aftercare_request','idx_merchant_aftercare_refund')
           GROUP BY index_name
         ) exact_index
         WHERE prefix_count = 0 AND index_type = 'BTREE' AND is_visible = 'YES'
           AND ((index_name = 'PRIMARY' AND non_unique = 0 AND cols = 'id')
             OR (index_name = 'uk_merchant_aftercare_request' AND non_unique = 0
                 AND cols = 'merchant_id,request_id')
             OR (index_name = 'idx_merchant_aftercare_refund' AND non_unique = 1
                 AND cols = 'merchant_id,refund_application_id,id'))) = 3
    AND (SELECT COUNT(*)
         FROM information_schema.check_constraints
         WHERE constraint_schema = DATABASE()
           AND ((constraint_name = 'chk_merchant_aftercare_decision'
                 AND REGEXP_REPLACE(LOWER(check_clause),
                     '[[:space:]`\\\\()]|_utf8mb[34]|_utf8|_latin1|_gbk', '')
                     <=> 'decisionin''agree'',''reject'',''evidence''')
             OR (constraint_name = 'chk_merchant_aftercare_actor_role'
                 AND REGEXP_REPLACE(LOWER(check_clause),
                     '[[:space:]`\\\\()]|_utf8mb[34]|_utf8|_latin1|_gbk', '')
                     <=> 'actor_role_codein''merchant_owner'',''merchant_manager'',''merchant_finance'''))) = 2
);
SET @merchant_aftercare_refund_lookup_definition := (
  SELECT IFNULL(CONCAT(MIN(non_unique), ':', MIN(index_type), ':', MIN(is_visible), ':',
                       COUNT(sub_part), ':',
                       GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')), '')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'merchant_aftercare_response'
    AND index_name = 'idx_merchant_aftercare_refund_lookup'
);
-- runner: assert=scalar-true
SELECT @merchant_aftercare_response_table_exists = 1
   AND @merchant_aftercare_response_shape_ok
   AND @merchant_aftercare_refund_lookup_definition =
         '1:BTREE:YES:0:refund_application_id,merchant_id,id' AS assertion_ok;
