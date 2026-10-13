-- 商家 CRM 跟进闭环。标签定义复用既有 merchant_customer_tag，禁止第二套同义真源。
-- merchant_id 自本迁移起统一冻结为 mms_merchant.id；owner memberId 只用于历史报名事实归集。
-- 【前置】migration_p3_phase_c_crm_finance.sql 已创建 merchant_customer_tag / uk_merchant_tag_code；
--          migration_merchant_operator_20260823.sql 已提供员工身份与 CRM_READ / CRM_SEGMENT 权限。

-- MySQL 8 不支持 ADD COLUMN IF NOT EXISTS；列定义只允许「尚未新增」或目标形状，其他状态 fail closed。
SET @merchant_customer_profile_scope_definition := (
  SELECT COALESCE(MAX(CONCAT(LOWER(data_type), ':', is_nullable, ':',
                             COALESCE(column_default, '<NULL>'))), '')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'merchant_customer_profile'
    AND column_name = 'merchant_scope_version'
);
SET @merchant_customer_tag_scope_definition := (
  SELECT COALESCE(MAX(CONCAT(LOWER(data_type), ':', is_nullable, ':',
                             COALESCE(column_default, '<NULL>'))), '')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'merchant_customer_tag'
    AND column_name = 'merchant_scope_version'
);
-- runner: assert=scalar-true
SELECT @merchant_customer_profile_scope_definition IN ('', 'tinyint:NO:0')
   AND @merchant_customer_tag_scope_definition IN ('', 'tinyint:NO:0') AS assertion_ok;

SET @merchant_customer_profile_scope_column_sql := IF(
  @merchant_customer_profile_scope_definition = '',
  'ALTER TABLE `merchant_customer_profile` ADD COLUMN `merchant_scope_version` tinyint NOT NULL DEFAULT 0 COMMENT ''0=待人工归属的历史owner口径/2=mms_merchant.id门店口径'' AFTER `merchant_id`',
  'SELECT 1'
);
PREPARE merchant_customer_profile_scope_column_stmt FROM @merchant_customer_profile_scope_column_sql;
EXECUTE merchant_customer_profile_scope_column_stmt;
DEALLOCATE PREPARE merchant_customer_profile_scope_column_stmt;

SET @merchant_customer_tag_scope_column_sql := IF(
  @merchant_customer_tag_scope_definition = '',
  'ALTER TABLE `merchant_customer_tag` ADD COLUMN `merchant_scope_version` tinyint NOT NULL DEFAULT 0 COMMENT ''0=待人工归属的历史owner口径/2=mms_merchant.id门店口径'' AFTER `merchant_id`',
  'SELECT 1'
);
PREPARE merchant_customer_tag_scope_column_stmt FROM @merchant_customer_tag_scope_column_sql;
EXECUTE merchant_customer_tag_scope_column_stmt;
DEALLOCATE PREPARE merchant_customer_tag_scope_column_stmt;

