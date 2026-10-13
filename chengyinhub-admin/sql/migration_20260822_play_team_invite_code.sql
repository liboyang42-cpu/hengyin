-- 邀请制队伍不能使用可枚举自增 ID 作为邀请凭证。
-- 三段 DDL 均先读 information_schema，保证失败重放不会因对象已存在再次中断。

SET @invite_column_exists = (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'play_team'
    AND column_name = 'invite_code'
);
SET @invite_column_sql = IF(
  @invite_column_exists = 0,
  'ALTER TABLE play_team ADD COLUMN invite_code VARCHAR(32) NULL COMMENT ''不可枚举的入队邀请码'' AFTER leader_member_id',
  'SELECT 1'
);
PREPARE invite_column_stmt FROM @invite_column_sql;
EXECUTE invite_column_stmt;
DEALLOCATE PREPARE invite_column_stmt;

SET @invite_index_exists = (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'play_team'
    AND index_name = 'uk_play_team_invite_code'
);
SET @invite_index_sql = IF(
  @invite_index_exists = 0,
  'ALTER TABLE play_team ADD UNIQUE KEY uk_play_team_invite_code (invite_code)',
  'SELECT 1'
);
PREPARE invite_index_stmt FROM @invite_index_sql;
EXECUTE invite_index_stmt;
DEALLOCATE PREPARE invite_index_stmt;

UPDATE play_team
SET invite_code = LOWER(HEX(RANDOM_BYTES(16)))
WHERE invite_code IS NULL OR invite_code = '';

SET @invite_column_nullable = (
  SELECT is_nullable
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'play_team'
    AND column_name = 'invite_code'
  LIMIT 1
);
SET @invite_not_null_sql = IF(
  @invite_column_nullable = 'YES',
  'ALTER TABLE play_team MODIFY COLUMN invite_code VARCHAR(32) NOT NULL COMMENT ''不可枚举的入队邀请码''',
  'SELECT 1'
);
PREPARE invite_not_null_stmt FROM @invite_not_null_sql;
EXECUTE invite_not_null_stmt;
DEALLOCATE PREPARE invite_not_null_stmt;

-- runner: assert=scalar-true
SELECT COUNT(*) = 0 AS migration_assertion
FROM play_team
WHERE invite_code IS NULL OR invite_code = '';

-- runner: assert=scalar-true
SELECT COUNT(*) = 1 AS migration_assertion
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'play_team'
  AND column_name = 'invite_code'
  AND is_nullable = 'NO'
  AND character_maximum_length = 32;

-- runner: assert=scalar-true
SELECT COUNT(*) = 1 AS migration_assertion
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'play_team'
  AND index_name = 'uk_play_team_invite_code'
  AND non_unique = 0;
