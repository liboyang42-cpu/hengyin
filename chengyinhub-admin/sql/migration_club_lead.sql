-- ============================================================================
-- P1 城市定向 · 俱乐部带领(集合/行进/结算;队长控章节+轮询,不上 WebSocket)
-- ⚠️ 生产执行前走 chengyin-db-migrate 只读预检。
-- ============================================================================

CREATE TABLE IF NOT EXISTS club_lead_state (
  id                 BIGINT       NOT NULL AUTO_INCREMENT,
  activity_id        BIGINT       NOT NULL,
  leader_member_id   BIGINT       NOT NULL,
  current_chapter_id BIGINT       DEFAULT NULL COMMENT '当前已放行章节(队长控)',
  broadcast_text     VARCHAR(200) DEFAULT NULL,
  broadcast_at       DATETIME     DEFAULT NULL,
  status             TINYINT      DEFAULT 1 COMMENT '1=集合中 2=行进 3=已结算',
  create_time        DATETIME     DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_activity (activity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P1 带领场状态';

CREATE TABLE IF NOT EXISTS club_lead_checkin (
  id          BIGINT   NOT NULL AUTO_INCREMENT,
  activity_id BIGINT   NOT NULL,
  member_id   BIGINT   NOT NULL,
  arrived_at  DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_arrive (activity_id, member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P1 集合签到(幂等)';
