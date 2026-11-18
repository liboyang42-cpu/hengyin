-- F01（2026-11-18）：completion_mode 已有第三个取值 SKIPPED（跳过：本题 0 分、辅助完成），只补列注释。
-- 不改类型、不改数据。执行前走 chengyin-db-migrate 只读预检；本文件只生成，不直接操作生产库。

ALTER TABLE player_node_progress
    MODIFY COLUMN completion_mode VARCHAR(16) NULL
        COMMENT 'SOLVED正常解出/REVEALED查看答案辅助完成/SKIPPED跳过记0分辅助完成';
