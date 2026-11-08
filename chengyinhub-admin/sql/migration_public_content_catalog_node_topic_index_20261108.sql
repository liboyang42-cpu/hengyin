-- 公开目录读面索引补缺：cms_topic_node(topic_id) 左前缀。
--
-- 为什么必须新增（已交叉核对全部 31 个提及 cms_topic_node 的 SQL 文件）：
--   基表只有 PRIMARY KEY(id)、idx_topic_node_chapter_merchant(chapter_id, merchant_member_id)、
--   以及 uk_cms_topic_node_checkin_code(checkin_code)。没有任何索引以 topic_id 为左前缀。
--   而 M7 公开目录读面（PublicContentCatalogMapper.xml）对节点表的每个访问都是
--   `where node.topic_id = ?` / `where probe.topic_id = ?`，现为全表扫描。
--   对照：cms_topic_chapter.topic_id 已被 idx_chapter_circle_selection(topic_id, ...) 覆盖，
--   故本文件只加节点表这一条，不重复加章表。
--
-- 只新增索引：不改列、不回填、不动任何数据；不执行生产 DDL，须走 chengyin-db-migrate 只读预检。
-- runner: assert=scalar-true
SELECT CAST(IF(DATABASE() IS NOT NULL
  AND (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
    AND table_name='cms_topic_node' AND UPPER(engine)='INNODB')=1
  AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='cms_topic_node' AND column_name='topic_id')=1
  AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='cms_topic_node' AND index_name='idx_topic_node_topic')=0,
  1,JSON_EXTRACT('ASSERT_FAILED_cms_topic_node_topic_index_preflight','$')) AS UNSIGNED) AS assertion_ok;

ALTER TABLE cms_topic_node
  ADD KEY idx_topic_node_topic (topic_id);

-- runner: assert=scalar-true
SELECT CAST(IF(
  (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='cms_topic_node' AND index_name='idx_topic_node_topic')=1
  AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='cms_topic_node' AND index_name='idx_topic_node_topic'
    AND seq_in_index=1 AND column_name='topic_id' AND non_unique=1)=1,
  1,JSON_EXTRACT('ASSERT_FAILED_cms_topic_node_topic_index_readback','$')) AS UNSIGNED) AS assertion_ok;
