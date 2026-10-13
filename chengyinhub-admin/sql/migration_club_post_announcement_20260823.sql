-- 俱乐部置顶公告与公开编辑历史。
-- 前置：club_post、migration_business_audit_event_20260823.sql。
-- 仅生成迁移文件；上线前必须单独走 chengyin-db-migrate 预检、执行与业务读回。
--
-- ALTER 与 revision CREATE 是两个独立原子 DDL；允许「全旧」或「ALTER 已完成」两个边界，
-- 拒绝单列/索引/CHECK 残缺候选形态。这使 ALTER 成功后断线可直接重放。
SET SESSION group_concat_max_len = 65535;
SET @club_post_announcement_column_count := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'club_post'
    AND column_name IN ('is_pinned','pinned_at','version','update_time')
);
SET @club_post_announcement_column_hash := (
  SELECT SHA2(GROUP_CONCAT(CONCAT_WS('~', column_name, LOWER(column_type), is_nullable,
    COALESCE(LOWER(CAST(column_default AS CHAR)), '<null>'), LOWER(extra),
    COALESCE(character_set_name, '<null>'),
    CASE WHEN character_set_name IS NULL OR character_set_name = 'utf8mb4'
      THEN COALESCE(character_set_name, '<null>') ELSE COALESCE(collation_name, '<null>') END,
    COALESCE(REGEXP_REPLACE(LOWER(generation_expression),
      '[[:space:]`]|_utf8mb[34]|_utf8|_latin1|_gbk', ''), '<null>'))
    ORDER BY ordinal_position SEPARATOR '|'), 256)
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'club_post'
    AND column_name IN ('is_pinned','pinned_at','version','update_time')
);
SET @club_post_announcement_index_shape := (
  SELECT IFNULL(CONCAT(MIN(non_unique), ':', MIN(index_type), ':', MIN(is_visible), ':',
    SUM(sub_part IS NOT NULL), ':', SUM(collation = 'A'), ':', COUNT(*), ':',
    GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')), '')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'club_post'
    AND index_name = 'idx_club_post_pinned'
);
SET @club_post_announcement_check_count := (
  SELECT COUNT(*)
  FROM information_schema.table_constraints tc
  JOIN information_schema.check_constraints cc
    ON cc.constraint_schema = tc.constraint_schema AND cc.constraint_name = tc.constraint_name
  WHERE tc.constraint_schema = DATABASE() AND tc.table_name = 'club_post'
    AND tc.constraint_type = 'CHECK' AND cc.constraint_name = 'chk_club_post_pinned'
);
SET @club_post_announcement_check_hash := (
  SELECT SHA2(GROUP_CONCAT(CONCAT_WS('~', cc.constraint_name, tc.enforced,
    SHA2(REGEXP_REPLACE(LOWER(cc.check_clause),
      '[[:space:]`]|_utf8mb[34]|_utf8|_latin1|_gbk', ''), 256))
    ORDER BY cc.constraint_name SEPARATOR '|'), 256)
  FROM information_schema.table_constraints tc
  JOIN information_schema.check_constraints cc
    ON cc.constraint_schema = tc.constraint_schema AND cc.constraint_name = tc.constraint_name
  WHERE tc.constraint_schema = DATABASE() AND tc.table_name = 'club_post'
    AND tc.constraint_type = 'CHECK' AND cc.constraint_name = 'chk_club_post_pinned'
);
SET @club_post_announcement_shape_exact :=
  @club_post_announcement_column_count = 4
  AND @club_post_announcement_column_hash = 'cd1708419c1b0faeae7e33480385bdd5cccedc2374ddaf3220a4c50f66c15596'
  AND @club_post_announcement_index_shape =
    '1:BTREE:YES:0:5:5:club_id,del_flag,is_pinned,pinned_at,id'
  AND @club_post_announcement_check_count = 1
  AND @club_post_announcement_check_hash =
    '0f9423cb74931199f999d6a9ec27e055f1b51eb954b083be97afbaabf81c837f';

-- runner: assert=scalar-true
SELECT ((@club_post_announcement_column_count = 0
          AND @club_post_announcement_index_shape = ''
          AND @club_post_announcement_check_count = 0)
        OR @club_post_announcement_shape_exact) AS assertion_ok;

SET @club_post_announcement_alter_sql := IF(
  @club_post_announcement_shape_exact,
  'SELECT ''club_post announcement shape already exact, skip ALTER''',
  'ALTER TABLE `club_post`
     ADD COLUMN `is_pinned` TINYINT NOT NULL DEFAULT 0 COMMENT ''仅公告可置顶'' AFTER `del_flag`,
     ADD COLUMN `pinned_at` DATETIME DEFAULT NULL COMMENT ''最近置顶时间'' AFTER `is_pinned`,
     ADD COLUMN `version` INT NOT NULL DEFAULT 0 COMMENT ''编辑/置顶乐观锁'' AFTER `pinned_at`,
     ADD COLUMN `update_time` DATETIME DEFAULT NULL COMMENT ''最近编辑或置顶时间'' AFTER `create_time`,
     ADD KEY `idx_club_post_pinned` (`club_id`,`del_flag`,`is_pinned`,`pinned_at`,`id`),
     ADD CONSTRAINT `chk_club_post_pinned` CHECK
       (`is_pinned` IN (0,1) AND (`is_pinned` = 0 OR `type` <=> 2)) ENFORCED'
);
PREPARE club_post_announcement_alter_stmt FROM @club_post_announcement_alter_sql;
EXECUTE club_post_announcement_alter_stmt;
DEALLOCATE PREPARE club_post_announcement_alter_stmt;

CREATE TABLE IF NOT EXISTS club_post_revision (
  id BIGINT NOT NULL AUTO_INCREMENT,
  post_id BIGINT NOT NULL,
  club_id BIGINT NOT NULL,
  editor_member_id BIGINT NOT NULL,
  snapshot_version INT NOT NULL COMMENT '本次修改前的 club_post.version',
  content TEXT DEFAULT NULL,
  images VARCHAR(2000) DEFAULT NULL,
  type INT NOT NULL,
  create_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_club_post_revision_version (post_id, snapshot_version),
  KEY idx_club_post_revision_history (post_id, id),
  KEY idx_club_post_revision_club (club_id, create_time, id),
  CONSTRAINT chk_club_post_revision_version CHECK (snapshot_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='俱乐部动态编辑前不可变快照';

-- runner: assert=scalar-true
SELECT
  (SELECT COUNT(*) FROM information_schema.tables
   WHERE table_schema = DATABASE() AND engine = 'InnoDB'
     AND table_name IN ('club_post','club_post_revision')) = 2
  AND (SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND
        ((table_name = 'club_post' AND column_name IN ('is_pinned','pinned_at','version','update_time'))
         OR (table_name = 'club_post_revision' AND column_name IN
          ('post_id','club_id','editor_member_id','snapshot_version','content','images','type','create_time')))) = 12
  AND EXISTS (
    SELECT 1 FROM (
      SELECT index_name, MIN(non_unique) AS non_unique,
             GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') AS cols
      FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND table_name = 'club_post_revision'
      GROUP BY index_name
    ) exact_unique
    WHERE index_name = 'uk_club_post_revision_version' AND non_unique = 0
      AND cols = 'post_id,snapshot_version')
  AND (SELECT COUNT(*) FROM information_schema.table_constraints
       WHERE table_schema = DATABASE() AND constraint_type = 'CHECK'
         AND enforced = 'YES'
         AND constraint_name IN ('chk_club_post_pinned','chk_club_post_revision_version')) = 2
  AND EXISTS (
    SELECT 1
    FROM information_schema.table_constraints tc
    JOIN information_schema.check_constraints cc
      ON cc.constraint_schema = tc.constraint_schema
     AND cc.constraint_name = tc.constraint_name
    WHERE tc.constraint_schema = DATABASE()
      AND tc.table_name = 'club_post'
      AND tc.constraint_name = 'chk_club_post_pinned'
      AND tc.enforced = 'YES'
      AND SHA2(REPLACE(REPLACE(LOWER(cc.check_clause), '`', ''), ' ', ''), 256)
        = 'f9ba85873b52394c09bfb30afb76c372d4240b7ac7b9b0857952dfb9b0e1d684')
  AND (SELECT COUNT(*) FROM club_post
       WHERE is_pinned = 1 AND (type IS NULL OR type <> 2)) = 0
  AS assertion_ok;

-- 终态重新从库中计算，不能复用 ALTER 前的 session 变量。
-- club_post 只固定本迁移新增四列；revision 表固定全部九列。
SET SESSION group_concat_max_len = 65535;
-- runner: assert=scalar-true
SELECT COUNT(*) = 2
  AND SUM(CASE
    WHEN table_name = 'club_post' AND column_count = 4
      AND column_hash = 'cd1708419c1b0faeae7e33480385bdd5cccedc2374ddaf3220a4c50f66c15596' THEN 1
    WHEN table_name = 'club_post_revision' AND column_count = 9
      AND column_hash = '77b527b91cf0223e5448ff56b03b262540ca583f77407c1a62fbcfece0d87da4' THEN 1
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
    AND ((table_name = 'club_post'
          AND column_name IN ('is_pinned','pinned_at','version','update_time'))
      OR table_name = 'club_post_revision')
  GROUP BY table_name
) exact_columns;

-- 终态精确固定本迁移的 CHECK 名称、ENFORCED 与保留括号分组的正文。
-- runner: assert=scalar-true
SELECT COUNT(*) = 2
  AND SUM(CASE
    WHEN table_name = 'club_post' AND check_count = 1
      AND check_hash = '0f9423cb74931199f999d6a9ec27e055f1b51eb954b083be97afbaabf81c837f' THEN 1
    WHEN table_name = 'club_post_revision' AND check_count = 1
      AND check_hash = '64fc12be38727a8fa9f88c730a3700b7eae5b503ac4a96ae5742b974ead0f289' THEN 1
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
    AND ((tc.table_name = 'club_post' AND tc.constraint_name = 'chk_club_post_pinned')
      OR tc.table_name = 'club_post_revision')
  GROUP BY tc.table_name
) exact_checks;

-- 终态精确固定置顶索引的列序、可见性、类型、前缀与升序属性。
-- runner: assert=scalar-true
SELECT IFNULL(CONCAT(MIN(non_unique), ':', MIN(index_type), ':', MIN(is_visible), ':',
         SUM(sub_part IS NOT NULL), ':', SUM(collation = 'A'), ':', COUNT(*), ':',
         GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')), '') =
       '1:BTREE:YES:0:5:5:club_id,del_flag,is_pinned,pinned_at,id' AS assertion_ok
FROM information_schema.statistics
WHERE table_schema = DATABASE() AND table_name = 'club_post'
  AND index_name = 'idx_club_post_pinned';
