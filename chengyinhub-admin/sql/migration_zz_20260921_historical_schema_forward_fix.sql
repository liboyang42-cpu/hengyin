-- 历史 migration 恢复原始字节后，用新 migration 承接曾被追写的 schema/data 意图。
-- 命名以 zz 开头，使它在已有 community/audit_time 迁移后执行，只做幂等收敛。

-- 前置 readback：不允许在基础表/视图不完整时部分执行。
-- runner: assert=scalar-true
SELECT
  (SELECT COUNT(*) FROM information_schema.tables
   WHERE table_schema=DATABASE() AND table_type='BASE TABLE'
     AND table_name IN ('biz_event_log','cms_registration_merchant','cms_topic')) = 3
  AND
  (SELECT COUNT(*) FROM information_schema.views
   WHERE table_schema=DATABASE() AND table_name='view_registration_merchant') = 1
  AND
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema=DATABASE() AND table_name='biz_event_log'
     AND column_name IN ('event_name','member_id','idempotency_key','occurred_at')) = 4
  AND
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema=DATABASE() AND table_name='cms_registration_merchant'
     AND column_name IN ('audit_status','status','del_flag','topic_id')) = 4
  AS assertion_ok;

-- 重复日志是业务数据，本 schema migration 不猜测保留哪条。
-- 如存在重复则在任何 UPDATE/DDL 前 fail-closed，需单独导出、备份和授权清理。
-- runner: assert=scalar-true
SELECT COUNT(*)=0 AS assertion_ok
FROM (
  SELECT 1
  FROM biz_event_log
  WHERE idempotency_key IS NOT NULL
  GROUP BY event_name,
           CASE WHEN member_id IS NULL
                THEN 'ANONYMOUS' ELSE CONCAT('MEMBER:', member_id) END,
           idempotency_key
  HAVING COUNT(*) > 1
) duplicate_event_groups;

SET @add_biz_event_actor_scope := IF(
  EXISTS(SELECT 1 FROM information_schema.columns
         WHERE table_schema=DATABASE() AND table_name='biz_event_log'
           AND column_name='actor_scope'),
  'SELECT 1',
  'ALTER TABLE biz_event_log ADD COLUMN actor_scope VARCHAR(96) NOT NULL DEFAULT ''ANONYMOUS'' COMMENT ''服务端派生幂等作用域 MEMBER:id/ANONYMOUS'' AFTER idempotency_key'
);
PREPARE historical_actor_scope_add_stmt FROM @add_biz_event_actor_scope;
EXECUTE historical_actor_scope_add_stmt;
DEALLOCATE PREPARE historical_actor_scope_add_stmt;

UPDATE biz_event_log
SET actor_scope=CASE WHEN member_id IS NULL THEN 'ANONYMOUS' ELSE CONCAT('MEMBER:', member_id) END
WHERE actor_scope IS NULL
   OR actor_scope=''
   OR actor_scope<>CASE WHEN member_id IS NULL
                        THEN 'ANONYMOUS' ELSE CONCAT('MEMBER:', member_id) END;

SET @normalize_biz_event_actor_scope := IF(
  EXISTS(SELECT 1 FROM information_schema.columns
         WHERE table_schema=DATABASE() AND table_name='biz_event_log'
           AND column_name='actor_scope'
           AND column_type='varchar(96)' AND is_nullable='NO'
           AND column_default='ANONYMOUS'
           AND column_comment='服务端派生幂等作用域 MEMBER:id/ANONYMOUS'),
  'SELECT 1',
  'ALTER TABLE biz_event_log MODIFY COLUMN actor_scope VARCHAR(96) NOT NULL DEFAULT ''ANONYMOUS'' COMMENT ''服务端派生幂等作用域 MEMBER:id/ANONYMOUS'''
);
PREPARE historical_actor_scope_normalize_stmt FROM @normalize_biz_event_actor_scope;
EXECUTE historical_actor_scope_normalize_stmt;
DEALLOCATE PREPARE historical_actor_scope_normalize_stmt;

