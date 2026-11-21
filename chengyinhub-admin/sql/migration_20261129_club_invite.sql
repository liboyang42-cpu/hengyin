-- ================= migration_20261129_club_invite.sql =================
-- W7 x-channel-events (SOC-15): club invite links with an expiry, an optional use limit, revoke and an optional
-- target channel. The link keeps the mini-program path: /pages/club/detail/index?id=<club>&invite=<code>.
--
-- ROLLBACK: the old jar never reads the table. To undo: DROP TABLE IF EXISTS club_invite;
--   (Only after the new jar is gone; it reads the table on every invite route.)
--
-- An invite grants nothing beyond the club's own join policy: accepting runs the ordinary /api/club/join path
-- (public clubs join at once, others send a request to the owner). It counts uses and points at a channel.
-- Personal data: creator_member_id. Account erasure revokes the member's invites and unlinks the creator
-- (MemberPrivacyErasureService). Idempotent: CREATE TABLE IF NOT EXISTS.

CREATE TABLE IF NOT EXISTS club_invite (
    id BIGINT NOT NULL AUTO_INCREMENT,
    club_id BIGINT NOT NULL,
    channel_id BIGINT UNSIGNED NULL COMMENT '加入后打开的频道；NULL=俱乐部主页',
    code CHAR(10) NOT NULL,
    creator_member_id BIGINT NOT NULL,
    max_uses INT NULL COMMENT 'NULL=不限次数',
    uses INT NOT NULL DEFAULT 0,
    expires_at DATETIME NULL COMMENT 'NULL=永久有效',
    revoked_at DATETIME NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_club_invite_code (code),
    KEY idx_club_invite_club (club_id, create_time),
    KEY idx_club_invite_creator (creator_member_id),
    CONSTRAINT ck_club_invite_uses CHECK (uses >= 0 AND (max_uses IS NULL OR (max_uses > 0 AND uses <= max_uses)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='俱乐部邀请链接';

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.tables
      WHERE table_schema=DATABASE() AND table_name='club_invite')=1 AS club_invite_ready;
