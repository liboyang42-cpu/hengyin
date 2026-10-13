-- 路线草稿与运行快照分离。
-- 生产执行必须走 chengyin-db-migrate；本文件只随代码交付，不自动落库。

SET @published_route_graph_exists := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='cms_topic'
    AND column_name='published_route_graph_json');
SET @published_route_graph_sql := IF(@published_route_graph_exists=0,
  'ALTER TABLE cms_topic ADD COLUMN published_route_graph_json JSON NULL COMMENT ''最近一次成功发布的路线快照；NULL=LINEAR或尚未发布分支路线'' AFTER config_version',
  'SELECT 1');
PREPARE published_route_graph_stmt FROM @published_route_graph_sql;
EXECUTE published_route_graph_stmt;
DEALLOCATE PREPARE published_route_graph_stmt;

SET @published_route_version_exists := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='cms_topic'
    AND column_name='published_config_version');
SET @published_route_version_sql := IF(@published_route_version_exists=0,
  'ALTER TABLE cms_topic ADD COLUMN published_config_version BIGINT NOT NULL DEFAULT 0 COMMENT ''成功发布路线版本；草稿保存不递增'' AFTER published_route_graph_json',
  'SELECT 1');
PREPARE published_route_version_stmt FROM @published_route_version_sql;
EXECUTE published_route_version_stmt;
DEALLOCATE PREPARE published_route_version_stmt;

-- 兼容升级：只有已经不是草稿的旧主题取得发布版本。BRANCH_GRAPH 同时复制图；
-- LINEAR 保持快照 NULL，但以 published_config_version>0 表示它确实发布过。
-- 旧草稿绝不能借迁移被发布；config_version 至少为1，可作为首次发布版本。
UPDATE cms_topic
SET published_route_graph_json = CASE WHEN route_mode = 'BRANCH_GRAPH' THEN route_graph_json ELSE NULL END,
    published_config_version = GREATEST(COALESCE(config_version, 1), 1)
WHERE is_draft = 0
  AND (published_config_version = 0
    OR (route_mode = 'BRANCH_GRAPH' AND route_graph_json IS NOT NULL
      AND published_route_graph_json IS NULL));

-- runner: assert=scalar-true
SELECT (
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'cms_topic'
      AND column_name IN ('published_route_graph_json', 'published_config_version')) = 2
  AND NOT EXISTS (
    SELECT 1 FROM cms_topic
    WHERE is_draft = 1 AND published_route_graph_json IS NOT NULL
  )
  AND NOT EXISTS (
    SELECT 1 FROM cms_topic
    WHERE is_draft = 0 AND published_config_version < 1
  )
  AND NOT EXISTS (
    SELECT 1 FROM cms_topic
    WHERE is_draft = 0 AND route_mode = 'BRANCH_GRAPH' AND route_graph_json IS NOT NULL
      AND published_route_graph_json IS NULL
  )
) AS migration_assertion;

-- 回滚（执行前先确认没有新路线局依赖 published_config_version）：
-- ALTER TABLE cms_topic DROP COLUMN published_config_version, DROP COLUMN published_route_graph_json;
