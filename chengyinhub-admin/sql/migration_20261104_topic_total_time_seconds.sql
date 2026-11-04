-- 主题总时长统一为秒(R11 g3 第 3 项,j3 F7)
--
-- 病根:cms_topic.total_time 建表注释是「总时长,单位秒」,种子主题(模板货架 360、官方单人 5400、
--   生活预制 7200 等)按秒写,小程序与后台都按秒格式化;只有编辑器建题/改题的
--   CmsTopicServiceImpl.calculateTotalTime 把节点 node_time(分钟)直接相加落库。
--   于是 27 站 × 10 分钟的主题存 270,详情页读成「约 4 分钟」。
-- 代码同批改为落秒(totalTimeSeconds = 分钟和 × 60);本文件只修编辑器写过的存量行。
--
-- 改什么:total_time 恰好等于本主题节点 node_time 之和(全部节点,或只算主办方自建节点)
--   且大于 0 的行,改为该值 × 60。这类行就是编辑器按分钟写下的;种子行的秒数不会恰好等于
--   它节点的分钟和,不命中。
-- 不改什么:其他列;没有节点或节点没填时长的主题(total_time = 0);cms_topic_chapter.total_time
--   (没有任何写入方,恒为空)。
--
-- 可重跑:改过的行 = 分钟和 × 60 ≠ 分钟和,第二次命中 0 行。
-- 回退:执行前 SELECT id, total_time 留档;回退按留档逐行写回(或对本次命中的 id 执行 total_time = total_time / 60)。

UPDATE cms_topic t
JOIN (
    SELECT topic_id,
           SUM(IFNULL(node_time, 0)) AS all_minutes,
           SUM(CASE WHEN merchant_member_id IS NULL THEN IFNULL(node_time, 0) ELSE 0 END) AS host_minutes
    FROM cms_topic_node
    WHERE topic_id IS NOT NULL
    GROUP BY topic_id
) n ON n.topic_id = t.id
SET t.total_time = t.total_time * 60
WHERE t.total_time > 0
  AND (t.total_time = n.all_minutes OR t.total_time = n.host_minutes);

-- runner: assert=scalar-true
SELECT COUNT(*) = 0 AS topic_total_time_in_seconds
FROM cms_topic t
JOIN (
    SELECT topic_id,
           SUM(IFNULL(node_time, 0)) AS all_minutes,
           SUM(CASE WHEN merchant_member_id IS NULL THEN IFNULL(node_time, 0) ELSE 0 END) AS host_minutes
    FROM cms_topic_node
    WHERE topic_id IS NOT NULL
    GROUP BY topic_id
) n ON n.topic_id = t.id
WHERE t.total_time > 0
  AND (t.total_time = n.all_minutes OR t.total_time = n.host_minutes);
