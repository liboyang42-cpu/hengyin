-- 统一审核任务可重复提审 + 单未结任务并发闸（先于对应新 jar 落库）。
-- 旧唯一键错误复用了乐观锁 version：终态任务 version 会增长，二次审核时可能互相冲突。
ALTER TABLE audit_task
  DROP INDEX uk_audit_biz_version,
  ADD COLUMN open_guard TINYINT
    GENERATED ALWAYS AS (CASE WHEN status IN (0, 3) THEN 1 ELSE NULL END) STORED
    COMMENT '未结任务唯一闸：终态为NULL以允许重复提审',
  ADD UNIQUE KEY uk_audit_biz_open (biz_type, biz_id, open_guard);

SELECT index_name, column_name, seq_in_index
FROM information_schema.statistics
WHERE table_schema = DATABASE() AND table_name = 'audit_task'
  AND index_name = 'uk_audit_biz_open'
ORDER BY seq_in_index;
