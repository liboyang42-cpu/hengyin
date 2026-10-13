-- ============================================================================
-- M9 埋点与学习飞轮地基 · 节点级尝试日志(服务端,不依赖客户端)
-- ----------------------------------------------------------------------------
-- arrive/answer/checkin/photo 落 attempt;供可玩性 Lint 与 AI 出题评估集导出。
-- ⚠️ 生产执行前走 chengyin-db-migrate 只读预检。
-- ============================================================================

CREATE TABLE IF NOT EXISTS play_attempt_log (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  member_id    BIGINT       NULL,
  topic_id     BIGINT       NULL,
  node_id      BIGINT       NULL,
  activity_id  BIGINT       NULL,
  attempt_type TINYINT      NULL COMMENT '1答题 2扫码 3GPS 4拍照',
  result       TINYINT      NULL COMMENT '1过 0错 2放弃',
  wrong_answer VARCHAR(120) NULL COMMENT '答错时用户答案(供AI出题评估)',
  duration_s   INT          NULL COMMENT '本次尝试用时(秒,可空)',
  created_at   DATETIME     NULL,
  PRIMARY KEY (id),
  KEY idx_tn (topic_id, node_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='M9 节点级游玩尝试日志';

-- 周报视图:每节点 通过率 / 平均尝试 / 放弃率 / 平均用时(可玩性 Lint 原料)
CREATE OR REPLACE VIEW v_node_quality AS
SELECT
  topic_id,
  node_id,
  COUNT(*)                                                           AS attempt_cnt,
  SUM(CASE WHEN result = 1 THEN 1 ELSE 0 END)                        AS pass_cnt,
  SUM(CASE WHEN result = 0 THEN 1 ELSE 0 END)                        AS wrong_cnt,
  SUM(CASE WHEN result = 2 THEN 1 ELSE 0 END)                        AS abandon_cnt,
  ROUND(SUM(CASE WHEN result = 1 THEN 1 ELSE 0 END) / COUNT(*), 4)   AS pass_rate,
  ROUND(SUM(CASE WHEN result = 2 THEN 1 ELSE 0 END) / COUNT(*), 4)   AS abandon_rate,
  ROUND(AVG(duration_s), 1)                                          AS avg_duration_s,
  COUNT(DISTINCT member_id)                                          AS uniq_member_cnt
FROM play_attempt_log
GROUP BY topic_id, node_id;
