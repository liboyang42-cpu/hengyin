-- ============================================================================
-- M5 途中彩蛋池 npc_route_egg(NPC 台词缓存 npc_line_cache 已存在,本迁移只加彩蛋表)
-- ⚠️ 生产执行前走 chengyin-db-migrate 只读预检。
-- ============================================================================

CREATE TABLE IF NOT EXISTS npc_route_egg (
  id         BIGINT        NOT NULL AUTO_INCREMENT,
  topic_id   BIGINT        NULL,
  seq        INT           NULL COMMENT '沿路顺序',
  lat        DECIMAL(10,6) NULL,
  lng        DECIMAL(10,6) NULL,
  radius_m   INT           DEFAULT 120 COMMENT '触发半径(米)',
  text       VARCHAR(300)  NULL COMMENT '彩蛋文案(≤80字,预审入库)',
  status     TINYINT       NULL COMMENT '0待审 1已审',
  created_at DATETIME      NULL,
  PRIMARY KEY (id),
  KEY idx_topic (topic_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='M5 途中彩蛋池(每主题≤15条)';