-- 旧唯一键把 owner memberId 与 merchant rowId 放在同一数字 namespace；version 必须进入唯一键。
SET @merchant_customer_profile_legacy_unique := (
  SELECT IFNULL(CONCAT(MIN(non_unique), ':', GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')), '')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'merchant_customer_profile'
    AND index_name = 'uk_merchant_member'
);
SET @merchant_customer_profile_scope_unique := (
  SELECT IFNULL(CONCAT(MIN(non_unique), ':', GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')), '')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'merchant_customer_profile'
    AND index_name = 'uk_merchant_scope_member'
);
SET @merchant_customer_tag_legacy_unique := (
  SELECT IFNULL(CONCAT(MIN(non_unique), ':', GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')), '')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'merchant_customer_tag'
    AND index_name = 'uk_merchant_tag_code'
);
SET @merchant_customer_tag_scope_unique := (
  SELECT IFNULL(CONCAT(MIN(non_unique), ':', GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')), '')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'merchant_customer_tag'
    AND index_name = 'uk_merchant_scope_tag_code'
);
-- runner: assert=scalar-true
SELECT ((@merchant_customer_profile_legacy_unique = '0:merchant_id,member_id'
          AND @merchant_customer_profile_scope_unique = '')
        OR (@merchant_customer_profile_legacy_unique = ''
          AND @merchant_customer_profile_scope_unique = '0:merchant_id,merchant_scope_version,member_id'))
   AND ((@merchant_customer_tag_legacy_unique = '0:merchant_id,tag_code'
          AND @merchant_customer_tag_scope_unique = '')
        OR (@merchant_customer_tag_legacy_unique = ''
          AND @merchant_customer_tag_scope_unique = '0:merchant_id,merchant_scope_version,tag_code'))
  AS assertion_ok;

SET @merchant_customer_profile_scope_unique_sql := IF(
  @merchant_customer_profile_scope_unique = '0:merchant_id,merchant_scope_version,member_id',
  'SELECT 1',
  'ALTER TABLE `merchant_customer_profile` DROP INDEX `uk_merchant_member`, ADD UNIQUE KEY `uk_merchant_scope_member` (`merchant_id`, `merchant_scope_version`, `member_id`)'
);
PREPARE merchant_customer_profile_scope_unique_stmt FROM @merchant_customer_profile_scope_unique_sql;
EXECUTE merchant_customer_profile_scope_unique_stmt;
DEALLOCATE PREPARE merchant_customer_profile_scope_unique_stmt;

SET @merchant_customer_tag_scope_unique_sql := IF(
  @merchant_customer_tag_scope_unique = '0:merchant_id,merchant_scope_version,tag_code',
  'SELECT 1',
  'ALTER TABLE `merchant_customer_tag` DROP INDEX `uk_merchant_tag_code`, ADD UNIQUE KEY `uk_merchant_scope_tag_code` (`merchant_id`, `merchant_scope_version`, `tag_code`)'
);
PREPARE merchant_customer_tag_scope_unique_stmt FROM @merchant_customer_tag_scope_unique_sql;
EXECUTE merchant_customer_tag_scope_unique_stmt;
DEALLOCATE PREPARE merchant_customer_tag_scope_unique_stmt;

-- v0 与目标门店既有 v2 行冲突时禁止猜测、覆盖或删除；由迁移执行人先人工归属。
-- runner: assert=scalar-true
SELECT NOT EXISTS (
         SELECT 1
         FROM merchant_customer_profile legacy_profile
         JOIN (
           SELECT member_id AS owner_member_id, MIN(id) AS merchant_id
           FROM mms_merchant WHERE status = 1 AND account_status = 1 AND del_flag = 0
           GROUP BY member_id HAVING COUNT(*) = 1
         ) exact_merchant ON exact_merchant.owner_member_id = legacy_profile.merchant_id
         JOIN merchant_customer_profile exact_profile
           ON exact_profile.merchant_id = exact_merchant.merchant_id
          AND exact_profile.merchant_scope_version = 2
          AND exact_profile.member_id = legacy_profile.member_id
          AND exact_profile.id <> legacy_profile.id
         WHERE legacy_profile.merchant_scope_version = 0
       )
   AND NOT EXISTS (
         SELECT 1
         FROM merchant_customer_tag legacy_tag
         JOIN (
           SELECT member_id AS owner_member_id, MIN(id) AS merchant_id
           FROM mms_merchant WHERE status = 1 AND account_status = 1 AND del_flag = 0
           GROUP BY member_id HAVING COUNT(*) = 1
         ) exact_merchant ON exact_merchant.owner_member_id = legacy_tag.merchant_id
         JOIN merchant_customer_tag exact_tag
           ON exact_tag.merchant_id = exact_merchant.merchant_id
          AND exact_tag.merchant_scope_version = 2
          AND exact_tag.tag_code = legacy_tag.tag_code
          AND exact_tag.id <> legacy_tag.id
         WHERE legacy_tag.merchant_scope_version = 0
       ) AS assertion_ok;

-- 仅 owner -> 单一有效门店时自动迁移；同 owner 多门店无法从旧数据反推来源门店，保留 version=0 隔离。
UPDATE `merchant_customer_profile` profile
JOIN (
  SELECT `member_id` AS owner_member_id, MIN(`id`) AS merchant_id
  FROM `mms_merchant`
  WHERE `status` = 1 AND `account_status` = 1 AND `del_flag` = 0
  GROUP BY `member_id`
  HAVING COUNT(*) = 1
) exact_merchant ON exact_merchant.owner_member_id = profile.merchant_id
SET profile.merchant_id = exact_merchant.merchant_id,
    profile.merchant_scope_version = 2
WHERE profile.merchant_scope_version = 0
  AND profile.merchant_id = exact_merchant.owner_member_id;

UPDATE `merchant_customer_tag` tag
JOIN (
  SELECT `member_id` AS owner_member_id, MIN(`id`) AS merchant_id
  FROM `mms_merchant`
  WHERE `status` = 1 AND `account_status` = 1 AND `del_flag` = 0
  GROUP BY `member_id`
  HAVING COUNT(*) = 1
) exact_merchant ON exact_merchant.owner_member_id = tag.merchant_id
SET tag.merchant_id = exact_merchant.merchant_id,
    tag.merchant_scope_version = 2
WHERE tag.merchant_scope_version = 0
  AND tag.merchant_id = exact_merchant.owner_member_id;

CREATE TABLE IF NOT EXISTS `merchant_customer_tag_assignment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `merchant_id` bigint NOT NULL COMMENT '门店主键 mms_merchant.id',
  `merchant_scope_version` tinyint NOT NULL DEFAULT 0 COMMENT '0=隔离的历史owner口径/2=mms_merchant.id门店口径',
  `member_id` bigint NOT NULL COMMENT '真实报名消费者 ums_member.id',
  `tag_id` bigint NOT NULL,
  `action` varchar(16) NOT NULL COMMENT 'ASSIGN/REMOVE，追加事件不改历史',
  `actor_member_id` bigint NOT NULL COMMENT '实际操作员工/店主',
  `request_id` varchar(64) NOT NULL COMMENT '客户端单次操作幂等键',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_merchant_customer_tag_request` (`merchant_id`, `merchant_scope_version`, `request_id`),
  KEY `idx_merchant_customer_tag_lookup` (`merchant_id`, `merchant_scope_version`, `member_id`, `tag_id`, `id`),
  CONSTRAINT `chk_merchant_customer_tag_action` CHECK (`action` IN ('ASSIGN', 'REMOVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家 CRM 客户标签关系';

CREATE TABLE IF NOT EXISTS `merchant_customer_note` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `merchant_id` bigint NOT NULL COMMENT '门店主键 mms_merchant.id',
  `merchant_scope_version` tinyint NOT NULL DEFAULT 0 COMMENT '0=隔离的历史owner口径/2=mms_merchant.id门店口径',
  `member_id` bigint NOT NULL COMMENT '真实报名消费者 ums_member.id',
  `actor_member_id` bigint NOT NULL COMMENT '实际操作员工/店主',
  `request_id` varchar(64) NOT NULL COMMENT '客户端单次提交幂等键',
  `corrects_note_id` bigint DEFAULT NULL COMMENT '追加式纠错所指向的原备注',
  `content` varchar(500) NOT NULL,
  `hidden` tinyint NOT NULL DEFAULT 0 COMMENT '0可见/1隐藏；原文保持不可变',
  `deleted_by` bigint DEFAULT NULL COMMENT '隐藏操作人',
  `deleted_at` datetime DEFAULT NULL COMMENT '隐藏时间',
  `hidden_request_id` varchar(64) DEFAULT NULL COMMENT '隐藏操作幂等键',
  `version` int NOT NULL DEFAULT 0 COMMENT '隐藏CAS版本',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_merchant_customer_note_request` (`merchant_id`, `merchant_scope_version`, `request_id`),
  UNIQUE KEY `uk_merchant_customer_note_hide_request` (`merchant_id`, `merchant_scope_version`, `hidden_request_id`),
  KEY `idx_merchant_customer_note_timeline` (`merchant_id`, `merchant_scope_version`, `member_id`, `hidden`, `create_time`, `id`),
  KEY `idx_merchant_customer_note_correction` (`merchant_id`, `merchant_scope_version`, `member_id`, `corrects_note_id`),
  CONSTRAINT `chk_merchant_customer_note_hidden` CHECK (`hidden` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家 CRM 跟进备注';

-- 兼容同一候选迁移的早期表形：旧 assignment/note 的 merchant_id 仍是 owner memberId。
-- 新增 namespace 后，无法唯一映射的历史行保持 version=0，应用只读写 version=2。
SET @merchant_customer_tag_assignment_scope_definition := (
  SELECT COALESCE(MAX(CONCAT(LOWER(data_type), ':', is_nullable, ':',
                             COALESCE(column_default, '<NULL>'))), '')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'merchant_customer_tag_assignment'
    AND column_name = 'merchant_scope_version'
);
SET @merchant_customer_note_scope_definition := (
  SELECT COALESCE(MAX(CONCAT(LOWER(data_type), ':', is_nullable, ':',
                             COALESCE(column_default, '<NULL>'))), '')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'merchant_customer_note'
    AND column_name = 'merchant_scope_version'
);
-- runner: assert=scalar-true
SELECT @merchant_customer_tag_assignment_scope_definition IN ('', 'tinyint:NO:0')
   AND @merchant_customer_note_scope_definition IN ('', 'tinyint:NO:0') AS assertion_ok;

SET @merchant_customer_tag_assignment_scope_column_sql := IF(
  @merchant_customer_tag_assignment_scope_definition = '',
  'ALTER TABLE `merchant_customer_tag_assignment` ADD COLUMN `merchant_scope_version` tinyint NOT NULL DEFAULT 0 COMMENT ''0=隔离的历史owner口径/2=mms_merchant.id门店口径'' AFTER `merchant_id`',
  'SELECT 1'
);
PREPARE merchant_customer_tag_assignment_scope_column_stmt
  FROM @merchant_customer_tag_assignment_scope_column_sql;
EXECUTE merchant_customer_tag_assignment_scope_column_stmt;
DEALLOCATE PREPARE merchant_customer_tag_assignment_scope_column_stmt;

SET @merchant_customer_note_scope_column_sql := IF(
  @merchant_customer_note_scope_definition = '',
  'ALTER TABLE `merchant_customer_note` ADD COLUMN `merchant_scope_version` tinyint NOT NULL DEFAULT 0 COMMENT ''0=隔离的历史owner口径/2=mms_merchant.id门店口径'' AFTER `merchant_id`',
  'SELECT 1'
);
PREPARE merchant_customer_note_scope_column_stmt FROM @merchant_customer_note_scope_column_sql;
EXECUTE merchant_customer_note_scope_column_stmt;
DEALLOCATE PREPARE merchant_customer_note_scope_column_stmt;

SET @merchant_customer_tag_assignment_request_unique := (
  SELECT IFNULL(CONCAT(MIN(non_unique), ':',
                       GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')), '')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'merchant_customer_tag_assignment'
    AND index_name = 'uk_merchant_customer_tag_request'
);
SET @merchant_customer_note_request_unique := (
  SELECT IFNULL(CONCAT(MIN(non_unique), ':',
                       GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')), '')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'merchant_customer_note'
    AND index_name = 'uk_merchant_customer_note_request'
);
SET @merchant_customer_note_hide_unique := (
  SELECT IFNULL(CONCAT(MIN(non_unique), ':',
                       GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')), '')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'merchant_customer_note'
    AND index_name = 'uk_merchant_customer_note_hide_request'
);
-- runner: assert=scalar-true
SELECT @merchant_customer_tag_assignment_request_unique IN (
         '0:merchant_id,request_id', '0:merchant_id,merchant_scope_version,request_id')
   AND @merchant_customer_note_request_unique IN (
         '0:merchant_id,request_id', '0:merchant_id,merchant_scope_version,request_id')
   AND @merchant_customer_note_hide_unique IN (
         '0:merchant_id,hidden_request_id',
         '0:merchant_id,merchant_scope_version,hidden_request_id') AS assertion_ok;

SET @merchant_customer_tag_assignment_request_unique_sql := IF(
  @merchant_customer_tag_assignment_request_unique =
    '0:merchant_id,merchant_scope_version,request_id',
  'SELECT 1',
  'ALTER TABLE `merchant_customer_tag_assignment`
     DROP INDEX `uk_merchant_customer_tag_request`,
     ADD UNIQUE KEY `uk_merchant_customer_tag_request`
       (`merchant_id`, `merchant_scope_version`, `request_id`)'
);
PREPARE merchant_customer_tag_assignment_request_unique_stmt
  FROM @merchant_customer_tag_assignment_request_unique_sql;
EXECUTE merchant_customer_tag_assignment_request_unique_stmt;
DEALLOCATE PREPARE merchant_customer_tag_assignment_request_unique_stmt;

SET @merchant_customer_note_request_unique_sql := IF(
  @merchant_customer_note_request_unique = '0:merchant_id,merchant_scope_version,request_id',
  'SELECT 1',
  'ALTER TABLE `merchant_customer_note`
     DROP INDEX `uk_merchant_customer_note_request`,
     ADD UNIQUE KEY `uk_merchant_customer_note_request`
       (`merchant_id`, `merchant_scope_version`, `request_id`)'
);
PREPARE merchant_customer_note_request_unique_stmt FROM @merchant_customer_note_request_unique_sql;
EXECUTE merchant_customer_note_request_unique_stmt;
DEALLOCATE PREPARE merchant_customer_note_request_unique_stmt;

SET @merchant_customer_note_hide_unique_sql := IF(
  @merchant_customer_note_hide_unique =
    '0:merchant_id,merchant_scope_version,hidden_request_id',
  'SELECT 1',
  'ALTER TABLE `merchant_customer_note`
     DROP INDEX `uk_merchant_customer_note_hide_request`,
     ADD UNIQUE KEY `uk_merchant_customer_note_hide_request`
       (`merchant_id`, `merchant_scope_version`, `hidden_request_id`)'
);
PREPARE merchant_customer_note_hide_unique_stmt FROM @merchant_customer_note_hide_unique_sql;
EXECUTE merchant_customer_note_hide_unique_stmt;
DEALLOCATE PREPARE merchant_customer_note_hide_unique_stmt;

-- 可唯一映射的 v0 行若会与既有 v2 幂等资源碰撞，先阻断，禁止覆盖或删除历史。
-- runner: assert=scalar-true
SELECT NOT EXISTS (
         SELECT 1
         FROM merchant_customer_tag_assignment legacy_assignment
         JOIN (
           SELECT member_id AS owner_member_id, MIN(id) AS merchant_id
           FROM mms_merchant WHERE status = 1 AND account_status = 1 AND del_flag = 0
           GROUP BY member_id HAVING COUNT(*) = 1
         ) exact_merchant ON exact_merchant.owner_member_id = legacy_assignment.merchant_id
         JOIN merchant_customer_tag exact_tag
           ON exact_tag.id = legacy_assignment.tag_id
          AND exact_tag.merchant_id = exact_merchant.merchant_id
          AND exact_tag.merchant_scope_version = 2
         JOIN merchant_customer_tag_assignment exact_assignment
           ON exact_assignment.merchant_id = exact_merchant.merchant_id
          AND exact_assignment.merchant_scope_version = 2
          AND exact_assignment.request_id = legacy_assignment.request_id
          AND exact_assignment.id <> legacy_assignment.id
         WHERE legacy_assignment.merchant_scope_version = 0
       )
   AND NOT EXISTS (
         SELECT 1
         FROM merchant_customer_note legacy_note
         JOIN (
           SELECT member_id AS owner_member_id, MIN(id) AS merchant_id
           FROM mms_merchant WHERE status = 1 AND account_status = 1 AND del_flag = 0
           GROUP BY member_id HAVING COUNT(*) = 1
         ) exact_merchant ON exact_merchant.owner_member_id = legacy_note.merchant_id
         JOIN merchant_customer_note exact_note
           ON exact_note.merchant_id = exact_merchant.merchant_id
          AND exact_note.merchant_scope_version = 2
          AND exact_note.request_id = legacy_note.request_id
          AND exact_note.id <> legacy_note.id
         WHERE legacy_note.merchant_scope_version = 0
       )
   AND NOT EXISTS (
         SELECT 1
         FROM merchant_customer_note legacy_note
         JOIN (
           SELECT member_id AS owner_member_id, MIN(id) AS merchant_id
           FROM mms_merchant WHERE status = 1 AND account_status = 1 AND del_flag = 0
           GROUP BY member_id HAVING COUNT(*) = 1
         ) exact_merchant ON exact_merchant.owner_member_id = legacy_note.merchant_id
         JOIN merchant_customer_note exact_note
           ON exact_note.merchant_id = exact_merchant.merchant_id
          AND exact_note.merchant_scope_version = 2
          AND exact_note.hidden_request_id = legacy_note.hidden_request_id
          AND exact_note.id <> legacy_note.id
         WHERE legacy_note.merchant_scope_version = 0
           AND legacy_note.hidden_request_id IS NOT NULL
       ) AS assertion_ok;

UPDATE `merchant_customer_tag_assignment` assignment
JOIN (
  SELECT `member_id` AS owner_member_id, MIN(`id`) AS merchant_id
  FROM `mms_merchant`
  WHERE `status` = 1 AND `account_status` = 1 AND `del_flag` = 0
  GROUP BY `member_id`
  HAVING COUNT(*) = 1
) exact_merchant ON exact_merchant.owner_member_id = assignment.merchant_id
JOIN `merchant_customer_tag` exact_tag
  ON exact_tag.id = assignment.tag_id
 AND exact_tag.merchant_id = exact_merchant.merchant_id
 AND exact_tag.merchant_scope_version = 2
SET assignment.merchant_id = exact_merchant.merchant_id,
    assignment.merchant_scope_version = 2
WHERE assignment.merchant_scope_version = 0
  AND assignment.merchant_id = exact_merchant.owner_member_id;

UPDATE `merchant_customer_note` note
JOIN (
  SELECT `member_id` AS owner_member_id, MIN(`id`) AS merchant_id
  FROM `mms_merchant`
  WHERE `status` = 1 AND `account_status` = 1 AND `del_flag` = 0
  GROUP BY `member_id`
  HAVING COUNT(*) = 1
) exact_merchant ON exact_merchant.owner_member_id = note.merchant_id
SET note.merchant_id = exact_merchant.merchant_id,
    note.merchant_scope_version = 2
WHERE note.merchant_scope_version = 0
  AND note.merchant_id = exact_merchant.owner_member_id;

-- runner: assert=scalar-true
SELECT
  (SELECT COUNT(*) FROM information_schema.tables
   WHERE table_schema = DATABASE() AND engine = 'InnoDB'
     AND table_name IN ('merchant_customer_tag_assignment','merchant_customer_note')) = 2
  AND (SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND
        ((table_name = 'merchant_customer_tag_assignment' AND column_name IN
          ('merchant_id','merchant_scope_version','member_id','tag_id','action','actor_member_id','request_id'))
         OR (table_name = 'merchant_customer_note' AND column_name IN
          ('merchant_id','merchant_scope_version','member_id','actor_member_id','request_id','content','hidden','version')))) = 15
  AND (SELECT COUNT(*) FROM (
        SELECT table_name, index_name, MIN(non_unique) AS non_unique,
               GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') AS cols
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name IN ('merchant_customer_profile','merchant_customer_tag',
                             'merchant_customer_tag_assignment','merchant_customer_note')
        GROUP BY table_name, index_name
      ) exact_unique
      WHERE non_unique = 0 AND
        ((table_name = 'merchant_customer_tag_assignment' AND index_name = 'uk_merchant_customer_tag_request'
          AND cols = 'merchant_id,merchant_scope_version,request_id')
         OR (table_name = 'merchant_customer_note' AND index_name = 'uk_merchant_customer_note_request'
          AND cols = 'merchant_id,merchant_scope_version,request_id')
         OR (table_name = 'merchant_customer_note' AND index_name = 'uk_merchant_customer_note_hide_request'
          AND cols = 'merchant_id,merchant_scope_version,hidden_request_id')
         OR (table_name = 'merchant_customer_profile' AND index_name = 'uk_merchant_scope_member'
          AND cols = 'merchant_id,merchant_scope_version,member_id')
         OR (table_name = 'merchant_customer_tag' AND index_name = 'uk_merchant_scope_tag_code'
          AND cols = 'merchant_id,merchant_scope_version,tag_code'))) = 5
  AND (SELECT COUNT(*) FROM information_schema.table_constraints
       WHERE table_schema = DATABASE() AND constraint_type = 'CHECK'
         AND constraint_name IN ('chk_merchant_customer_tag_action','chk_merchant_customer_note_hidden')) = 2
  AND (SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE()
         AND table_name IN ('merchant_customer_profile','merchant_customer_tag',
                            'merchant_customer_tag_assignment','merchant_customer_note')
         AND column_name = 'merchant_scope_version') = 4
  AND NOT EXISTS (
        SELECT 1 FROM merchant_customer_profile profile
        LEFT JOIN mms_merchant merchant ON merchant.id = profile.merchant_id
        WHERE profile.merchant_scope_version = 2 AND merchant.id IS NULL
      )
  AND NOT EXISTS (
        SELECT 1 FROM merchant_customer_tag tag
        LEFT JOIN mms_merchant merchant ON merchant.id = tag.merchant_id
        WHERE tag.merchant_scope_version = 2 AND merchant.id IS NULL
      )
  AND NOT EXISTS (
        SELECT 1 FROM merchant_customer_tag_assignment assignment
        LEFT JOIN mms_merchant merchant ON merchant.id = assignment.merchant_id
        WHERE assignment.merchant_scope_version = 2 AND merchant.id IS NULL
      )
  AND NOT EXISTS (
        SELECT 1 FROM merchant_customer_tag_assignment assignment
        LEFT JOIN merchant_customer_tag tag
          ON tag.id = assignment.tag_id
         AND tag.merchant_id = assignment.merchant_id
         AND tag.merchant_scope_version = 2
        WHERE assignment.merchant_scope_version = 2 AND tag.id IS NULL
      )
  AND NOT EXISTS (
        SELECT 1 FROM merchant_customer_note note
        LEFT JOIN mms_merchant merchant ON merchant.id = note.merchant_id
        WHERE note.merchant_scope_version = 2 AND merchant.id IS NULL
      )
  AS assertion_ok;

SET SESSION group_concat_max_len = 65535;
-- runner: assert=scalar-true
SELECT COUNT(*) = 2
  AND SUM(CASE
    WHEN table_name = 'merchant_customer_tag_assignment' AND column_count = 9
      AND column_hash = 'bb8d1b130e402a43ef35e8b3486d9d1a9b904d8776a4ac5e3201f59355d47c22' THEN 1
    WHEN table_name = 'merchant_customer_note' AND column_count = 14
      AND column_hash = '81548a731b1acd71d5618bd75a83fb887219b9c5dbe7fb2bb01d648dcbb06785' THEN 1
    ELSE 0 END) = 2 AS assertion_ok
FROM (
  SELECT table_name, COUNT(*) AS column_count,
         SHA2(GROUP_CONCAT(CONCAT_WS('~', column_name, LOWER(column_type), is_nullable,
           COALESCE(LOWER(CAST(column_default AS CHAR)), '<null>'), LOWER(extra),
           COALESCE(character_set_name, '<null>'),
           CASE WHEN character_set_name IS NULL OR character_set_name = 'utf8mb4'
             THEN COALESCE(character_set_name, '<null>') ELSE COALESCE(collation_name, '<null>') END,
           COALESCE(REGEXP_REPLACE(LOWER(generation_expression),
             '[[:space:]`]|_utf8mb[34]|_utf8|_latin1|_gbk', ''), '<null>'))
           ORDER BY ordinal_position SEPARATOR '|'), 256) AS column_hash
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name IN ('merchant_customer_tag_assignment','merchant_customer_note')
  GROUP BY table_name
) exact_columns;

-- runner: assert=scalar-true
SELECT COUNT(*) = 2
  AND SUM(CASE
    WHEN table_name = 'merchant_customer_tag_assignment' AND check_count = 1
      AND check_hash = '27a03fe9613e48edc4291cc8929b9ad03bf11b3bf2065bde5592e3c9a591dbf8' THEN 1
    WHEN table_name = 'merchant_customer_note' AND check_count = 1
      AND check_hash = 'a5d5f8902b8398201138736a606637be75702bfd3b80347f6959ae0fe4f1f561' THEN 1
    ELSE 0 END) = 2 AS assertion_ok
FROM (
  SELECT tc.table_name, COUNT(*) AS check_count,
         SHA2(GROUP_CONCAT(CONCAT_WS('~', cc.constraint_name, tc.enforced,
           SHA2(REGEXP_REPLACE(LOWER(cc.check_clause),
             '[[:space:]`]|_utf8mb[34]|_utf8|_latin1|_gbk', ''), 256))
           ORDER BY cc.constraint_name SEPARATOR '|'), 256) AS check_hash
  FROM information_schema.table_constraints tc
  JOIN information_schema.check_constraints cc
    ON cc.constraint_schema = tc.constraint_schema AND cc.constraint_name = tc.constraint_name
  WHERE tc.constraint_schema = DATABASE() AND tc.constraint_type = 'CHECK'
    AND tc.table_name IN ('merchant_customer_tag_assignment','merchant_customer_note')
  GROUP BY tc.table_name
) exact_checks;
