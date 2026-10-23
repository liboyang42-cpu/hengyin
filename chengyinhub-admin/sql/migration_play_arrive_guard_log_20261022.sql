-- Doc2 F1c / O-01(2026-10-22):/api/play/arrive 位置守卫判定留痕表。只新建表,不改既有表、不回填数据。
-- 读写方:ArriveLocationGuard(chengyinhub-system)。仅在 sys_config['play.arrive.location_guard.mode'] 为
-- shadow / enforce 时读写;默认 off 不碰本表,所以新 jar 先于本迁移上线也不会报错(守卫表不可用时按放行处理)。
-- 打开开关之前必须先落本迁移。
-- 只记服务端站点坐标与端上上报精度,不落玩家原始定位。被拒行的 id 即申诉引用号
-- (audit_appeal.biz_type = 'play_arrive_guard', biz_id = play_arrive_guard_log.id)。
-- 幂等:表不存在则建;已存在则核对列 fingerprint,不一致时 guard 主键冲突让执行器报错,不改不删。
-- ⚠️ 生产执行前必须走 chengyin-db-migrate 的只读预检 + owner 确认,本 PR 不在任何库上执行。
-- 回滚(确认开关为 off 后):DROP TABLE play_arrive_guard_log;
DROP TEMPORARY TABLE IF EXISTS play_arrive_guard_log_guard;
CREATE TEMPORARY TABLE play_arrive_guard_log_guard(id TINYINT PRIMARY KEY);
INSERT INTO play_arrive_guard_log_guard VALUES(1);

CREATE TABLE IF NOT EXISTS play_arrive_guard_log (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键,被拒行即申诉引用号',
  member_id BIGINT NOT NULL COMMENT '玩家 member_id',
  activity_id BIGINT NULL COMMENT '活动会话 id(自玩会话为 null)',
  topic_id BIGINT NULL COMMENT '主题 id',
  node_id BIGINT NOT NULL COMMENT '节点 id',
  node_lat DECIMAL(10,6) NOT NULL COMMENT '本次判定用的站点纬度(服务端配置,非玩家定位)',
  node_lng DECIMAL(10,6) NOT NULL COMMENT '本次判定用的站点经度(服务端配置,非玩家定位)',
  accuracy_m DECIMAL(10,1) NULL COMMENT '端上上报的水平精度(米),未上报为 null',
  verdict VARCHAR(16) NOT NULL COMMENT 'PASS/REJECT/SHADOW(观察模式本应拒绝、实际放行)',
  reason_code VARCHAR(40) NULL COMMENT 'ARRIVE_GUARD_THROTTLE/SPEED/WINDOW_DISTANCE;PASS 为 null',
  detail VARCHAR(255) NULL COMMENT '判定数据(保守位移、时间差、速度),供申诉审核',
  created_at DATETIME(3) NOT NULL COMMENT '判定时间(服务端)',
  PRIMARY KEY (id),
  KEY idx_play_arrive_guard_member_time (member_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='O-01 GPS 到达位置守卫判定留痕';

-- 同名但形状不对的旧表禁止当成已完成:列 fingerprint。
SET @play_arrive_guard_log_columns := (SELECT GROUP_CONCAT(CONCAT_WS(':', column_name, column_type, is_nullable)
    ORDER BY ordinal_position SEPARATOR '|')
  FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'play_arrive_guard_log');
INSERT INTO play_arrive_guard_log_guard
SELECT 1 FROM DUAL WHERE COALESCE(@play_arrive_guard_log_columns, '') <> CONCAT(
  'id:bigint:NO|member_id:bigint:NO|activity_id:bigint:YES|topic_id:bigint:YES|node_id:bigint:NO',
  '|node_lat:decimal(10,6):NO|node_lng:decimal(10,6):NO|accuracy_m:decimal(10,1):YES',
  '|verdict:varchar(16):NO|reason_code:varchar(40):YES|detail:varchar(255):YES|created_at:datetime(3):NO');
DROP TEMPORARY TABLE play_arrive_guard_log_guard;

-- runner: assert=scalar-true
SELECT COUNT(*) = 2 AS assertion_ok FROM information_schema.statistics
WHERE table_schema = DATABASE() AND table_name = 'play_arrive_guard_log'
  AND index_name = 'idx_play_arrive_guard_member_time';
