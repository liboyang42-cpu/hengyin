-- C 后续候选：高级动作请求指纹。仅编写，不在代码任务执行生产 DDL。
-- 前置：play_attempt_log/result_json/idempotency_key 已存在，InnoDB，原 key 唯一索引完整。
-- 用支持 runner assert 的受控迁移路径执行；预检失败必须停止，不得忽略结果继续。
-- 不回填历史 NULL，不修改/删除旧回执、唯一索引或配额记录。

SET @advanced_fingerprint_precheck := (
    (SELECT COUNT(*) FROM information_schema.tables
      WHERE table_schema=DATABASE() AND table_name='play_attempt_log' AND engine='InnoDB')=1
    AND (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='play_attempt_log'
        AND column_name IN ('result_json','idempotency_key'))=2
    AND (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema=DATABASE() AND table_name='play_attempt_log'
        AND index_name='uk_play_attempt_idempotency')=1
    AND (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema=DATABASE() AND table_name='play_attempt_log'
        AND index_name='uk_play_attempt_idempotency' AND non_unique=0
        AND seq_in_index=1 AND column_name='idempotency_key' AND sub_part IS NULL)=1
    AND ((SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='play_attempt_log' AND column_name='request_fingerprint')=0
      OR (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='play_attempt_log' AND column_name='request_fingerprint'
        AND data_type='char' AND character_maximum_length=64 AND character_set_name='ascii'
        AND collation_name='ascii_bin' AND is_nullable='YES' AND column_default IS NULL)=1)
);
-- runner: assert=scalar-true
SELECT @advanced_fingerprint_precheck AS advanced_fingerprint_precheck;
-- 即便调用方未解释 runner 注释，也必须在不满足前提时以真实 SQL 错误停止。
SET @advanced_fingerprint_guard_sql := IF(@advanced_fingerprint_precheck,
    'SELECT 1', 'SELECT * FROM information_schema.__advanced_fingerprint_precheck_failed__');
PREPARE advanced_fingerprint_guard FROM @advanced_fingerprint_guard_sql;
EXECUTE advanced_fingerprint_guard;
DEALLOCATE PREPARE advanced_fingerprint_guard;

SET @advanced_fingerprint_exists := (
    SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='play_attempt_log' AND column_name='request_fingerprint');
SET @advanced_fingerprint_sql := IF(@advanced_fingerprint_exists=0,
    'ALTER TABLE play_attempt_log ADD COLUMN request_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT ''SHA256 of versioned canonical command; NULL means unbound historic attempt''',
    'SELECT 1');
PREPARE advanced_fingerprint_stmt FROM @advanced_fingerprint_sql;
EXECUTE advanced_fingerprint_stmt;
DEALLOCATE PREPARE advanced_fingerprint_stmt;

-- runner: assert=scalar-true
SELECT (
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='play_attempt_log' AND column_name='request_fingerprint'
        AND data_type='char' AND character_maximum_length=64 AND character_set_name='ascii'
        AND collation_name='ascii_bin' AND is_nullable='YES' AND column_default IS NULL)=1
    AND (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema=DATABASE() AND table_name='play_attempt_log'
        AND index_name='uk_play_attempt_idempotency' AND non_unique=0
        AND seq_in_index=1 AND column_name='idempotency_key' AND sub_part IS NULL)=1
) AS advanced_fingerprint_ready;

-- 回退限制：保留此列、旧 NULL、新指纹回执及原唯一身份。禁止 DROP/清表/猜 hash。
-- 先扩列再部署新代码；有新回执后不回退到不校验指纹的写代码，必要时关闭高级写入口。
