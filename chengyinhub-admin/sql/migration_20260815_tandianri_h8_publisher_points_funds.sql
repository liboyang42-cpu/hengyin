-- ORDER 1/2：先建立 H8 积分规则、快照列与全局幂等键，再执行费率收敛 migration。
-- 探店日 H8：发布者/活动设计者现金分润改为每主题一次的版本化积分奖励。
-- 本 migration 必须先于包含 H8 writer 的 jar 部署；未落库时运行时会因 creator_rate 非零 fail closed。

CREATE TEMPORARY TABLE tandianri_h8_guard (
    id INT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;
INSERT INTO tandianri_h8_guard (id) VALUES (1);

-- event 15 / id 15 只允许“同一条 H8 规则”占用；可预判冲突必须在任何持久 DDL/DML 前停止。
INSERT INTO tandianri_h8_guard (id)
SELECT 1
WHERE EXISTS (
        SELECT 1 FROM cms_points_setting
        WHERE id = 15 AND NOT (
            type = 1 AND event_type = 15 AND title = '作品结算奖励'
            AND value = 100 AND status = 1 AND del_flag = 0
        )
    )
   OR EXISTS (
        SELECT 1 FROM cms_points_setting
        WHERE type = 1 AND event_type = 15 AND id <> 15
    )
   OR (
        NOT EXISTS (
            SELECT 1 FROM cms_points_setting
            WHERE id = 15 AND type = 1 AND event_type = 15
              AND title = '作品结算奖励' AND value = 100 AND status = 1 AND del_flag = 0
        )
        AND EXISTS (
            SELECT 1 FROM ums_member_points_detail
            WHERE event_type = 15
        )
    )
   OR EXISTS (
        SELECT event_id
        FROM ums_member_points_detail
        WHERE event_type = 15 AND change_type = 1
        GROUP BY event_id
        HAVING COUNT(*) > 1
    )
   OR EXISTS (
        SELECT 1 FROM ums_member_points_detail
        WHERE event_type = 15 AND change_type = 1 AND event_id IS NULL
    );

-- 同名错列不能被“列已存在”跳过；否则 writer 会在部分落库后才暴露。
INSERT INTO tandianri_h8_guard (id)
SELECT 1
WHERE EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'cms_points_setting'
          AND column_name = 'rule_version'
          AND NOT (
              data_type = 'bigint' AND is_nullable = 'NO' AND column_default = '1'
              AND COALESCE(extra, '') = '' AND COALESCE(generation_expression, '') = ''
          )
    )
   OR EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'ums_member_points_detail'
          AND column_name = 'points_rule_id'
          AND NOT (
              data_type = 'bigint' AND is_nullable = 'YES' AND column_default IS NULL
              AND COALESCE(extra, '') = '' AND COALESCE(generation_expression, '') = ''
          )
    )
   OR EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'ums_member_points_detail'
          AND column_name = 'points_rule_version'
          AND NOT (
              data_type = 'bigint' AND is_nullable = 'YES' AND column_default IS NULL
              AND COALESCE(extra, '') = '' AND COALESCE(generation_expression, '') = ''
          )
    )
   OR EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'ums_member_points_detail'
          AND column_name = 'points_rule_value'
          AND NOT (
              column_type = 'decimal(12,2)' AND is_nullable = 'YES' AND column_default IS NULL
              AND COALESCE(extra, '') = '' AND COALESCE(generation_expression, '') = ''
          )
    )
   OR EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'ums_member_points_detail'
          AND column_name = 'publisher_settlement_topic_id'
          AND NOT (
              data_type = 'bigint' AND is_nullable = 'YES'
              AND column_default IS NULL AND COALESCE(extra, '') = ''
              AND COALESCE(generation_expression, '') = ''
          )
    );

SET @h8_existing_topic_key_guard_sql := IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'ums_member_points_detail'
       AND column_name = 'publisher_settlement_topic_id') = 1,
    'INSERT INTO tandianri_h8_guard (id) SELECT 1 WHERE EXISTS (
         SELECT 1 FROM ums_member_points_detail
         WHERE publisher_settlement_topic_id IS NOT NULL
           AND NOT (event_type = 15 AND change_type = 1
                    AND publisher_settlement_topic_id = event_id)
     )',
    'SELECT 1'
);
PREPARE h8_existing_topic_key_guard_stmt FROM @h8_existing_topic_key_guard_sql;
EXECUTE h8_existing_topic_key_guard_stmt;
DEALLOCATE PREPARE h8_existing_topic_key_guard_stmt;

