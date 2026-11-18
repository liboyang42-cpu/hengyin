-- 2026-11-18 自由探索:地点到访方式与到访事实(FE-STORY-1117 第五节)。只对 product_type=2 生效;城市定向不读这些列。
-- 规则:到访 = 该地点 GPS 校验通过(默认 300 米,可配 300-500 米),或在配置为扫码的地点扫码成功;纯线上节点开票即算到访。
-- 只有已到访地点的章节剧情会下发。可重复执行;回滚见 bak_fe_story_visit_gate_20261118_rollback.sql。
-- 只读预检:
-- SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.COLUMNS
--  WHERE TABLE_SCHEMA = DATABASE()
--    AND ((TABLE_NAME='cms_topic_node' AND COLUMN_NAME IN ('visit_check','visit_radius_m'))
--      OR (TABLE_NAME='player_node_progress' AND COLUMN_NAME IN ('visit_method','visit_time')));

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'cms_topic_node' AND column_name = 'visit_check') = 0,
    'ALTER TABLE cms_topic_node ADD COLUMN visit_check TINYINT NOT NULL DEFAULT 0 COMMENT ''自由探索到访方式:0 GPS(默认) 1 扫码 2 纯线上''',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'cms_topic_node' AND column_name = 'visit_radius_m') = 0,
    'ALTER TABLE cms_topic_node ADD COLUMN visit_radius_m SMALLINT NULL COMMENT ''自由探索 GPS 半径(米),空=300,服务端夹到 300-500''',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'player_node_progress' AND column_name = 'visit_method') = 0,
    'ALTER TABLE player_node_progress ADD COLUMN visit_method TINYINT NULL COMMENT ''自由探索首次到访方式:1 GPS 2 扫码 3 线上 4 队友共享''',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'player_node_progress' AND column_name = 'visit_time') = 0,
    'ALTER TABLE player_node_progress ADD COLUMN visit_time DATETIME NULL COMMENT ''自由探索首次到访时间;非空即已到访,写入后不再改''',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 存量回填:已经扫码到店的 FE 进度算作已到访(扫码)。
UPDATE player_node_progress p
  JOIN cms_topic t ON t.id = p.topic_id AND t.product_type = 2
   SET p.visit_method = 2, p.visit_time = p.checkin_time
 WHERE p.visit_time IS NULL AND p.checkin_time IS NOT NULL;
-- 存量 FE 节点:没有坐标但有打卡码的,改为扫码;两样都没有的,改为纯线上,避免上线后无法到访。
UPDATE cms_topic_node n JOIN cms_topic t ON t.id = n.topic_id AND t.product_type = 2
   SET n.visit_check = 1
 WHERE n.visit_check = 0
   AND (n.longitude IS NULL OR n.longitude = '' OR n.latitude IS NULL OR n.latitude = '')
   AND n.checkin_code IS NOT NULL AND n.checkin_code <> '';
UPDATE cms_topic_node n JOIN cms_topic t ON t.id = n.topic_id AND t.product_type = 2
   SET n.visit_check = 2
 WHERE n.visit_check = 0
   AND (n.longitude IS NULL OR n.longitude = '' OR n.latitude IS NULL OR n.latitude = '')
   AND (n.checkin_code IS NULL OR n.checkin_code = '');
