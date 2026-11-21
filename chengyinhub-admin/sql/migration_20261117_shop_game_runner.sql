-- ================= migration_20261117_shop_game_runner.sql =================
SELECT 'migration_20261117_shop_game_runner.sql' AS running;
-- W7 x-shop-game-runner (2026-11-17): a shop play's own runtime session, played outside any journey.
--   cy_shop_game_session         one session per issued play (uk play_id), owned by the play's member, on the play's
--                                template. Journey sessions stay in game_runtime_session, keyed by activity, topic and
--                                node; the two never share rows, so neither lookup can see the other.
--   cy_shop_game_session_action  one stored answer per (session, idempotency key) so a resent action replays.
-- No location, money or contact data is stored here (the finish fix is scored and dropped), so the tables do not join
-- the member privacy erasure. shopgames.play.enabled stays false; nothing changes until an operator turns it on.
-- Idempotent: each table is created only when it is missing.
--
-- ROLLBACK (manual, after the backend build that reads these tables has been rolled back):
--   DROP TABLE IF EXISTS cy_shop_game_session_action;
--   DROP TABLE IF EXISTS cy_shop_game_session;
-- Without the tables, starting a shop session fails and /api/shop-games/result judges as before (journey sessions).

CREATE TABLE IF NOT EXISTS cy_shop_game_session (
  id                    BIGINT       NOT NULL AUTO_INCREMENT,
  play_id               BIGINT       NOT NULL COMMENT 'cy_shop_game_play.id; one session per play',
  member_id             BIGINT       NOT NULL COMMENT 'owner, same as the play',
  template_id           BIGINT       NOT NULL COMMENT 'the play''s template',
  config_snapshot_json  JSON         NOT NULL COMMENT 'normalized advanced config frozen at start',
  state_json            JSON         NOT NULL COMMENT 'section engine state',
  status                TINYINT      NOT NULL DEFAULT 0 COMMENT '0 running 1 won 2 lost 3 expired',
  score                 INT          NOT NULL DEFAULT 0,
  version               INT          NOT NULL DEFAULT 0 COMMENT 'CAS version',
  started_at            DATETIME(3)  NOT NULL,
  deadline_at           DATETIME(3)  NULL COMMENT 'never after the play expires',
  completed_at          DATETIME(3)  NULL,
  create_time           DATETIME(3)  NOT NULL,
  update_time           DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_shop_game_session_play (play_id),
  KEY idx_shop_game_session_member (member_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='W7 shop game runtime session (template only, no journey)';

CREATE TABLE IF NOT EXISTS cy_shop_game_session_action (
  session_id       BIGINT       NOT NULL COMMENT 'cy_shop_game_session.id',
  idempotency_key  VARCHAR(64)  NOT NULL,
  fingerprint      CHAR(64)     NOT NULL COMMENT 'sha-256 of version, action and payload',
  response_json    MEDIUMTEXT   NOT NULL COMMENT 'the answer replayed for the same key',
  create_time      DATETIME(3)  NOT NULL,
  PRIMARY KEY (session_id, idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='W7 shop game session action replay';
