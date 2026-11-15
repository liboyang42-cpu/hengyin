-- G3 external-games (2026-11-15): operator takedown, report resolution, operator node binding audit,
-- and the points-governance cap for creator traffic points (event_type 99).
-- 类别：② 新增表 + 种子行（只新增，不改、不删既有行与约束）。可重复执行。
--
-- Rollback (manual, after exporting the audit rows you want to keep):
--   UPDATE external_game g JOIN external_game_takedown t ON t.game_id = g.game_id AND t.status = 'ACTIVE'
--      SET g.approved_submission_id = t.submission_id;          -- re-list games that are taken down
--   DROP TABLE IF EXISTS external_game_takedown;
--   DROP TABLE IF EXISTS external_game_report_resolution;
--   DROP TABLE IF EXISTS external_game_moderation_event;
--   DELETE FROM points_award_cap WHERE event_type = 99 AND update_by = 'migration';
-- The backend treats a missing external_game_takedown table as "no takedown" (reads fail open only for the
-- re-approval guard; takedown writes fail closed), so the deploy order does not matter.

-- One row per game. ACTIVE = delisted by an operator: external_game.approved_submission_id is NULL, so the
-- catalogue, the signed download, node bindings and new play sessions refuse it. LIFTED = restored.
CREATE TABLE IF NOT EXISTS external_game_takedown (
  game_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'ACTIVE/LIFTED',
  submission_id BIGINT NULL COMMENT '下架时的上架版本',
  reason_code VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  reason VARCHAR(500) NOT NULL COMMENT '后台原因（不对玩家展示）',
  taken_down_by BIGINT NOT NULL COMMENT 'sys_user.user_id',
  taken_down_time DATETIME(3) NOT NULL,
  lifted_by BIGINT NULL,
  lifted_time DATETIME(3) NULL,
  lift_reason VARCHAR(500) NULL,
  update_time DATETIME(3) NOT NULL,
  PRIMARY KEY (game_id),
  KEY idx_egt_status (status, taken_down_time),
  CONSTRAINT ck_egt_status CHECK (status IN ('ACTIVE', 'LIFTED')),
  CONSTRAINT ck_egt_reason CHECK (reason_code IN ('INAPPROPRIATE_CONTENT', 'MALICIOUS_CODE', 'PAYMENT_OR_SCAM', 'COPYRIGHT', 'BROKEN', 'OTHER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='外部游戏后台下架';

-- How an operator closed a report. external_game_report.status becomes RESOLVED in the same transaction.
CREATE TABLE IF NOT EXISTS external_game_report_resolution (
  report_id BIGINT NOT NULL COMMENT 'external_game_report.id',
  resolution VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'ACTIONED/DISMISSED',
  note VARCHAR(500) NULL,
  resolver_id BIGINT NOT NULL COMMENT 'sys_user.user_id',
  resolved_time DATETIME(3) NOT NULL,
  PRIMARY KEY (report_id),
  CONSTRAINT ck_egrr_resolution CHECK (resolution IN ('ACTIONED', 'DISMISSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='外部游戏举报处理结果';

-- Append-only audit of operator actions on external games (takedown, restore, report resolution, node binding).
CREATE TABLE IF NOT EXISTS external_game_moderation_event (
  id BIGINT NOT NULL AUTO_INCREMENT,
  game_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  action VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  target_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'GAME/REPORT/NODE',
  target_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  actor_id BIGINT NOT NULL COMMENT 'sys_user.user_id',
  reason VARCHAR(500) NULL,
  detail_json VARCHAR(1000) NULL,
  create_time DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_egme_game (game_id, id),
  KEY idx_egme_action (action, id),
  CONSTRAINT ck_egme_action CHECK (action IN ('TAKEDOWN', 'RESTORE', 'REPORT_ACTIONED', 'REPORT_DISMISSED', 'NODE_BIND', 'NODE_UNBIND')),
  CONSTRAINT ck_egme_target CHECK (target_type IN ('GAME', 'REPORT', 'NODE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='外部游戏后台操作留痕（只追加）';

-- Creator traffic points (SpringExternalGameWalletBridge, event_type 99) join the PLAY programme with the same
-- 500 points/day/member cap as the other play sources. Inserted only when missing; never overwrites ops values.
INSERT INTO points_award_cap (event_type, source_name, programme, daily_cap_points, status, update_by, update_time)
SELECT 99, '外部游戏流量激励', 'PLAY', 500, '0', 'migration', NOW() FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM points_award_cap WHERE event_type = 99);
