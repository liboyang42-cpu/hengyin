-- 举报→处置桥接 + 评论处置（owner 2026-10-20 批准 DDL）。只新增可空列与二级索引，不删、不改、不回填既有数据。
-- community_post_enforcement.report_case_id  非空 = 由该举报单（community_report_case.id）裁定违规后落地的处置
-- community_post_enforcement.comment_id      非空 = 针对单条评论（community_post_comment.id）的处置；此时 post_id 为空，
--                                             帖文级的生效/恢复查询（按 post_id）天然不会把评论处置当成整帖下架
-- 前置：community_post_enforcement 由 migration_community_post_platform_20260909.sql 建表。
-- 落库顺序：先手工跑本文件并通过末尾断言，再部署新 jar（新 jar 的 Mapper 会写这两列）。
-- 幂等：每条 ALTER 前查 information_schema；同名但形状不符的列/索引 fail-closed，不替换、不删除。
DROP TEMPORARY TABLE IF EXISTS community_enforcement_links_guard;
CREATE TEMPORARY TABLE community_enforcement_links_guard(id TINYINT PRIMARY KEY);
INSERT INTO community_enforcement_links_guard VALUES(1);

-- 目标表必须存在且为 InnoDB；否则下一条 INSERT 撞主键，整个文件停在这里。
INSERT INTO community_enforcement_links_guard
SELECT 1 WHERE (SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema=DATABASE() AND table_name='community_post_enforcement' AND engine='InnoDB') <> 1;

-- 已存在的同名列必须正是本迁移要的形状（BIGINT NULL、无默认值）；不符就停，不当成已完成。
INSERT INTO community_enforcement_links_guard
SELECT 1 WHERE EXISTS(SELECT 1 FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='community_post_enforcement'
    AND column_name IN ('report_case_id','comment_id')
    AND NOT (column_type='bigint' AND is_nullable='YES' AND column_default IS NULL));

-- 已存在的同名索引必须正是本迁移要的列序；不符就停。
INSERT INTO community_enforcement_links_guard
SELECT 1 WHERE EXISTS(SELECT 1 FROM (
    SELECT index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) cols, MAX(non_unique) non_unique
    FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='community_post_enforcement'
      AND index_name IN ('idx_community_enforcement_report','idx_community_enforcement_comment')
    GROUP BY index_name) existing
  WHERE non_unique<>1
     OR (index_name='idx_community_enforcement_report' AND cols<>'report_case_id,id')
     OR (index_name='idx_community_enforcement_comment' AND cols<>'comment_id,status,id'));

SET @enforcement_report_case_ddl := IF(
  EXISTS(SELECT 1 FROM information_schema.columns
         WHERE table_schema=DATABASE() AND table_name='community_post_enforcement' AND column_name='report_case_id'),
  'SELECT 1',
  'ALTER TABLE community_post_enforcement ADD COLUMN report_case_id BIGINT NULL COMMENT ''来源举报单 community_report_case.id'', ALGORITHM=INSTANT'
);
PREPARE enforcement_report_case_stmt FROM @enforcement_report_case_ddl;
EXECUTE enforcement_report_case_stmt;
DEALLOCATE PREPARE enforcement_report_case_stmt;

SET @enforcement_comment_ddl := IF(
  EXISTS(SELECT 1 FROM information_schema.columns
         WHERE table_schema=DATABASE() AND table_name='community_post_enforcement' AND column_name='comment_id'),
  'SELECT 1',
  'ALTER TABLE community_post_enforcement ADD COLUMN comment_id BIGINT NULL COMMENT ''评论处置目标 community_post_comment.id'', ALGORITHM=INSTANT'
);
PREPARE enforcement_comment_stmt FROM @enforcement_comment_ddl;
EXECUTE enforcement_comment_stmt;
DEALLOCATE PREPARE enforcement_comment_stmt;

-- 二级索引在线加，不锁写（LOCK=NONE 做不到时报错停下，不会退化成锁表）。
SET @enforcement_report_idx_ddl := IF(
  EXISTS(SELECT 1 FROM information_schema.statistics
         WHERE table_schema=DATABASE() AND table_name='community_post_enforcement'
           AND index_name='idx_community_enforcement_report'),
  'SELECT 1',
  'ALTER TABLE community_post_enforcement ADD INDEX idx_community_enforcement_report (report_case_id, id), ALGORITHM=INPLACE, LOCK=NONE'
);
PREPARE enforcement_report_idx_stmt FROM @enforcement_report_idx_ddl;
EXECUTE enforcement_report_idx_stmt;
DEALLOCATE PREPARE enforcement_report_idx_stmt;

SET @enforcement_comment_idx_ddl := IF(
  EXISTS(SELECT 1 FROM information_schema.statistics
         WHERE table_schema=DATABASE() AND table_name='community_post_enforcement'
           AND index_name='idx_community_enforcement_comment'),
  'SELECT 1',
  'ALTER TABLE community_post_enforcement ADD INDEX idx_community_enforcement_comment (comment_id, status, id), ALGORITHM=INPLACE, LOCK=NONE'
);
PREPARE enforcement_comment_idx_stmt FROM @enforcement_comment_idx_ddl;
EXECUTE enforcement_comment_idx_stmt;
DEALLOCATE PREPARE enforcement_comment_idx_stmt;

DROP TEMPORARY TABLE community_enforcement_links_guard;

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='community_post_enforcement'
          AND column_name IN ('report_case_id','comment_id')
          AND column_type='bigint' AND is_nullable='YES' AND column_default IS NULL) = 2
   AND (SELECT COUNT(*) FROM (
          SELECT index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) cols
          FROM information_schema.statistics
          WHERE table_schema=DATABASE() AND table_name='community_post_enforcement' AND non_unique=1
            AND index_name IN ('idx_community_enforcement_report','idx_community_enforcement_comment')
          GROUP BY index_name) idx
        WHERE (index_name='idx_community_enforcement_report' AND cols='report_case_id,id')
           OR (index_name='idx_community_enforcement_comment' AND cols='comment_id,status,id')) = 2
   AS assertion_ok;
