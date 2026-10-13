-- ==================================================
-- 城瘾 AI NPC 陪伴系统: 追问对话日志(审计+优化语料)
-- 执行前预检: SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'npc_chat_log';
-- ==================================================

CREATE TABLE npc_chat_log (
  id            BIGINT       PRIMARY KEY AUTO_INCREMENT,
  user_id       BIGINT       NOT NULL,
  profile_id    BIGINT       NOT NULL,
  activity_id   BIGINT       NOT NULL COMMENT '记忆作用域',
  user_msg      TEXT         NOT NULL,
  npc_reply     TEXT,
  safety_flag   TINYINT      DEFAULT 0 COMMENT '0=正常 1=输入被拦 2=输出被拦 3=降级兜底',
  model         VARCHAR(32)  COMMENT '实际命中的模型',
  latency_ms    INT,
  token_in      INT,
  token_out     INT,
  create_time   DATETIME,
  KEY idx_user_activity (user_id, activity_id),
  KEY idx_safety (safety_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='NPC 追问日志(审计与后续RAG/微调语料)';
