-- qx-c1 #7（U08-02 完局问题反馈）：玩家对自己这一局的地点/题目问题反馈。
-- 现有 /api/topic/report、/api/community-trust/reports/submit 是举报审核，不带节点与修订身份，不复用为反馈。
-- 一行 = 一条反馈事实：归属会员 + 原局（topic_route_session）+ 原局冻结版本/修订 + 可选节点；request_id 幂等。
-- 不承载任何金额、审核结论或处理状态以外的业务字段。生产执行必须走 chengyin-db-migrate；本文件只随代码交付。

CREATE TABLE IF NOT EXISTS play_run_feedback (
  id BIGINT NOT NULL AUTO_INCREMENT,
  member_id BIGINT NOT NULL COMMENT '提交者；必须是原局 owner',
  route_session_id BIGINT NOT NULL COMMENT '原局 topic_route_session.id',
  topic_id BIGINT NOT NULL COMMENT '原局主题（服务端从原局取，不信客户端）',
  activity_id BIGINT NOT NULL DEFAULT 0 COMMENT '原局场次；自玩为0',
  play_instance_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '原局游玩实例',
  config_version BIGINT NULL COMMENT '原局冻结的发布版本',
  content_revision_id BIGINT NULL COMMENT '原局冻结内容对应的 cms_topic_content_revision.id；匹配不到为 NULL（未知，不是0）',
  node_id BIGINT NULL COMMENT '问题节点；必须属于原局主题',
  category VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'PLACE/QUESTION/OTHER',
  content VARCHAR(500) NOT NULL COMMENT '已过内容安全检测的反馈正文',
  request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '客户端幂等号',
  request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '幂等输入指纹；同号异内容拒绝',
  status TINYINT NOT NULL DEFAULT 0 COMMENT '0=已收到',
  create_time DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_play_run_feedback_request (member_id, request_id),
  KEY idx_play_run_feedback_topic (topic_id, create_time),
  KEY idx_play_run_feedback_session (route_session_id),
  CONSTRAINT ck_play_run_feedback_category CHECK (category IN ('PLACE', 'QUESTION', 'OTHER')),
  CONSTRAINT ck_play_run_feedback_status CHECK (status IN (0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='U08-02 完局问题反馈（玩家 → 原局/节点/修订）';

-- runner: assert=scalar-true
SELECT (
  (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_name = 'play_run_feedback' AND engine = 'InnoDB'
      AND table_collation = 'utf8mb4_general_ci') = 1
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'play_run_feedback') = 15
  AND (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'play_run_feedback'
      AND index_name = 'uk_play_run_feedback_request' AND non_unique = 0) = 2
) AS play_run_feedback_ready;