SET @drop_wrong_biz_event_idem := IF(
  EXISTS(SELECT 1 FROM information_schema.statistics
         WHERE table_schema=DATABASE() AND table_name='biz_event_log'
           AND index_name='uk_biz_event_idem')
  AND NOT EXISTS(
    SELECT index_name, non_unique FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='biz_event_log'
      AND index_name='uk_biz_event_idem'
    GROUP BY index_name, non_unique
    HAVING non_unique=0
       AND GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
           = 'event_name,actor_scope,idempotency_key'
  ),
  'ALTER TABLE biz_event_log DROP INDEX uk_biz_event_idem',
  'SELECT 1'
);
PREPARE historical_biz_event_idem_drop_stmt FROM @drop_wrong_biz_event_idem;
EXECUTE historical_biz_event_idem_drop_stmt;
DEALLOCATE PREPARE historical_biz_event_idem_drop_stmt;

SET @add_biz_event_idem := IF(
  EXISTS(
    SELECT index_name, non_unique FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='biz_event_log'
      AND index_name='uk_biz_event_idem'
    GROUP BY index_name, non_unique
    HAVING non_unique=0
       AND GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
           = 'event_name,actor_scope,idempotency_key'
  ),
  'SELECT 1',
  'ALTER TABLE biz_event_log ADD UNIQUE INDEX uk_biz_event_idem (event_name, actor_scope, idempotency_key)'
);
PREPARE historical_biz_event_idem_add_stmt FROM @add_biz_event_idem;
EXECUTE historical_biz_event_idem_add_stmt;
DEALLOCATE PREPARE historical_biz_event_idem_add_stmt;

SET @drop_wrong_biz_event_occurred_index := IF(
  EXISTS(SELECT 1 FROM information_schema.statistics
         WHERE table_schema=DATABASE() AND table_name='biz_event_log'
           AND index_name='idx_biz_event_occurred_at')
  AND NOT EXISTS(
    SELECT index_name, non_unique FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='biz_event_log'
      AND index_name='idx_biz_event_occurred_at'
    GROUP BY index_name, non_unique
    HAVING non_unique=1
       AND GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')='occurred_at'
  ),
  'ALTER TABLE biz_event_log DROP INDEX idx_biz_event_occurred_at',
  'SELECT 1'
);
PREPARE historical_biz_event_occurred_drop_stmt FROM @drop_wrong_biz_event_occurred_index;
EXECUTE historical_biz_event_occurred_drop_stmt;
DEALLOCATE PREPARE historical_biz_event_occurred_drop_stmt;

SET @add_biz_event_occurred_index := IF(
  EXISTS(
    SELECT index_name, non_unique FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='biz_event_log'
      AND index_name='idx_biz_event_occurred_at'
    GROUP BY index_name, non_unique
    HAVING non_unique=1
       AND GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')='occurred_at'
  ),
  'SELECT 1',
  'ALTER TABLE biz_event_log ADD INDEX idx_biz_event_occurred_at (occurred_at)'
);
PREPARE historical_biz_event_occurred_add_stmt FROM @add_biz_event_occurred_index;
EXECUTE historical_biz_event_occurred_add_stmt;
DEALLOCATE PREPARE historical_biz_event_occurred_add_stmt;

SET @add_registration_merchant_audit_time := IF(
  EXISTS(SELECT 1 FROM information_schema.columns
         WHERE table_schema=DATABASE() AND table_name='cms_registration_merchant'
           AND column_name='audit_time'),
  'SELECT 1',
  'ALTER TABLE cms_registration_merchant ADD COLUMN audit_time DATETIME NULL DEFAULT NULL COMMENT ''审核决定发生的时刻(通过/驳回都写;退回待定时清空)'' AFTER audit_status'
);
PREPARE historical_audit_time_add_stmt FROM @add_registration_merchant_audit_time;
EXECUTE historical_audit_time_add_stmt;
DEALLOCATE PREPARE historical_audit_time_add_stmt;