-- 错名索引也必须在任何持久 ALTER 前拒绝，避免先加列后才因索引冲突留下部分状态。
SET @h8_topic_unique_definition := (
    SELECT CONCAT(non_unique, ':', GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ','))
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'ums_member_points_detail'
      AND index_name = 'uk_publisher_settlement_topic'
    GROUP BY non_unique
);
INSERT INTO tandianri_h8_guard (id)
SELECT 1
WHERE @h8_topic_unique_definition IS NOT NULL
  AND @h8_topic_unique_definition <> '0:publisher_settlement_topic_id';

SET @h8_rule_version_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'cms_points_setting'
      AND column_name = 'rule_version'
);
SET @h8_add_rule_version_sql := IF(
    @h8_rule_version_exists = 0,
    'ALTER TABLE cms_points_setting ADD COLUMN rule_version BIGINT NOT NULL DEFAULT 1 COMMENT ''积分规则版本；每次后台修改递增'' AFTER event_type',
    'SELECT 1'
);
PREPARE h8_add_rule_version_stmt FROM @h8_add_rule_version_sql;
EXECUTE h8_add_rule_version_stmt;
DEALLOCATE PREPARE h8_add_rule_version_stmt;

SET @h8_snapshot_rule_id_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ums_member_points_detail'
      AND column_name = 'points_rule_id'
);
SET @h8_add_snapshot_rule_id_sql := IF(
    @h8_snapshot_rule_id_exists = 0,
    'ALTER TABLE ums_member_points_detail ADD COLUMN points_rule_id BIGINT NULL COMMENT ''发奖时积分规则ID快照'' AFTER reward_business_key',
    'SELECT 1'
);
PREPARE h8_add_snapshot_rule_id_stmt FROM @h8_add_snapshot_rule_id_sql;
EXECUTE h8_add_snapshot_rule_id_stmt;
DEALLOCATE PREPARE h8_add_snapshot_rule_id_stmt;

SET @h8_snapshot_rule_version_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ums_member_points_detail'
      AND column_name = 'points_rule_version'
);
SET @h8_add_snapshot_rule_version_sql := IF(
    @h8_snapshot_rule_version_exists = 0,
    'ALTER TABLE ums_member_points_detail ADD COLUMN points_rule_version BIGINT NULL COMMENT ''发奖时积分规则版本快照'' AFTER points_rule_id',
    'SELECT 1'
);
PREPARE h8_add_snapshot_rule_version_stmt FROM @h8_add_snapshot_rule_version_sql;
EXECUTE h8_add_snapshot_rule_version_stmt;
DEALLOCATE PREPARE h8_add_snapshot_rule_version_stmt;

SET @h8_snapshot_rule_value_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ums_member_points_detail'
      AND column_name = 'points_rule_value'
);
SET @h8_add_snapshot_rule_value_sql := IF(
    @h8_snapshot_rule_value_exists = 0,
    'ALTER TABLE ums_member_points_detail ADD COLUMN points_rule_value DECIMAL(12,2) NULL COMMENT ''发奖时积分规则值快照'' AFTER points_rule_version',
    'SELECT 1'
);
PREPARE h8_add_snapshot_rule_value_stmt FROM @h8_add_snapshot_rule_value_sql;
EXECUTE h8_add_snapshot_rule_value_stmt;
DEALLOCATE PREPARE h8_add_snapshot_rule_value_stmt;

