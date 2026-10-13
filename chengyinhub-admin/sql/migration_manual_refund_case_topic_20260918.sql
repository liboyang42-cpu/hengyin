-- FX-05(2026-09-18 用户拍板「加列」):人工退款工单也能挂主题级票(owner_type=1)。
--
-- 背景:activity_cancel_manual_refund_case 只有 activity_id NOT NULL,主题票整单部分核销时没有工单可挂,
--   只能整单拒退(玩家只看到报错)。本迁移加可空 topic_id,并把 activity_id 放宽为可空:
--   每张工单恰好挂一个对象 —— 场次工单 activity_id>0 且 topic_id 为空;主题工单反之(CHECK 强制)。
--   ⚠️ CHECK 里必须写 IS NOT NULL:只写 activity_id > 0 时两列皆空会算成 UNKNOWN,CHECK 视为通过。
--   主题工单用 (topic_id, parent_order_no) 唯一键防重复冻结,与场次侧 uk_activity_cancel_manual_order 对称。
-- 存量影响:存量行全是场次工单(activity_id>0,topic_id 补 NULL),新 CHECK 对它们恒成立;不回填任何数据。
-- 可重复执行:每一步都以 information_schema 判定是否已做过(MySQL 8 无 ADD COLUMN IF NOT EXISTS)。
-- ⚠️ 本文件替代 migration_event_cancel_manual_refund_case_20260826 的结构断言(列数/索引/identity CHECK 都变了),
--    已登记 ci/migration-assertion-supersessions.json;下方断言接管它守的结构事实。
-- ⚠️ 只在库上跑:合入 master 前须走 chengyin-db-migrate 只读预检 + 用户确认后落库;本文件不自动执行。

SET @exist := (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'activity_cancel_manual_refund_case'
                 AND column_name = 'topic_id');
