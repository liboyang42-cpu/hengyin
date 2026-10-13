-- 俱乐部活动运营：系列场次、候补占位、签到更正、分群通知与 outbox。
-- 阶段：pre-deploy schema；本文件只建表并回读结构，不注册依赖新 jar 的 Quartz 任务。
-- 前置：migration_club_role_assignment_20260823.sql、migration_business_audit_event_20260823.sql。
-- CmsActivity、cms_registration、oms_ticket、club_lead_checkin 继续分别作为场次、报名、库存、原始签到真源。
-- 仅生成迁移文件；上线前必须单独走 chengyin-db-migrate 预检、执行和业务读回。

CREATE TABLE IF NOT EXISTS club_event_series (
  id BIGINT NOT NULL AUTO_INCREMENT,
  club_id BIGINT NOT NULL,
  topic_id BIGINT NOT NULL,
  recurrence_type VARCHAR(24) NOT NULL COMMENT 'ONCE/WEEKLY/CUSTOM_DATES',
  timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
  default_lead_member_id BIGINT NOT NULL,
  default_capacity INT DEFAULT NULL COMMENT '仅单票种系列可覆盖容量',
  waitlist_enabled TINYINT(1) NOT NULL DEFAULT 0,
  offer_minutes INT NOT NULL DEFAULT 30,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  version INT NOT NULL DEFAULT 0,
  created_by_member_id BIGINT NOT NULL,
  request_id VARCHAR(64) CHARACTER SET ascii NOT NULL,
  request_fingerprint CHAR(64) CHARACTER SET ascii NOT NULL,
  create_by VARCHAR(64) DEFAULT NULL,
  create_time DATETIME NOT NULL,
  update_by VARCHAR(64) DEFAULT NULL,
  update_time DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_club_event_series_request (club_id, created_by_member_id, request_id),
  KEY idx_club_event_series_club (club_id, status, id),
  CONSTRAINT chk_club_event_series_recurrence CHECK
    (recurrence_type IN ('ONCE','WEEKLY','CUSTOM_DATES')),
  CONSTRAINT chk_club_event_series_status CHECK (status IN ('ACTIVE','CLOSED')),
  CONSTRAINT chk_club_event_series_offer CHECK (offer_minutes BETWEEN 5 AND 120),
  CONSTRAINT chk_club_event_series_capacity CHECK
    (default_capacity IS NULL OR default_capacity BETWEEN 1 AND 10000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='俱乐部活动系列配置';

CREATE TABLE IF NOT EXISTS club_event_occurrence (
  id BIGINT NOT NULL AUTO_INCREMENT,
  series_id BIGINT NOT NULL,
  activity_id BIGINT NOT NULL,
  occurrence_at DATETIME NOT NULL,
  default_lead_member_id BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  version INT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL,
  active_date_key VARCHAR(128) CHARACTER SET ascii GENERATED ALWAYS AS
    (CASE WHEN status = 'ACTIVE'
      THEN CONCAT(CAST(series_id AS CHAR), ':', CAST(occurrence_at AS CHAR)) ELSE NULL END) STORED,
  PRIMARY KEY (id),
  UNIQUE KEY uk_club_event_occurrence_activity (activity_id),
  UNIQUE KEY uk_club_event_occurrence_active_date (active_date_key),
  KEY idx_club_event_occurrence_future (series_id, status, occurrence_at),
  CONSTRAINT chk_club_event_occurrence_status CHECK (status IN ('ACTIVE','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系列到每期CmsActivity的真源关联';

CREATE TABLE IF NOT EXISTS club_event_waitlist (
  id BIGINT NOT NULL AUTO_INCREMENT,
  club_id BIGINT NOT NULL,
  activity_id BIGINT NOT NULL,
  ticket_id BIGINT NOT NULL,
  member_id BIGINT NOT NULL,
  state VARCHAR(16) NOT NULL,
  joined_at DATETIME NOT NULL,
  offered_at DATETIME DEFAULT NULL,
  offer_expires_at DATETIME DEFAULT NULL,
  offer_token_hash CHAR(64) CHARACTER SET ascii DEFAULT NULL COMMENT '仅保存SHA-256',
  registration_id BIGINT DEFAULT NULL,
  version INT NOT NULL DEFAULT 0,
  active_member_key VARCHAR(160) CHARACTER SET ascii GENERATED ALWAYS AS
    (CASE WHEN state IN ('WAITING','OFFERED','CLAIMED')
      THEN CONCAT(activity_id, ':', ticket_id, ':', member_id) ELSE NULL END) STORED,
  PRIMARY KEY (id),
  UNIQUE KEY uk_club_event_waitlist_active (active_member_key),
  UNIQUE KEY uk_club_event_waitlist_registration (registration_id),
  KEY idx_club_event_waitlist_fifo (activity_id, ticket_id, state, joined_at, id),
  KEY idx_club_event_waitlist_expiry (state, offer_expires_at, id),
  CONSTRAINT chk_club_event_waitlist_state CHECK
    (state IN ('WAITING','OFFERED','CLAIMED','CONVERTED','CANCELLED','EXPIRED')),
  CONSTRAINT chk_club_event_waitlist_offer CHECK
    ((state IN ('OFFERED','CLAIMED','CONVERTED') AND offered_at IS NOT NULL
      AND offer_expires_at IS NOT NULL AND offer_token_hash IS NOT NULL)
      OR state IN ('WAITING','CANCELLED','EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='按活动票种FIFO候补队列';

CREATE TABLE IF NOT EXISTS club_event_attendance_correction (
  id BIGINT NOT NULL AUTO_INCREMENT,
  club_id BIGINT NOT NULL,
  activity_id BIGINT NOT NULL,
  member_id BIGINT NOT NULL,
  original_arrived TINYINT(1) NOT NULL,
  new_arrived TINYINT(1) NOT NULL,
  original_arrived_at DATETIME DEFAULT NULL,
  new_arrived_at DATETIME DEFAULT NULL,
  reason VARCHAR(500) NOT NULL,
  actor_member_id BIGINT NOT NULL,
  request_id VARCHAR(64) CHARACTER SET ascii NOT NULL,
  version INT NOT NULL,
  corrected_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_club_event_attendance_version (activity_id, member_id, version),
  UNIQUE KEY uk_club_event_attendance_request (actor_member_id, request_id),
  KEY idx_club_event_attendance_target (club_id, activity_id, member_id, corrected_at),
  CONSTRAINT chk_club_event_attendance_change CHECK (original_arrived <> new_arrived)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='append-only活动签到更正';

CREATE TABLE IF NOT EXISTS club_event_notification_campaign (
  id BIGINT NOT NULL AUTO_INCREMENT,
  club_id BIGINT NOT NULL,
  activity_id BIGINT DEFAULT NULL,
  audience_type VARCHAR(24) NOT NULL,
  channel VARCHAR(16) NOT NULL,
  title VARCHAR(80) NOT NULL,
  content VARCHAR(1000) NOT NULL,
  status VARCHAR(24) NOT NULL,
  actor_member_id BIGINT NOT NULL,
  request_id VARCHAR(64) CHARACTER SET ascii NOT NULL,
  total_count INT NOT NULL DEFAULT 0,
  success_count INT NOT NULL DEFAULT 0,
  failed_count INT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL,
  update_time DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_club_event_campaign_request (club_id, actor_member_id, request_id),
  KEY idx_club_event_campaign_scope (club_id, activity_id, create_time),
  CONSTRAINT chk_club_event_campaign_audience CHECK
    (audience_type IN ('ALL_MEMBERS','ADMINS','REGISTERED','WAITLIST','NO_SHOW','INACTIVE')),
  CONSTRAINT chk_club_event_campaign_channel CHECK (channel = 'IN_APP'),
  CONSTRAINT chk_club_event_campaign_status CHECK
    (status IN ('DRAFT','PROCESSING','COMPLETED','PARTIAL_FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='俱乐部分群通知任务';

CREATE TABLE IF NOT EXISTS club_event_notification_delivery (
  id BIGINT NOT NULL AUTO_INCREMENT,
  campaign_id BIGINT NOT NULL,
  recipient_member_id BIGINT NOT NULL,
  channel VARCHAR(16) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  attempt_count INT NOT NULL DEFAULT 0,
  message_id BIGINT DEFAULT NULL COMMENT 'IImService站内消息回执',
  last_error VARCHAR(500) DEFAULT NULL,
  sent_at DATETIME DEFAULT NULL,
  version INT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL,
  update_time DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_club_event_delivery_recipient (campaign_id, recipient_member_id, channel),
  KEY idx_club_event_delivery_retry (campaign_id, status, attempt_count, id),
  CONSTRAINT chk_club_event_delivery_channel CHECK (channel = 'IN_APP'),
  CONSTRAINT chk_club_event_delivery_status CHECK
    (status IN ('PENDING','SENT','FAILED','SKIPPED_INELIGIBLE')),
  CONSTRAINT chk_club_event_delivery_attempt CHECK (attempt_count BETWEEN 0 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每位收件人的站内投递回执';

CREATE TABLE IF NOT EXISTS club_event_notification_outbox (
  id BIGINT NOT NULL AUTO_INCREMENT,
  delivery_id BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  next_attempt_at DATETIME NOT NULL,
  create_time DATETIME NOT NULL,
  update_time DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_club_event_outbox_delivery (delivery_id),
  KEY idx_club_event_outbox_due (status, next_attempt_at, id),
  CONSTRAINT chk_club_event_outbox_status CHECK (status IN ('PENDING','COMPLETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='俱乐部通知可靠投递outbox';

-- 上线结构读回：七表、关键 CAS/作用域列、全部业务唯一键与 CHECK 必须来自目标库。
-- runner: assert=scalar-true
SELECT
  (SELECT COUNT(*) FROM information_schema.tables
   WHERE table_schema = DATABASE() AND engine = 'InnoDB'
     AND table_name IN ('club_event_series','club_event_occurrence','club_event_waitlist',
       'club_event_attendance_correction','club_event_notification_campaign',
       'club_event_notification_delivery','club_event_notification_outbox')) = 7
  AND (SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND
        ((table_name = 'club_event_series' AND column_name IN
          ('club_id','topic_id','recurrence_type','timezone','default_capacity','offer_minutes','request_id','version','status'))
         OR (table_name = 'club_event_occurrence' AND column_name IN
          ('series_id','activity_id','occurrence_at','status','version','active_date_key'))
         OR (table_name = 'club_event_waitlist' AND column_name IN
          ('club_id','activity_id','ticket_id','member_id','state','offer_token_hash','version','active_member_key'))
         OR (table_name = 'club_event_attendance_correction' AND column_name IN
          ('club_id','activity_id','member_id','original_arrived','new_arrived','request_id','version'))
         OR (table_name = 'club_event_notification_campaign' AND column_name IN
          ('club_id','activity_id','audience_type','channel','status','request_id'))
         OR (table_name = 'club_event_notification_delivery' AND column_name IN
          ('campaign_id','recipient_member_id','channel','status','attempt_count','version'))
         OR (table_name = 'club_event_notification_outbox' AND column_name IN
          ('delivery_id','status','next_attempt_at')))) = 45
  AND (SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE()
         AND ((table_name = 'club_event_occurrence' AND column_name = 'active_date_key')
           OR (table_name = 'club_event_waitlist' AND column_name = 'active_member_key'))
         AND extra LIKE '%STORED GENERATED%' AND generation_expression <> '') = 2
  AND (SELECT COUNT(*) FROM (
        SELECT table_name, index_name, MIN(non_unique) AS non_unique,
               GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') AS cols
        FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name LIKE 'club_event_%'
        GROUP BY table_name, index_name
      ) exact_unique
      WHERE non_unique = 0 AND
       ((table_name = 'club_event_series' AND index_name = 'uk_club_event_series_request' AND cols = 'club_id,created_by_member_id,request_id')
        OR (table_name = 'club_event_occurrence' AND index_name = 'uk_club_event_occurrence_activity' AND cols = 'activity_id')
        OR (table_name = 'club_event_occurrence' AND index_name = 'uk_club_event_occurrence_active_date' AND cols = 'active_date_key')
        OR (table_name = 'club_event_waitlist' AND index_name = 'uk_club_event_waitlist_active' AND cols = 'active_member_key')
        OR (table_name = 'club_event_waitlist' AND index_name = 'uk_club_event_waitlist_registration' AND cols = 'registration_id')
        OR (table_name = 'club_event_attendance_correction' AND index_name = 'uk_club_event_attendance_version' AND cols = 'activity_id,member_id,version')
        OR (table_name = 'club_event_attendance_correction' AND index_name = 'uk_club_event_attendance_request' AND cols = 'actor_member_id,request_id')
        OR (table_name = 'club_event_notification_campaign' AND index_name = 'uk_club_event_campaign_request' AND cols = 'club_id,actor_member_id,request_id')
        OR (table_name = 'club_event_notification_delivery' AND index_name = 'uk_club_event_delivery_recipient' AND cols = 'campaign_id,recipient_member_id,channel')
        OR (table_name = 'club_event_notification_outbox' AND index_name = 'uk_club_event_outbox_delivery' AND cols = 'delivery_id'))) = 10
  AND (SELECT COUNT(*) FROM information_schema.table_constraints
       WHERE table_schema = DATABASE() AND constraint_type = 'CHECK'
         AND table_name IN ('club_event_series','club_event_occurrence','club_event_waitlist',
           'club_event_attendance_correction','club_event_notification_campaign',
           'club_event_notification_delivery','club_event_notification_outbox')) = 15
  AS assertion_ok;

-- 业务不变量仍需上线后只读核对：confirmed registrations + active OFFERED holds
-- 不得超过 oms_ticket.total_inventory；本任务没有连接或修改生产库。

SET SESSION group_concat_max_len = 65535;
-- runner: assert=scalar-true
SELECT COUNT(*) = 7
  AND SUM(CASE
    WHEN table_name = 'club_event_series' AND column_count = 18
      AND column_hash = 'd5976a29688ce06d82f32518ddcbe0b568238477f7980fd4f6f331391d670f00' THEN 1
    WHEN table_name = 'club_event_occurrence' AND column_count = 9
      AND column_hash = '8cbec1815825c711d9d6d745104d4b3f330739d374831c11b45bb98ae0c02fab' THEN 1
    WHEN table_name = 'club_event_waitlist' AND column_count = 13
      AND column_hash = '6d30563c23642f6b0ee43a6e77684e6e99be7e4726abb7bc7f2412a2ba6fc2d3' THEN 1
    WHEN table_name = 'club_event_attendance_correction' AND column_count = 13
      AND column_hash = 'de8608fb34f50d11b12247dbc6aaee5386f2381e0bbf2a3154e2524853973185' THEN 1
    WHEN table_name = 'club_event_notification_campaign' AND column_count = 15
      AND column_hash = 'b7bc11e0f1a8301a758da34ec5f777b536565d249b8b68634d3bea9be7c76316' THEN 1
    WHEN table_name = 'club_event_notification_delivery' AND column_count = 12
      AND column_hash = 'fbf79ae33f6fa32e56bc0f31ea6def90ee92633c565f48278656bce2520f0f98' THEN 1
    WHEN table_name = 'club_event_notification_outbox' AND column_count = 6
      AND column_hash = 'e2b2a8b6d56022ba2d23ba7188f810a2580f1d9b1c0f235fd523566ac217bee6' THEN 1
    ELSE 0 END) = 7 AS assertion_ok
FROM (
  SELECT table_name, COUNT(*) AS column_count,
         SHA2(GROUP_CONCAT(CONCAT_WS('~', column_name, LOWER(column_type), is_nullable,
           COALESCE(LOWER(CAST(column_default AS CHAR)), '<null>'), LOWER(extra),
           COALESCE(character_set_name, '<null>'),
           CASE WHEN character_set_name IS NULL OR character_set_name = 'utf8mb4'
             THEN COALESCE(character_set_name, '<null>') ELSE COALESCE(collation_name, '<null>') END,
           COALESCE(REGEXP_REPLACE(LOWER(generation_expression),
             '[[:space:]`]|_utf8mb[34]|_utf8|_latin1|_gbk', ''), '<null>'))
           ORDER BY ordinal_position SEPARATOR '|'), 256) AS column_hash
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name IN ('club_event_series','club_event_occurrence',
    'club_event_waitlist','club_event_attendance_correction','club_event_notification_campaign',
    'club_event_notification_delivery','club_event_notification_outbox')
  GROUP BY table_name
) exact_columns;

-- runner: assert=scalar-true
SELECT COUNT(*) = 7
  AND SUM(CASE
    WHEN table_name = 'club_event_series' AND check_count = 4
      AND check_hash = 'f279bdcc72a395aed10ef315104d1e923d6f8040853f237b1777dce399a87b70' THEN 1
    WHEN table_name = 'club_event_occurrence' AND check_count = 1
      AND check_hash = '3d7cf2bb8b671a8bcc94fb87672e62847eb5181554132509778f03b8a8f5ced6' THEN 1
    WHEN table_name = 'club_event_waitlist' AND check_count = 2
      AND check_hash = '6d901bd5b6dc071b8dc60038cf94af499a2da3eb0c6061c0cabc2326dc335ba2' THEN 1
    WHEN table_name = 'club_event_attendance_correction' AND check_count = 1
      AND check_hash = '3b29e6d8bad2a4da568e2200b9b8df61fe9346f7a9ea8ec013a8596de99fe979' THEN 1
    WHEN table_name = 'club_event_notification_campaign' AND check_count = 3
      AND check_hash = '083eca97b48d5a21b1e7363ccb50328db8b76aadbadbbfdbcfeaa9d03ef6db39' THEN 1
    WHEN table_name = 'club_event_notification_delivery' AND check_count = 3
      AND check_hash = '804d4baa93572ac7b0dbc88f6fc8a5741ff15bc56a380b0500df762384bec876' THEN 1
    WHEN table_name = 'club_event_notification_outbox' AND check_count = 1
      AND check_hash = 'b6fa7fd93485aceee6fc946defeb6d3a04c489119d8b289afa9d9c603c11b63b' THEN 1
    ELSE 0 END) = 7 AS assertion_ok
FROM (
  SELECT tc.table_name, COUNT(*) AS check_count,
         SHA2(GROUP_CONCAT(CONCAT_WS('~', cc.constraint_name, tc.enforced,
           SHA2(REGEXP_REPLACE(LOWER(cc.check_clause),
             '[[:space:]`]|_utf8mb[34]|_utf8|_latin1|_gbk', ''), 256))
           ORDER BY cc.constraint_name SEPARATOR '|'), 256) AS check_hash
  FROM information_schema.table_constraints tc
  JOIN information_schema.check_constraints cc
    ON cc.constraint_schema = tc.constraint_schema AND cc.constraint_name = tc.constraint_name
  WHERE tc.constraint_schema = DATABASE() AND tc.constraint_type = 'CHECK'
    AND tc.table_name IN ('club_event_series','club_event_occurrence','club_event_waitlist',
      'club_event_attendance_correction','club_event_notification_campaign',
      'club_event_notification_delivery','club_event_notification_outbox')
  GROUP BY tc.table_name
) exact_checks;
