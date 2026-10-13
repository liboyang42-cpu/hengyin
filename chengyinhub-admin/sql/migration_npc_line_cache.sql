-- ==================================================
-- 城瘾 AI NPC 陪伴系统: 事件冒泡话术缓存(预生成+人审)
-- 执行前预检: SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'npc_line_cache';
-- ==================================================

CREATE TABLE npc_line_cache (
  id            BIGINT       PRIMARY KEY AUTO_INCREMENT,
  profile_id    BIGINT       NOT NULL COMMENT '关联 npc_profile.id',
  event_type    VARCHAR(32)  NOT NULL COMMENT 'enter/checkin_success/answer_wrong/answer_right/near_merchant/activity_finish',
  node_id       BIGINT       DEFAULT NULL COMMENT '可选:绑定到具体节点(同事件按节点出不同话)',
  variants_json TEXT         NOT NULL COMMENT '同一事件的多条话术变体[](随机取一条防重复)',
  audit_status  TINYINT      DEFAULT 0 COMMENT '0=待审 1=通过(只有通过的才会下发)',
  create_time   DATETIME,
  update_time   DATETIME,
  KEY idx_lookup (profile_id, event_type, node_id, audit_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='NPC 事件话术缓存(离线预生成+人审)';
