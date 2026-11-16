-- ================= migration_20261116_external_game_result_key.sql =================
SELECT 'migration_20261116_external_game_result_key.sql' AS running;
-- W7 x-shop-games GAME-9 (2026-11-16): per-game result keys a creator provisions for their own game server.
--   external_game_result_key  one row per game: AES-GCM ciphertext of the key (wrap secret CHENGYIN_EXTERNAL_GAME_KEY_WRAP
--                             from the environment, never stored), an 8-character hint, issue and revoke times.
--                             No personal data: the owner is read from external_game.
--   sys_menu business:externalgame:resultkey  console status + revoke button under 外部游戏变现. Granted to no role.
--
-- ROLLBACK (manual, only after the backend build that reads this table has been rolled back):
--   DELETE FROM sys_menu WHERE perms = 'business:externalgame:resultkey';
--   DROP TABLE IF EXISTS external_game_result_key;
-- Idempotent: CREATE TABLE IF NOT EXISTS and NOT EXISTS-guarded inserts.

CREATE TABLE IF NOT EXISTS external_game_result_key (
  game_id     VARCHAR(64)    NOT NULL COMMENT 'external_game.game_id',
  key_cipher  VARBINARY(256) NOT NULL COMMENT 'IV(12) + AES-GCM ciphertext of the hex key',
  key_hint    CHAR(8)        NOT NULL COMMENT 'first 8 hex of SHA-256(key), for support',
  created_at  DATETIME       NOT NULL,
  revoked_at  DATETIME       NULL,
  revoked_by  VARCHAR(64)    NULL COMMENT 'console operator user name',
  PRIMARY KEY (game_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='W7 GAME-9 external game result keys';

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '成绩密钥管理',(SELECT m.menu_id FROM (SELECT menu_id FROM sys_menu WHERE component='business/externalgame/monetization' LIMIT 1) m),
       9,'#','',1,0,'F','0','0','business:externalgame:resultkey','#','migration',NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:externalgame:resultkey')
  AND EXISTS (SELECT 1 FROM sys_menu WHERE component='business/externalgame/monetization');

-- runner: assert=scalar-true
SELECT COUNT(*) = 1 FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'external_game_result_key';
