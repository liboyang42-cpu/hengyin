-- ================= migration_20261116_channel_events.sql =================
-- C3 channels-events (2026-11-16): events posted in club channels.
--
-- A channel event is a link row over a real club activity (cms_activity + oms_ticket, created through the
-- existing activity publish path). Nothing here duplicates the activity, its tickets, enrolment or payment.
--   channel_event        : channel -> activity link, the card message id, and a snapshot for the card
--   channel_event_rsvp   : going / maybe / no per member (going is only counted with a confirmed enrolment)
--   channel_event_photo  : album photos; each points at an app media asset that goes through the existing
--                          media review (visible only once the asset is APPROVED)
-- Plus the admin console page 频道活动 (business:channelEvent:list / :moderate), granted to no role.
--
-- ROLLBACK (data in these tables is member content; export before dropping):
--   DELETE FROM sys_menu WHERE perms IN ('business:channelEvent:moderate');
--   DELETE FROM sys_menu WHERE perms IN ('business:channelEvent:list');
--   DROP TABLE IF EXISTS channel_event_photo;
--   DROP TABLE IF EXISTS channel_event_rsvp;
--   DROP TABLE IF EXISTS channel_event;
--   (The activities, tickets and card messages stay: they are ordinary activities and channel messages.)
--
-- Idempotent: CREATE TABLE IF NOT EXISTS, menu rows guarded by existence checks.
-- No money, inventory, settlement or payment tables are touched.

CREATE TABLE IF NOT EXISTS channel_event (
  id               BIGINT        NOT NULL AUTO_INCREMENT,
  club_id          BIGINT        NOT NULL COMMENT 'club that owns the channel (im_channel.guild_id, guild_kind 1)',
  channel_id       BIGINT        NOT NULL COMMENT 'im_channel.id; its conversation has the same id',
  activity_id      BIGINT        NOT NULL COMMENT 'cms_activity.id created by the existing publish path',
  ticket_id        BIGINT        NULL     COMMENT 'oms_ticket.id of the single event ticket (capacity = total_stock)',
  message_id       BIGINT        NULL     COMMENT 'im_message.id of the event card; NULL while the activity waits for review',
  poster_member_id BIGINT        NOT NULL,
  title            VARCHAR(60)   NOT NULL,
  start_at         DATETIME      NOT NULL,
  end_at           DATETIME      NOT NULL,
  place_name       VARCHAR(80)   NULL,
  lat              DECIMAL(10,7) NULL     COMMENT 'GCJ-02',
  lng              DECIMAL(10,7) NULL     COMMENT 'GCJ-02',
  capacity         INT           NOT NULL DEFAULT 0,
  paid             TINYINT(1)    NOT NULL DEFAULT 0,
  status           TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '0 waiting for review, 1 posted, 2 cancelled',
  create_time      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_channel_event_activity (activity_id),
  KEY idx_channel_event_channel (channel_id, start_at),
  KEY idx_channel_event_club (club_id, start_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='C3: event posted in a club channel (link over cms_activity)';

CREATE TABLE IF NOT EXISTS channel_event_rsvp (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  event_id    BIGINT      NOT NULL,
  member_id   BIGINT      NOT NULL,
  status      VARCHAR(8)  NOT NULL COMMENT 'GOING | MAYBE | NO',
  create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_channel_event_rsvp (event_id, member_id),
  KEY idx_channel_event_rsvp_member (member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='C3: RSVP per member; GOING counts only with a confirmed enrolment';

CREATE TABLE IF NOT EXISTS channel_event_photo (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  event_id    BIGINT      NOT NULL,
  member_id   BIGINT      NOT NULL,
  asset_id    BIGINT      NOT NULL COMMENT 'app media asset (existing upload + review); shown only when APPROVED',
  removed     TINYINT(1)  NOT NULL DEFAULT 0,
  removed_by  BIGINT      NULL,
  create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_channel_event_photo_asset (event_id, asset_id),
  KEY idx_channel_event_photo_event (event_id, id),
  KEY idx_channel_event_photo_member (member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='C3: event photo album (CLB-12)';

-- ---------------------------------------------------------------- 频道活动（/clubs-roam/channel-events）
SET @c3_dir_unique := (SELECT COUNT(*) FROM sys_menu WHERE path = 'clubs-roam' AND menu_type = 'M');
SET @c3_dir := (SELECT MIN(menu_id) FROM sys_menu WHERE path = 'clubs-roam' AND menu_type = 'M' AND @c3_dir_unique = 1);
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '频道活动', @c3_dir, 15, 'channel-events', 'business/channelevent/index', 1, 0, 'C', '0', '0', 'business:channelEvent:list', 'date', 'migration', NOW(), 'C3 channels-events'
FROM dual
WHERE @c3_dir IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:channelEvent:list');
SET @c3_page := (SELECT MIN(menu_id) FROM sys_menu WHERE perms = 'business:channelEvent:list' AND menu_type = 'C');
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '移除与恢复活动相册照片', @c3_page, 1, '', '', 1, 0, 'F', '0', '0', 'business:channelEvent:moderate', '#', 'migration', NOW(), 'C3 channels-events'
FROM dual
WHERE @c3_page IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:channelEvent:moderate');

-- runner: assert=scalar-true
SELECT (
  (SELECT COUNT(1) FROM information_schema.tables WHERE table_schema = DATABASE()
      AND table_name IN ('channel_event', 'channel_event_rsvp', 'channel_event_photo')) = 3
  AND (SELECT COUNT(1) FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id = rm.menu_id
        WHERE m.perms IN ('business:channelEvent:list', 'business:channelEvent:moderate')) = 0
) AS channel_events_ready;