CREATE OR REPLACE
    SQL SECURITY INVOKER
VIEW `view_registration_merchant` AS
SELECT
    `crm`.`id` AS `id`,
    `crm`.`member_id` AS `member_id`,
    `crm`.`topic_id` AS `topic_id`,
    `crm`.`mode` AS `mode`,
    `crm`.`node_id` AS `node_id`,
    `crm`.`address` AS `address`,
    `crm`.`address_name` AS `address_name`,
    `crm`.`longitude` AS `longitude`,
    `crm`.`latitude` AS `latitude`,
    `crm`.`template_id` AS `template_id`,
    `crm`.`pic_url` AS `pic_url`,
    `crm`.`email` AS `email`,
    `crm`.`start_date` AS `start_date`,
    `crm`.`end_date` AS `end_date`,
    `crm`.`cooperate_date` AS `cooperate_date`,
    `crm`.`activity_desc` AS `activity_desc`,
    `crm`.`limit_num` AS `limit_num`,
    `crm`.`rule_instructions` AS `rule_instructions`,
    `crm`.`feedback_text` AS `feedback_text`,
    `crm`.`coupon_id` AS `coupon_id`,
    `crm`.`validation_method` AS `validation_method`,
    `crm`.`question_name` AS `question_name`,
    `crm`.`question_answer` AS `question_answer`,
    `crm`.`question_a` AS `question_a`,
    `crm`.`question_b` AS `question_b`,
    `crm`.`question_c` AS `question_c`,
    `crm`.`question_d` AS `question_d`,
    `crm`.`correct_answer` AS `correct_answer`,
    `crm`.`status` AS `status`,
    `crm`.`audit_time` AS `audit_time`,
    `crm`.`sharing_rate` AS `sharing_rate`,
    `crm`.`sharing_amount` AS `sharing_amount`,
    `crm`.`reason` AS `reason`,
    `crm`.`del_flag` AS `del_flag`,
    `crm`.`create_by` AS `create_by`,
    `crm`.`create_time` AS `create_time`,
    `crm`.`update_by` AS `update_by`,
    `crm`.`update_time` AS `update_time`,
    `ct`.`member_id` AS `topic_member_id`,
    `ct`.`topic_type` AS `topic_type`,
    `ct`.`topic_id` AS `related_topic_id`,
    `ct`.`name` AS `topic_name`,
    `ct`.`subtitle` AS `topic_subtitle`,
    `ct`.`description` AS `topic_description`,
    `ct`.`address_name` AS `topic_address_name`,
    `ct`.`total_time` AS `total_time`,
    `ct`.`total_mileage` AS `total_mileage`,
    `ct`.`location_count` AS `location_count`,
    `ct`.`template_count` AS `template_count`,
    `ct`.`start_date` AS `topic_start_date`,
    `ct`.`end_date` AS `topic_end_date`,
    `ct`.`img_url` AS `topic_img_url`,
    `ct`.`img_arr` AS `topic_img_arr`,
    `ct`.`category_ids` AS `category_ids`,
    `ct`.`status` AS `topic_status`,
    `ct`.`reason` AS `topic_reason`,
    `ct`.`user_status` AS `user_status`,
    `ct`.`merchant_status` AS `merchant_status`,
    `ct`.`merchant_sign_up_start_date` AS `merchant_sign_up_start_date`,
    `ct`.`merchant_sign_up_end_date` AS `merchant_sign_up_end_date`,
    `ct`.`del_flag` AS `topic_del_flag`,
    `ct`.`is_sync` AS `is_sync`,
    `ct`.`is_recommend` AS `is_recommend`,
    `ct`.`check_status` AS `topic_check_status`,
    `ct`.`is_settlement` AS `is_settlement`,
    `ct`.`create_by` AS `topic_create_by`,
    `ct`.`create_time` AS `topic_create_time`,
    `ct`.`update_by` AS `topic_update_by`,
    `ct`.`update_time` AS `topic_update_time`,
    (CASE
        WHEN (`crm`.`status` = 2) THEN '已驳回'
        WHEN (`crm`.`status` = 0) THEN '审核中'
        WHEN (`crm`.`status` = 1) THEN
            (CASE
                WHEN (`ct`.`end_date` < NOW()) THEN '已结束'
                WHEN ((`ct`.`start_date` <= NOW()) AND (`ct`.`end_date` >= NOW())) THEN '进行中'
                WHEN (`ct`.`start_date` > NOW()) THEN '未开始'
                ELSE '已审核'
            END)
        ELSE '未知状态'
    END) AS `display_status`,
    (CASE WHEN ((`crm`.`status` = 1) AND (`ct`.`start_date` <= NOW()) AND (`ct`.`end_date` >= NOW()))
        THEN 1 ELSE 0 END) AS `is_in_progress`,
    (CASE WHEN ((`crm`.`status` = 1) AND (`ct`.`end_date` < NOW()))
        THEN 1 ELSE 0 END) AS `is_ended`,
    (CASE WHEN (`crm`.`status` = 0) THEN 1 ELSE 0 END) AS `is_under_review`,
    (CASE WHEN (`crm`.`status` = 2) THEN 1 ELSE 0 END) AS `is_rejected`
