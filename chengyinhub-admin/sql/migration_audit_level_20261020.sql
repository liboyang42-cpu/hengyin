-- 审核分级 P2：先发后审（owner 2026-10-20 批准 DDL）。只新增列，不删、不改、不回填既有数据。
-- community_post.audit_level          0 未审 / 1 机审通过 / 2 人审通过
-- community_post.audit_before_publish 1 = 该帖经过先审后发（HOLD 后人工通过）
-- community_post.review_flag          1 = 放行但进入抽检池
-- cms_topic.review_flag               1 = 放行但进入抽检池（写入方在 P1 接入闸门后落地）
-- 前置：community_post 由 migration_community_post_platform_20260909.sql 建表；cms_topic 在 sql/base_schema_20260818.sql。
-- 落库顺序：先手工跑本文件并通过末尾断言，再部署新 jar（新 jar 的 Mapper 会读写这些列）。
-- 幂等：每条 ALTER 前查 information_schema；同名但形状不符的列 fail-closed，不替换、不删除。
DROP TEMPORARY TABLE IF EXISTS audit_level_20261020_guard;
CREATE TEMPORARY TABLE audit_level_20261020_guard(id TINYINT PRIMARY KEY);
INSERT INTO audit_level_20261020_guard VALUES(1);

-- 两张目标表必须存在且为 InnoDB；否则下一条 INSERT 撞主键，整个文件停在这里。
INSERT INTO audit_level_20261020_guard
SELECT 1 WHERE (SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema=DATABASE() AND table_name IN ('community_post','cms_topic') AND engine='InnoDB') <> 2;

-- 已存在的同名列必须正是本迁移要的形状（TINYINT NOT NULL DEFAULT 0）；不符就停，不当成已完成。
INSERT INTO audit_level_20261020_guard
SELECT 1 WHERE EXISTS(SELECT 1 FROM information_schema.columns
  WHERE table_schema=DATABASE()
    AND ((table_name='community_post' AND column_name IN ('audit_level','audit_before_publish','review_flag'))
      OR (table_name='cms_topic' AND column_name='review_flag'))
    AND NOT (data_type='tinyint' AND is_nullable='NO' AND column_default='0'));

SET @audit_level_ddl := IF(
  EXISTS(SELECT 1 FROM information_schema.columns
         WHERE table_schema=DATABASE() AND table_name='community_post' AND column_name='audit_level'),
  'SELECT 1',
  'ALTER TABLE community_post ADD COLUMN audit_level TINYINT NOT NULL DEFAULT 0 COMMENT ''审核等级：0未审 1机审通过 2人审通过'', ALGORITHM=INSTANT'
);
PREPARE audit_level_stmt FROM @audit_level_ddl;
EXECUTE audit_level_stmt;
DEALLOCATE PREPARE audit_level_stmt;

SET @audit_before_publish_ddl := IF(
  EXISTS(SELECT 1 FROM information_schema.columns
         WHERE table_schema=DATABASE() AND table_name='community_post' AND column_name='audit_before_publish'),
  'SELECT 1',
  'ALTER TABLE community_post ADD COLUMN audit_before_publish TINYINT NOT NULL DEFAULT 0 COMMENT ''1=先审后发（HOLD后人工通过）'', ALGORITHM=INSTANT'
);
PREPARE audit_before_publish_stmt FROM @audit_before_publish_ddl;
EXECUTE audit_before_publish_stmt;
DEALLOCATE PREPARE audit_before_publish_stmt;

SET @post_review_flag_ddl := IF(
  EXISTS(SELECT 1 FROM information_schema.columns
         WHERE table_schema=DATABASE() AND table_name='community_post' AND column_name='review_flag'),
  'SELECT 1',
  'ALTER TABLE community_post ADD COLUMN review_flag TINYINT NOT NULL DEFAULT 0 COMMENT ''1=放行但进入抽检池'', ALGORITHM=INSTANT'
);
PREPARE post_review_flag_stmt FROM @post_review_flag_ddl;
EXECUTE post_review_flag_stmt;
DEALLOCATE PREPARE post_review_flag_stmt;

SET @topic_review_flag_ddl := IF(
  EXISTS(SELECT 1 FROM information_schema.columns
         WHERE table_schema=DATABASE() AND table_name='cms_topic' AND column_name='review_flag'),
  'SELECT 1',
  'ALTER TABLE cms_topic ADD COLUMN review_flag TINYINT NOT NULL DEFAULT 0 COMMENT ''1=放行但进入抽检池'', ALGORITHM=INSTANT'
);
PREPARE topic_review_flag_stmt FROM @topic_review_flag_ddl;
EXECUTE topic_review_flag_stmt;
DEALLOCATE PREPARE topic_review_flag_stmt;

DROP TEMPORARY TABLE audit_level_20261020_guard;

-- runner: assert=scalar-true
SELECT COUNT(*)=4 AS assertion_ok
FROM information_schema.columns
WHERE table_schema=DATABASE()
  AND ((table_name='community_post' AND column_name IN ('audit_level','audit_before_publish','review_flag'))
    OR (table_name='cms_topic' AND column_name='review_flag'))
  AND data_type='tinyint' AND is_nullable='NO' AND column_default='0';
