-- 只读预检(不在库上写任何东西):cms_topic_node 坐标脏值分布,Doc2 F1d / survey C3-4。
-- 判据与 GeoCoordinates.parse 同口径:空、非数字、0、疑似经纬对调、越界。不 coerce 成 0,只统计与列出。
-- 走 chengyin-db-migrate 的只读预检流程执行;结果贴回 PR 供 owner 决定是否需要人工清洗。

SELECT dirty_reason, COUNT(*) AS node_count
FROM (
    SELECT CASE
             WHEN longitude IS NULL OR TRIM(longitude) = '' OR latitude IS NULL OR TRIM(latitude) = '' THEN 'MISSING'
             WHEN TRIM(longitude) NOT REGEXP '^-?[0-9]+(\\.[0-9]+)?$'
               OR TRIM(latitude) NOT REGEXP '^-?[0-9]+(\\.[0-9]+)?$' THEN 'NOT_NUMBER'
             WHEN CAST(TRIM(longitude) AS DECIMAL(20,10)) = 0 OR CAST(TRIM(latitude) AS DECIMAL(20,10)) = 0 THEN 'ZERO'
             WHEN ABS(CAST(TRIM(latitude) AS DECIMAL(20,10))) > 90
              AND ABS(CAST(TRIM(longitude) AS DECIMAL(20,10))) <= 90
              AND ABS(CAST(TRIM(latitude) AS DECIMAL(20,10))) <= 180 THEN 'SWAPPED_SUSPECT'
             WHEN ABS(CAST(TRIM(longitude) AS DECIMAL(20,10))) > 180
               OR ABS(CAST(TRIM(latitude) AS DECIMAL(20,10))) > 90 THEN 'OUT_OF_RANGE'
             ELSE 'CLEAN'
           END AS dirty_reason
    FROM cms_topic_node
) classified
GROUP BY dirty_reason
ORDER BY dirty_reason;

-- 明细(仅脏值,最多 500 行):
SELECT n.id AS node_id, n.topic_id, n.longitude, n.latitude
FROM cms_topic_node n
WHERE n.longitude IS NULL OR TRIM(n.longitude) = '' OR n.latitude IS NULL OR TRIM(n.latitude) = ''
   OR TRIM(n.longitude) NOT REGEXP '^-?[0-9]+(\\.[0-9]+)?$'
   OR TRIM(n.latitude) NOT REGEXP '^-?[0-9]+(\\.[0-9]+)?$'
   OR CAST(TRIM(n.longitude) AS DECIMAL(20,10)) = 0 OR CAST(TRIM(n.latitude) AS DECIMAL(20,10)) = 0
   OR ABS(CAST(TRIM(n.latitude) AS DECIMAL(20,10))) > 90
   OR ABS(CAST(TRIM(n.longitude) AS DECIMAL(20,10))) > 180
ORDER BY n.topic_id, n.id
LIMIT 500;
