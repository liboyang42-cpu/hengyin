-- R4 guest-walk (GRO-12): one walk made before sign-in, saved once after sign-in (POST /api/roam/guest-walk).
-- Idempotent: CREATE TABLE IF NOT EXISTS; no existing table is altered.
-- Rollback: set CHENGYIN_ROAM_GUEST_WALK_ENABLED=false first (the route then refuses and stores nothing), then
--           DROP TABLE IF EXISTS roam_guest_walk;
--   Walks already saved stay as ordinary finished roam_session rows with their roam_fog_tile / roam_member_fog_cell
--   cells and the growth XP; they are not undone by the rollback.
-- Privacy: no coordinates are stored here. The walk's simplified track goes to roam_session.track_json and one
--   location_trust_fix row (kind GUEST_WALK); both follow the existing 30-day location retention.

CREATE TABLE IF NOT EXISTS roam_guest_walk (
  id              BIGINT        NOT NULL AUTO_INCREMENT,
  install_id      CHAR(36)      NOT NULL COMMENT 'client install id for the guest walk (random UUID, not the statistics id)',
  walk_id         CHAR(36)      NOT NULL COMMENT 'client walk id (UUID)',
  member_id       BIGINT        NOT NULL,
  session_id      BIGINT        NULL     COMMENT 'roam_session created for the walk; NULL when nothing was credited',
  started_at      DATETIME      NOT NULL,
  finished_at     DATETIME      NOT NULL,
  cells_sent      INT           NOT NULL,
  cells_credited  INT           NOT NULL,
  xp_awarded      INT           NOT NULL,
  withheld        TINYINT       NOT NULL DEFAULT 0,
  trust_code      VARCHAR(32)   NULL,
  result_json     VARCHAR(1024) NOT NULL COMMENT 'first response, replayed to a retry',
  create_time     DATETIME      NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_roam_guest_walk_install (install_id),
  UNIQUE KEY uk_roam_guest_walk_member (member_id),
  KEY idx_roam_guest_walk_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='R4 guest walk: one pre-sign-in walk per install and per member';