SET @sql := IF(@exist = 0,
  'ALTER TABLE activity_cancel_manual_refund_case ADD COLUMN topic_id BIGINT NULL AFTER activity_id',
  'SELECT ''activity_cancel_manual_refund_case.topic_id 已存在,跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

ALTER TABLE activity_cancel_manual_refund_case MODIFY COLUMN activity_id BIGINT NULL;

-- identity CHECK 换成「恰好挂一个对象」。旧版不含 topic_id,据此判定是否已替换过。
SET @old := (SELECT COUNT(*) FROM information_schema.check_constraints
             WHERE constraint_schema = DATABASE()
               AND constraint_name = 'chk_activity_cancel_manual_identity'
               AND check_clause NOT LIKE '%topic_id%');
SET @sql := IF(@old = 1,
  'ALTER TABLE activity_cancel_manual_refund_case DROP CHECK chk_activity_cancel_manual_identity',
  'SELECT ''identity CHECK 已是新版或不存在,跳过删除''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @missing := (SELECT COUNT(*) = 0 FROM information_schema.check_constraints
                 WHERE constraint_schema = DATABASE()
                   AND constraint_name = 'chk_activity_cancel_manual_identity');
SET @sql := IF(@missing = 1,
  'ALTER TABLE activity_cancel_manual_refund_case ADD CONSTRAINT chk_activity_cancel_manual_identity CHECK
    (((activity_id IS NOT NULL AND activity_id > 0 AND topic_id IS NULL)
        OR (activity_id IS NULL AND topic_id IS NOT NULL AND topic_id > 0))
      AND owner_member_id > 0 AND actor_member_id > 0
      AND payer_registration_id > 0 AND payer_member_id > 0
      AND (club_id IS NULL OR club_id > 0))',
  'SELECT ''identity CHECK 已是新版,跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'activity_cancel_manual_refund_case'
                 AND index_name = 'uk_topic_cancel_manual_order');
SET @sql := IF(@exist = 0,
  'ALTER TABLE activity_cancel_manual_refund_case ADD UNIQUE KEY uk_topic_cancel_manual_order (topic_id, parent_order_no)',
  'SELECT ''uk_topic_cancel_manual_order 已存在,跳过''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 列:33 列(原 32 + topic_id);activity_id/topic_id 都是可空 bigint 且无默认值。
-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'activity_cancel_manual_refund_case') = 33
  AND (SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND table_name = 'activity_cancel_manual_refund_case'
         AND column_name IN ('activity_id', 'topic_id')
         AND column_type = 'bigint' AND is_nullable = 'YES' AND column_default IS NULL) = 2
  AS assertion_ok;

-- 索引:原 7 个 + 主题唯一键,全部可见、无 prefix。
-- runner: assert=scalar-true
SELECT COUNT(*) = 8
  AND SUM(index_type = 'BTREE' AND is_visible = 'YES' AND prefix_parts = 0) = 8
  AND SUM(CASE
    WHEN index_name = 'PRIMARY' AND non_unique = 0 AND cols = 'id' THEN 1
    WHEN index_name = 'uk_activity_cancel_manual_order' AND non_unique = 0
      AND cols = 'activity_id,parent_order_no' THEN 1
    WHEN index_name = 'uk_topic_cancel_manual_order' AND non_unique = 0
      AND cols = 'topic_id,parent_order_no' THEN 1
    WHEN index_name = 'uk_activity_cancel_manual_assessment_request' AND non_unique = 0
      AND cols = 'assessment_request_id' THEN 1
    WHEN index_name = 'uk_activity_cancel_manual_handle_request' AND non_unique = 0
      AND cols = 'handle_request_id' THEN 1
    WHEN index_name = 'uk_activity_cancel_manual_verify_request' AND non_unique = 0
      AND cols = 'verify_request_id' THEN 1
    WHEN index_name = 'idx_activity_cancel_manual_status' AND non_unique = 1
      AND cols = 'status,club_id,id' THEN 1
    WHEN index_name = 'idx_activity_cancel_manual_club' AND non_unique = 1
      AND cols = 'club_id,status,id' THEN 1
    ELSE 0 END) = 8 AS assertion_ok
FROM (
  SELECT index_name, MIN(non_unique) non_unique, MIN(index_type) index_type,
         MIN(is_visible) is_visible, SUM(sub_part IS NOT NULL) prefix_parts,
         GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') cols
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'activity_cancel_manual_refund_case'
  GROUP BY index_name
) exact_indexes;

-- CHECK:8 条全部 ENFORCED;identity 必须是「恰好一个对象」的新版(含 topic_id 且不接受两者皆空)。
-- runner: assert=scalar-true
SELECT COUNT(*) = 8
  AND SUM(cc.constraint_name = 'chk_activity_cancel_manual_identity'
    AND LOWER(cc.check_clause) LIKE '%activity_id%is not null%topic_id%is null%'
    AND LOWER(cc.check_clause) LIKE '%activity_id%is null%topic_id%is not null%') = 1
  AS assertion_ok
FROM information_schema.check_constraints cc
JOIN information_schema.table_constraints tc
  ON tc.constraint_schema = cc.constraint_schema
 AND tc.constraint_name = cc.constraint_name
WHERE cc.constraint_schema = DATABASE()
  AND tc.table_name = 'activity_cancel_manual_refund_case'
  AND tc.constraint_type = 'CHECK' AND tc.enforced = 'YES';

-- 回滚(确认没有主题工单后):
-- ALTER TABLE activity_cancel_manual_refund_case DROP INDEX uk_topic_cancel_manual_order;
-- ALTER TABLE activity_cancel_manual_refund_case DROP CHECK chk_activity_cancel_manual_identity;
-- ALTER TABLE activity_cancel_manual_refund_case ADD CONSTRAINT chk_activity_cancel_manual_identity CHECK
--   (activity_id > 0 AND owner_member_id > 0 AND actor_member_id > 0
--     AND payer_registration_id > 0 AND payer_member_id > 0 AND (club_id IS NULL OR club_id > 0));
-- ALTER TABLE activity_cancel_manual_refund_case MODIFY COLUMN activity_id BIGINT NOT NULL;
-- ALTER TABLE activity_cancel_manual_refund_case DROP COLUMN topic_id;
