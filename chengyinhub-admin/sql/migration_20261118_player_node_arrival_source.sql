-- 2026-11-18 F08 到达来源:player_node_progress.arrival_source
-- QR      = 扫店内码 / 一次性游戏码(/api/play/checkin)
-- GEO_TAP = 自由探索 300 米内点「我已到店」(/api/play/arrive 定位通过)
-- TAP     = 城市定向开局后逐站点「我已到达」
-- ONLINE  = 纯线上节点(无店、无坐标)
-- 排队计时 queue_joined_at 只认 QR(GEO_TAP 是玩家手机自报的位置,不能当成「在店门口扫了码」)。
-- 只加一个可空列,可重复执行;回滚见 bak_player_node_arrival_source_20261118_rollback.sql。
-- 只读预检:
-- SELECT COLUMN_NAME FROM information_schema.COLUMNS
--  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'player_node_progress' AND COLUMN_NAME = 'arrival_source';

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'player_node_progress'
                 AND column_name = 'arrival_source') = 0,
    'ALTER TABLE player_node_progress ADD COLUMN arrival_source VARCHAR(16) NULL COMMENT ''QR|GEO_TAP|TAP|ONLINE;排队计时只认 QR'' AFTER checkin_time',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 存量回填:改动前自由探索(product_type=2)实体店的到店行只可能来自扫码(/arrive 对实体店一律拒绝),
-- 所以已有 checkin_time 的自由探索行记为 QR,排队口径与改动前一致。城市定向的行保持 NULL(不参与排队)。
UPDATE player_node_progress p
  JOIN cms_topic t ON t.id = p.topic_id AND t.product_type = 2
   SET p.arrival_source = 'QR'
 WHERE p.arrival_source IS NULL AND p.checkin_time IS NOT NULL;
