-- NPC 声音写入版本：清除/换任务后旧结果不能落库，同录音并发只允许一次领取任务。
-- 生产执行需单独确认，先备份、核对表结构与 MySQL INSTANT 支持，再部署配套后端。
SET @npc_voice_version_ddl = (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE npc_profile ADD COLUMN voice_version BIGINT NOT NULL DEFAULT 0 COMMENT ''声音写入版本'' , ALGORITHM=INSTANT',
    'SELECT ''npc_profile.voice_version already exists'' AS skipped')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'npc_profile' AND COLUMN_NAME = 'voice_version'
);
PREPARE npc_voice_version_stmt FROM @npc_voice_version_ddl;
EXECUTE npc_voice_version_stmt;
DEALLOCATE PREPARE npc_voice_version_stmt;

-- readback: voice_version 必须为 BIGINT、NOT NULL、DEFAULT 0；历史音色和状态不得改变。
-- runner: assert=scalar-true
SELECT COUNT(*) = 1
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'npc_profile' AND COLUMN_NAME = 'voice_version'
  AND DATA_TYPE = 'bigint' AND IS_NULLABLE = 'NO' AND COLUMN_DEFAULT = '0';
