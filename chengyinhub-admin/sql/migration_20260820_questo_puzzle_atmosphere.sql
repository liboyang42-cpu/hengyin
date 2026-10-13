-- Questo 借鉴项：城市定向谜题表现 + 章节氛围预设。
-- 执行前必须走 chengyin-db-migrate；本文件只生成，不直接操作生产库。

ALTER TABLE player_node_progress
    ADD COLUMN puzzle_score SMALLINT NULL COMMENT '城市定向谜题得分0-100;非谜题为空' AFTER latitude,
    ADD COLUMN completion_mode VARCHAR(16) NULL COMMENT 'SOLVED正常解出/REVEALED查看答案辅助完成' AFTER puzzle_score;

ALTER TABLE cms_topic_chapter
    ADD COLUMN atmosphere_preset VARCHAR(16) NOT NULL DEFAULT 'DEFAULT'
        COMMENT '章节氛围预设:DEFAULT/NIGHT/ARCHIVE/MOSS/NEON' AFTER blocks;

-- play_attempt_log 不加列：attempt_type=5 表示谜题提示，result=1/2 表示 Hint1/Hint2。
-- 质量视图只统计真正的节点尝试；提示等级恰好也用 1/2，若不排除会污染通过率/放弃率。
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
WHERE attempt_type IS NULL OR attempt_type <> 5
GROUP BY topic_id, node_id;
