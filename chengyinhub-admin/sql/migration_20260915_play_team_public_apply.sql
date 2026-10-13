-- 地图组队 P 方案(2026-09-15 裁决):公开队伍陌生人可申请。
-- 申请复用 play_team_member 行(uk_team_member 保证同人同队唯一;status 0=待审核 4=已拒绝 早已预留),
-- 本迁移只加申请失效时间;应用层新增 5=已撤回 6=已失效。
-- 两段 DDL 先读 information_schema,失败重放不会因对象已存在再次中断。无数据回填。

SET @apply_expire_column_exists = (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'play_team_member'
    AND column_name = 'apply_expire_time'
);
SET @apply_expire_column_sql = IF(
  @apply_expire_column_exists = 0,
  'ALTER TABLE play_team_member ADD COLUMN apply_expire_time DATETIME NULL COMMENT ''公开申请失效时间=min(申请+24h,场次开始)'' AFTER apply_message',
  'SELECT 1'
);
PREPARE apply_expire_column_stmt FROM @apply_expire_column_sql;
EXECUTE apply_expire_column_stmt;
DEALLOCATE PREPARE apply_expire_column_stmt;

SET @apply_expire_index_exists = (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'play_team_member'
    AND index_name = 'idx_member_apply_expire'
);
SET @apply_expire_index_sql = IF(
  @apply_expire_index_exists = 0,
  'ALTER TABLE play_team_member ADD KEY idx_member_apply_expire (status, apply_expire_time)',
  'SELECT 1'
);
PREPARE apply_expire_index_stmt FROM @apply_expire_index_sql;
EXECUTE apply_expire_index_stmt;
DEALLOCATE PREPARE apply_expire_index_stmt;

-- runner: assert=scalar-true
SELECT COUNT(*) = 1 AS migration_assertion
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'play_team_member'
  AND column_name = 'apply_expire_time'
  AND data_type = 'datetime'
  AND is_nullable = 'YES';

-- runner: assert=scalar-true
SELECT COUNT(*) = 1 AS migration_assertion
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'play_team_member'
  AND index_name = 'idx_member_apply_expire'
  AND seq_in_index = 1
  AND column_name = 'status';
