-- P0-17 creator_income_detail 幂等唯一键（2026-11-11 f-money，备将来写入用）
-- 执行方式：走 chengyin-db-migrate，只读预检后确认。新 jar 不读也不写本列，部署前后执行皆可。
-- 背景：表建于 migration_p3_phase_b_player_world_creator.sql，只有主键和普通索引；目前没有任何写入路径
--       （CreatorIncomeDetailWriteGuardTest 锁住）。将来接写入时，每一行必须带一个由业务事件确定性生成的
--       idempotency_key（例如 来源类型 + 来源单号 + 创作者），回调或任务重放时由本唯一键挡住重复入账。
-- 列允许 NULL：存量行（若有人工数据）不回填、不猜键；MySQL 唯一索引允许多个 NULL，所以存量不冲突。
--       新写入路径必须写非空键，由 CreatorIncomeDetailWriteGuardTest 校验插入语句带这一列。
-- 可重复执行：加列、加索引前都先查 information_schema。

SET @cid_idem_col_exists := (
    SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='creator_income_detail' AND column_name='idempotency_key');
SET @cid_idem_col_sql := IF(@cid_idem_col_exists=0,
    'ALTER TABLE creator_income_detail ADD COLUMN idempotency_key VARCHAR(128) NULL COMMENT ''幂等键：业务事件确定性生成，新写入必填'' AFTER source_id',
    'SELECT 1');
PREPARE cid_idem_col_stmt FROM @cid_idem_col_sql;
EXECUTE cid_idem_col_stmt;
DEALLOCATE PREPARE cid_idem_col_stmt;

SET @cid_idem_uk_exists := (
    SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema=DATABASE() AND table_name='creator_income_detail' AND index_name='uk_creator_income_idempotency');
SET @cid_idem_uk_sql := IF(@cid_idem_uk_exists=0,
    'ALTER TABLE creator_income_detail ADD UNIQUE KEY uk_creator_income_idempotency (idempotency_key)',
    'SELECT 1');
PREPARE cid_idem_uk_stmt FROM @cid_idem_uk_sql;
EXECUTE cid_idem_uk_stmt;
DEALLOCATE PREPARE cid_idem_uk_stmt;

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema=DATABASE() AND table_name='creator_income_detail'
        AND index_name='uk_creator_income_idempotency' AND non_unique=0 AND column_name='idempotency_key')=1
       AS creator_income_idempotency_ready;

-- 回退：保留列与唯一键（旧 jar 不读它们）。不要 DROP —— 接入写入后删键会让重放重复入账。