-- 组合键(member_id,reward_business_key)只能防同一人重发。H8 要求全链路 topicId 最多一次，
-- 因此由专用 writer 显式写入可空投影列；通用 CRUD 拒绝该列，唯一索引负责并发仲裁。
SET @h8_topic_key_column_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ums_member_points_detail'
      AND column_name = 'publisher_settlement_topic_id'
);
SET @h8_add_topic_key_column_sql := IF(
    @h8_topic_key_column_exists = 0,
    'ALTER TABLE ums_member_points_detail ADD COLUMN publisher_settlement_topic_id BIGINT NULL COMMENT ''event 15 全局 topic 幂等键；仅专用 writer 写入'' AFTER points_rule_value',
    'SELECT 1'
);
PREPARE h8_add_topic_key_column_stmt FROM @h8_add_topic_key_column_sql;
EXECUTE h8_add_topic_key_column_stmt;
DEALLOCATE PREPARE h8_add_topic_key_column_stmt;

-- 兼容 migration 前已存在且唯一的 event 15 流水；错值已在持久变更前被 guard 拒绝。
UPDATE ums_member_points_detail
SET publisher_settlement_topic_id = event_id
WHERE event_type = 15 AND change_type = 1
  AND publisher_settlement_topic_id IS NULL;

SET @h8_add_topic_unique_sql := IF(
    @h8_topic_unique_definition IS NULL,
    'ALTER TABLE ums_member_points_detail ADD UNIQUE KEY uk_publisher_settlement_topic (publisher_settlement_topic_id)',
    'SELECT 1'
);
PREPARE h8_add_topic_unique_stmt FROM @h8_add_topic_unique_sql;
EXECUTE h8_add_topic_unique_stmt;
DEALLOCATE PREPARE h8_add_topic_unique_stmt;

INSERT INTO cms_points_setting
    (id, title, value, description, type, event_type, rule_version, status,
     sort_order, del_flag, create_by, create_time)
SELECT 15, '作品结算奖励', 100, '主题现金结算成功后，发布者/活动设计者每主题一次',
       1, 15, 1, 1, 15, 0, 'admin', NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM cms_points_setting WHERE id = 15 AND type = 1 AND event_type = 15
);

-- runner: assert=scalar-true
SELECT (
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'cms_points_setting'
       AND column_name = 'rule_version'
       AND data_type = 'bigint' AND is_nullable = 'NO' AND column_default = '1'
       AND COALESCE(extra, '') = '' AND COALESCE(generation_expression, '') = '') = 1
    AND
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'ums_member_points_detail'
       AND column_name IN ('points_rule_id', 'points_rule_version')
       AND data_type = 'bigint' AND is_nullable = 'YES' AND column_default IS NULL
       AND COALESCE(extra, '') = '' AND COALESCE(generation_expression, '') = '') = 2
    AND
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'ums_member_points_detail'
       AND column_name = 'points_rule_value'
       AND column_type = 'decimal(12,2)' AND is_nullable = 'YES' AND column_default IS NULL
       AND COALESCE(extra, '') = '' AND COALESCE(generation_expression, '') = '') = 1
    AND
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'ums_member_points_detail'
       AND column_name = 'publisher_settlement_topic_id') = 1
    AND
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'ums_member_points_detail'
       AND column_name = 'publisher_settlement_topic_id'
       AND data_type = 'bigint' AND is_nullable = 'YES'
       AND column_default IS NULL AND COALESCE(extra, '') = ''
       AND COALESCE(generation_expression, '') = '') = 1
    AND
    (SELECT COUNT(*) FROM ums_member_points_detail
     WHERE event_type = 15 AND change_type = 1
       AND publisher_settlement_topic_id = event_id)
       =
    (SELECT COUNT(*) FROM ums_member_points_detail
     WHERE event_type = 15 AND change_type = 1)
    AND
    (SELECT CONCAT(non_unique, ':', GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ','))
     FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'ums_member_points_detail'
       AND index_name = 'uk_publisher_settlement_topic'
     GROUP BY non_unique) = '0:publisher_settlement_topic_id'
) AS assertion_ok;

-- runner: assert=scalar-true
SELECT COUNT(*) = 1 AS assertion_ok
FROM cms_points_setting
WHERE id = 15 AND type = 1 AND event_type = 15 AND title = '作品结算奖励' AND value = 100
  AND rule_version >= 1 AND status = 1 AND del_flag = 0;

DROP TEMPORARY TABLE tandianri_h8_guard;

SELECT id, title, value, type, event_type, rule_version, status, del_flag
FROM cms_points_setting
WHERE id = 15 AND event_type = 15;
