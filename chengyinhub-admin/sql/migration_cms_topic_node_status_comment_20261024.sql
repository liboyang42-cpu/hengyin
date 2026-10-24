-- C:U-078:cms_topic_node.status 列注释写成了「状态0不显示 1不显示」,与代码口径相反
-- (代码里 status=1 即显示:CmsTopicServiceImpl 新建节点 setStatus(1L) // 默认显示,各处探针按 status=1 计节点)。
-- 本文件只改注释,不改类型/默认值/可空性;类型统一(int vs tinyint 混用)属另案,不在此处。
-- 前置:cms_topic_node 在 sql/base_schema_20260818.sql,定义为 `status` int DEFAULT '0'。
--
-- ⚠️ MODIFY COLUMN 会用这里写的定义整条替换原定义 —— 漏写哪个属性就是静默删除哪个属性。
--    下面的守卫先确认库里的列正是 int、可空、默认 '0',形状不符就停在守卫上(主键冲突),不改任何东西。
-- 幂等:重跑只是把同一条注释再写一遍;ALGORITHM=INSTANT 只改元数据,不支持时响亮报错而不是重建表。
DROP TEMPORARY TABLE IF EXISTS cms_topic_node_status_comment_20261024_guard;
CREATE TEMPORARY TABLE cms_topic_node_status_comment_20261024_guard(id TINYINT PRIMARY KEY);
INSERT INTO cms_topic_node_status_comment_20261024_guard VALUES(1);

INSERT INTO cms_topic_node_status_comment_20261024_guard
SELECT 1 WHERE (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='cms_topic_node' AND column_name='status'
    AND data_type='int' AND is_nullable='YES' AND column_default='0') <> 1;

DROP TEMPORARY TABLE cms_topic_node_status_comment_20261024_guard;

ALTER TABLE cms_topic_node
    MODIFY COLUMN status int DEFAULT '0' COMMENT '状态 0不显示 1显示',
    ALGORITHM=INSTANT;

-- 落库后回读:注释已更正,类型/默认值/可空性不变。
SELECT column_type, column_default, is_nullable, column_comment
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'cms_topic_node'
  AND column_name = 'status';