FROM (`cms_registration_merchant` `crm`
    LEFT JOIN `cms_topic` `ct` ON ((`crm`.`topic_id` = `ct`.`id`)))
WHERE (`crm`.`del_flag` = 0);

-- 后置 readback：字段、数据、两个索引和视图投影必须同时收敛。
-- runner: assert=scalar-true
SELECT
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema=DATABASE() AND table_name='biz_event_log'
     AND column_name='actor_scope' AND column_type='varchar(96)'
     AND is_nullable='NO' AND column_default='ANONYMOUS'
     AND column_comment='服务端派生幂等作用域 MEMBER:id/ANONYMOUS') = 1
  AND NOT EXISTS(
    SELECT 1 FROM biz_event_log
    WHERE actor_scope<>CASE WHEN member_id IS NULL
                            THEN 'ANONYMOUS' ELSE CONCAT('MEMBER:', member_id) END
  )
  AND
  (SELECT COUNT(*) FROM (
     SELECT index_name, non_unique FROM information_schema.statistics
     WHERE table_schema=DATABASE() AND table_name='biz_event_log'
       AND index_name='uk_biz_event_idem'
     GROUP BY index_name, non_unique
     HAVING non_unique=0
        AND GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
            = 'event_name,actor_scope,idempotency_key'
   ) actor_idem_index) = 1
  AND
  (SELECT COUNT(*) FROM (
     SELECT index_name, non_unique FROM information_schema.statistics
     WHERE table_schema=DATABASE() AND table_name='biz_event_log'
       AND index_name='idx_biz_event_occurred_at'
     GROUP BY index_name, non_unique
     HAVING non_unique=1
        AND GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')='occurred_at'
   ) occurred_index) = 1
  AND
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema=DATABASE() AND table_name='cms_registration_merchant'
     AND column_name='audit_time' AND data_type='datetime' AND is_nullable='YES') = 1
  AND
  (SELECT COUNT(*) FROM information_schema.columns audit_column
   JOIN information_schema.columns status_column
     ON status_column.table_schema=audit_column.table_schema
    AND status_column.table_name=audit_column.table_name
    AND status_column.column_name='status'
   WHERE audit_column.table_schema=DATABASE()
     AND audit_column.table_name='view_registration_merchant'
     AND audit_column.column_name='audit_time'
     AND audit_column.ordinal_position=status_column.ordinal_position+1) = 1
  AND
  (SELECT COUNT(*) FROM information_schema.views
   WHERE table_schema=DATABASE() AND table_name='view_registration_merchant'
     AND security_type='INVOKER') = 1
  AS assertion_ok;
