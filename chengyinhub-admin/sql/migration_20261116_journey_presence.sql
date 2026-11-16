-- ================= migration_20261116_journey_presence.sql =================
SELECT 'migration_20261116_journey_presence.sql' AS running;
-- W7 x-rewards-evidence, GAME-2 "progress is free, prizes need presence" (owner decision 2026-11-16).
--
-- player_node_progress.start_verified: 1 = this row passed the 300 m start check (or an on-site scan), so the run
--   counts as started. Rows that existed before this migration are back-filled to 1 so running journeys are untouched;
--   new rows default to 0. (Added with DEFAULT 1, then the default is switched to 0: no full-table UPDATE.)
-- player_node_progress.remote_completion: 1 = the stop was completed away from it; points, coupons, drops and the
--   podium medal were capped to XP and badges, and the journey completion award is capped the same way.
-- sys_config play.arrive.remote_reward_policy (XP_BADGE_ONLY = safe default, FULL = previous behaviour) and
--   play.arrive.reward_radius_m (80). GAME-4 location.trust.jitterMode (ACCURACY), windowMinutes (10) and
--   checkinRequiresFix (false until the fixed mini-program is live). Seeded only when missing; never overwrites.
--
-- ROLLBACK (manual, only after the backend build that reads these columns has been rolled back):
--   ALTER TABLE player_node_progress DROP COLUMN remote_completion;
--   ALTER TABLE player_node_progress DROP COLUMN start_verified;
--   DELETE FROM sys_config WHERE config_key IN ('play.arrive.remote_reward_policy', 'play.arrive.reward_radius_m',
--     'location.trust.jitterMode', 'location.trust.windowMinutes', 'location.trust.checkinRequiresFix');
-- Idempotent: every ALTER is guarded by information_schema; re-running changes nothing.

SET @jp_start_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='player_node_progress' AND COLUMN_NAME='start_verified')=0,
  'ALTER TABLE player_node_progress ADD COLUMN start_verified TINYINT NOT NULL DEFAULT 1 COMMENT ''GAME-2 1=passed the start check (existing rows back-filled to 1)''',
  'SELECT 1');
PREPARE jp_start_stmt FROM @jp_start_ddl;
EXECUTE jp_start_stmt;
DEALLOCATE PREPARE jp_start_stmt;

-- New rows start unverified. Guarded so a re-run on an already-switched column does nothing.
SET @jp_default_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='player_node_progress' AND COLUMN_NAME='start_verified' AND COLUMN_DEFAULT='1')=1,
  'ALTER TABLE player_node_progress ALTER COLUMN start_verified SET DEFAULT 0',
  'SELECT 1');
PREPARE jp_default_stmt FROM @jp_default_ddl;
EXECUTE jp_default_stmt;
DEALLOCATE PREPARE jp_default_stmt;

SET @jp_remote_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='player_node_progress' AND COLUMN_NAME='remote_completion')=0,
  'ALTER TABLE player_node_progress ADD COLUMN remote_completion TINYINT NOT NULL DEFAULT 0 COMMENT ''GAME-2 1=completed away from the stop, prizes capped to XP and badges''',
  'SELECT 1');
PREPARE jp_remote_stmt FROM @jp_remote_ddl;
EXECUTE jp_remote_stmt;
DEALLOCATE PREPARE jp_remote_stmt;

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT * FROM (
  SELECT '旅程-不在现场完成的奖励' AS n, 'play.arrive.remote_reward_policy' AS k, 'XP_BADGE_ONLY' AS v, 'Y' AS t, 'admin' AS b, NOW() AS c,
         'XP_BADGE_ONLY=开局后不在站点附近完成只发探索值与徽章(安全默认);FULL=照旧全额发奖' AS r
  UNION ALL SELECT '旅程-领奖半径(米)', 'play.arrive.reward_radius_m', '80', 'Y', 'admin', NOW(),
         '积分、券、掉落、名次奖牌要求人在站点此半径内(站点自身半径更大时取更大者),30-1000'
  UNION ALL SELECT '定位可信-抖动判定', 'location.trust.jitterMode', 'ACCURACY', 'Y', 'admin', NOW(),
         'GAME-4 ACCURACY=两次精度之和+50米内才算抖动(不超过 minMoveMeters);FLAT=旧规则固定 minMoveMeters'
  UNION ALL SELECT '定位可信-窗口(分钟)', 'location.trust.windowMinutes', '10', 'Y', 'admin', NOW(),
         'GAME-4 这段时间内总位移/用时不得超过交通速度上限'
  UNION ALL SELECT '定位可信-点亮要求真实定位', 'location.trust.checkinRequiresFix', 'false', 'Y', 'admin', NOW(),
         'GAME-4 true=点亮请求缺定位精度或定位时间则只记录不计;新版小程序上线后再打开'
) seed
WHERE NOT EXISTS (SELECT 1 FROM sys_config s WHERE s.config_key = seed.k);

-- runner: assert=scalar-true
SELECT COUNT(*) = 2 FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'player_node_progress'
  AND COLUMN_NAME IN ('start_verified', 'remote_completion');
