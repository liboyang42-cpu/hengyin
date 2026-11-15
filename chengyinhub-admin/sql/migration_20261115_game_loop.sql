-- G1 game-loop (GRO-15): who may appear on the friends and district growth boards.
-- Opt-in: a player appears on those boards only after turning it on (visible = 1). No row = not shown.
-- A player who chose leaderboard_scope = 'OUT' in member_progress_privacy never appears, whatever this says.
-- Idempotent: CREATE TABLE IF NOT EXISTS; no existing table is altered.
-- Rollback (by hand, after a read-only check): DROP TABLE IF EXISTS growth_board_visibility;
--   The friends and district boards then show only the viewer's own row; the global board is unchanged.

CREATE TABLE IF NOT EXISTS growth_board_visibility (
  member_id   BIGINT      NOT NULL,
  visible     TINYINT     NOT NULL DEFAULT 0 COMMENT '1 = shown on friends and district boards',
  update_time DATETIME    NOT NULL,
  PRIMARY KEY (member_id),
  KEY idx_growth_board_visibility_visible (visible)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='G1 growth board opt-in for friends and district scopes';
