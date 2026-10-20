-- 内容安全留置标记（AUDIT-INT-7，owner 2026-10-20 批准 DDL）。只新增列，不删、不改、不回填既有数据。
-- cms_topic.content_safety_hold     1 = 图片机审回调判违规（含已被人工驳回的主题），申诉成立/撤销都不能恢复上架
-- cms_activity.content_safety_hold  1 = 同上（活动）
-- 为什么不复用 check_status：人工驳回本身就是 check_status=2，已驳回的内容再收到图片违规回调时无从区分；
-- 原驳回原因也要保留给作者。人工审核作者重新提交的内容并通过时清 0。
-- 前置：两张表都在 sql/base_schema_20260818.sql。
-- 落库顺序：先手工跑本文件并通过末尾断言，再部署新 jar（新 jar 的 Mapper 会读写这两列）。
-- 幂等：每条 ALTER 前查 information_schema；同名但形状不符的列 fail-closed，不替换、不删除。
DROP TEMPORARY TABLE IF EXISTS content_safety_hold_20261020_guard;
CREATE TEMPORARY TABLE content_safety_hold_20261020_guard(id TINYINT PRIMARY KEY);
INSERT INTO content_safety_hold_20261020_guard VALUES(1);

-- 两张目标表必须存在且为 InnoDB；否则下一条 INSERT 撞主键，整个文件停在这里。
INSERT INTO content_safety_hold_20261020_guard
SELECT 1 WHERE (SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema=DATABASE() AND table_name IN ('cms_topic','cms_activity') AND engine='InnoDB') <> 2;

-- 已存在的同名列必须正是本迁移要的形状（TINYINT NOT NULL DEFAULT 0）；不符就停，不当成已完成。
INSERT INTO content_safety_hold_20261020_guard
SELECT 1 WHERE EXISTS(SELECT 1 FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name IN ('cms_topic','cms_activity')
    AND column_name='content_safety_hold'
    AND NOT (data_type='tinyint' AND is_nullable='NO' AND column_default='0'));

SET @topic_safety_hold_ddl := IF(
  EXISTS(SELECT 1 FROM information_schema.columns
         WHERE table_schema=DATABASE() AND table_name='cms_topic' AND column_name='content_safety_hold'),
  'SELECT 1',
  'ALTER TABLE cms_topic ADD COLUMN content_safety_hold TINYINT NOT NULL DEFAULT 0 COMMENT ''1=图片内容安全违规留置，须更换图片并重新人审'', ALGORITHM=INSTANT'
);
PREPARE topic_safety_hold_stmt FROM @topic_safety_hold_ddl;
EXECUTE topic_safety_hold_stmt;
DEALLOCATE PREPARE topic_safety_hold_stmt;

SET @activity_safety_hold_ddl := IF(
  EXISTS(SELECT 1 FROM information_schema.columns
         WHERE table_schema=DATABASE() AND table_name='cms_activity' AND column_name='content_safety_hold'),
  'SELECT 1',
  'ALTER TABLE cms_activity ADD COLUMN content_safety_hold TINYINT NOT NULL DEFAULT 0 COMMENT ''1=图片内容安全违规留置，须更换图片并重新人审'', ALGORITHM=INSTANT'
);
PREPARE activity_safety_hold_stmt FROM @activity_safety_hold_ddl;
EXECUTE activity_safety_hold_stmt;
DEALLOCATE PREPARE activity_safety_hold_stmt;

DROP TEMPORARY TABLE content_safety_hold_20261020_guard;

-- runner: assert=scalar-true
SELECT COUNT(*)=2 AS assertion_ok
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name IN ('cms_topic','cms_activity')
  AND column_name='content_safety_hold'
  AND data_type='tinyint' AND is_nullable='NO' AND column_default='0';
