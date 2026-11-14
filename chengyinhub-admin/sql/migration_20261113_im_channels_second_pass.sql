-- 频道/好友第二批（native App 合同 docs/channels/contract.md，App 仓 ui/channels-contract）。
-- 只新增表/列与放宽回执族约束；不回填、不改既有行；本地/测试库候选，不在生产执行。
-- 依赖：20261112_im_channels_platform（回执表）、20261105_l9_friend_foundation（好友三表）、20261105_l8_chain_storage。

-- 0) 原请求回执族放宽：好友/元数据/移除/治理/申诉/通知/接龙/打卡/准入/个人状态/隐私/角色/举报/媒体/队伍邀请。
SET @ck_exists = (
    SELECT COUNT(*) FROM information_schema.table_constraints
     WHERE constraint_schema = DATABASE() AND table_name = 'im_channel_request_receipt'
       AND constraint_name = 'ck_channel_receipt_family' AND constraint_type = 'CHECK'
);
SET @ck_ready = (
    SELECT COUNT(*) FROM information_schema.check_constraints
     WHERE constraint_schema = DATABASE() AND constraint_name = 'ck_channel_receipt_family'
       AND check_clause LIKE '%''ONBOARD''%'
);
SET @ddl = IF(
    @ck_exists > 0 AND @ck_ready = 0,
    'ALTER TABLE im_channel_request_receipt DROP CHECK ck_channel_receipt_family',
    'SELECT 1'
);
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;
SET @ck_exists = (
    SELECT COUNT(*) FROM information_schema.table_constraints
     WHERE constraint_schema = DATABASE() AND table_name = 'im_channel_request_receipt'
       AND constraint_name = 'ck_channel_receipt_family' AND constraint_type = 'CHECK'
);
SET @ddl = IF(
    @ck_exists = 0,
    'ALTER TABLE im_channel_request_receipt ADD CONSTRAINT ck_channel_receipt_family CHECK (family IN (''META'',''GOV'',''MSG'',''FRIEND'',''FMETA'',''FREMOVE'',''FINVITE'',''FTEAM'',''MOD'',''APPEAL'',''REPORT'',''NOTIFY'',''CHAIN'',''CHECKIN'',''ADMIT'',''STATE'',''PRIVACY'',''ROLE'',''MEDIA'',''ONBOARD''))',
    'SELECT 1'
);
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

-- 1) 好友元数据与分组的 CAS 版本（#727 三表未带版本列）。
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'member_friend_meta' AND column_name = 'version') = 0,
    'ALTER TABLE member_friend_meta ADD COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT ''备注/分组/星标/进度范围 CAS 版本''',
    'SELECT 1'
);
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'member_friend_group' AND column_name = 'version') = 0,
    'ALTER TABLE member_friend_group ADD COLUMN version BIGINT NOT NULL DEFAULT 1 COMMENT ''分组 CAS 版本''',
    'SELECT 1'
);
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

-- 2) 治理处置：禁言(频道)/踢出/封禁(空间)；状态 ACTIVE/EXPIRED(读时按 expires_at 推导)/REVOKED/APPLIED(踢出)。
CREATE TABLE IF NOT EXISTS im_moderation_action (
    id BIGINT NOT NULL AUTO_INCREMENT,
    guild_kind TINYINT NOT NULL,
    guild_id BIGINT UNSIGNED NOT NULL,
    target_member_id BIGINT NOT NULL,
    action_type VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    scope_type VARCHAR(8) NOT NULL,
    scope_id BIGINT UNSIGNED NOT NULL,
    reason_code VARCHAR(32) NOT NULL,
    reason_text VARCHAR(500) NOT NULL,
    duration_sec INT NOT NULL DEFAULT 0,
    delete_message_sec INT NOT NULL DEFAULT 0,
    starts_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at DATETIME(3) NULL,
    operator_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    revoked_by BIGINT NULL,
    revoke_reason VARCHAR(500) NULL,
    revoked_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    KEY idx_moderation_guild (guild_kind, guild_id, id),
    KEY idx_moderation_target (target_member_id, guild_kind, guild_id, status),
    CONSTRAINT ck_moderation_type CHECK (action_type IN ('TIMEOUT','KICK','BAN')),
    CONSTRAINT ck_moderation_status CHECK (status IN ('ACTIVE','REVOKED','APPLIED')),
    CONSTRAINT ck_moderation_scope CHECK (scope_type IN ('GUILD','CHANNEL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='频道空间治理处置';

-- 3) 处置申诉：每个处置每个申诉人一条；证据为 JSON 数组（最多 5 条）。
CREATE TABLE IF NOT EXISTS im_moderation_appeal (
    id BIGINT NOT NULL AUTO_INCREMENT,
    action_id BIGINT NOT NULL,
    appellant_member_id BIGINT NOT NULL,
    guild_kind TINYINT NOT NULL,
    guild_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    reason_code VARCHAR(32) NOT NULL,
    reason_text VARCHAR(500) NOT NULL,
    evidence_json JSON NULL,
    decision_reason VARCHAR(500) NULL,
    decided_by BIGINT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_appeal_action (action_id, appellant_member_id),
    KEY idx_appeal_guild (guild_kind, guild_id, id),
    CONSTRAINT ck_appeal_status CHECK (status IN ('PENDING','REVIEWING','UPHELD','REVERSED','WITHDRAWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='治理处置申诉';

-- 4) 频道消息举报：一次原请求一行，审核仍走既有 audit_task（reportContent）。
CREATE TABLE IF NOT EXISTS im_channel_report (
    id BIGINT NOT NULL AUTO_INCREMENT,
    reporter_member_id BIGINT NOT NULL,
    target_type VARCHAR(16) NOT NULL,
    target_id BIGINT NOT NULL,
    channel_id BIGINT NOT NULL,
    reason_code VARCHAR(32) NOT NULL,
    reason_text VARCHAR(500) NULL,
    body_digest CHAR(64) NOT NULL,
    audit_task_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    request_id VARCHAR(64) NOT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_channel_report_request (reporter_member_id, request_id),
    KEY idx_channel_report_target (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='频道消息举报';

-- 5) 频道个人状态：本人置顶/隐藏/静音（静音同时写通知偏好永久静音）。
CREATE TABLE IF NOT EXISTS im_channel_member_state (
    channel_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    pinned TINYINT NOT NULL DEFAULT 0,
    hidden TINYINT NOT NULL DEFAULT 0,
    muted TINYINT NOT NULL DEFAULT 0,
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (channel_id, member_id),
    CONSTRAINT ck_member_state_flags CHECK (pinned IN (0,1) AND hidden IN (0,1) AND muted IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='频道本人状态';

-- 6) 角色写：角色版本/颜色/分列/展示层级；授予带范围与到期。
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'im_guild_role' AND column_name = 'version') = 0,
    'ALTER TABLE im_guild_role ADD COLUMN version BIGINT NOT NULL DEFAULT 1 COMMENT ''角色 CAS 版本''',
    'SELECT 1'
);
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'im_guild_role' AND column_name = 'color') = 0,
    'ALTER TABLE im_guild_role ADD COLUMN color INT NULL COMMENT ''0xRRGGBB''',
    'SELECT 1'
);
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'im_guild_role' AND column_name = 'hoist') = 0,
    'ALTER TABLE im_guild_role ADD COLUMN hoist TINYINT NOT NULL DEFAULT 0',
    'SELECT 1'
);
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'im_guild_role' AND column_name = 'display_level') = 0,
    'ALTER TABLE im_guild_role ADD COLUMN display_level VARCHAR(8) NULL',
    'SELECT 1'
);
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'im_guild_role_member' AND column_name = 'scope_type') = 0,
    'ALTER TABLE im_guild_role_member ADD COLUMN scope_type VARCHAR(8) NOT NULL DEFAULT ''GUILD''',
    'SELECT 1'
);
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'im_guild_role_member' AND column_name = 'scope_id') = 0,
    'ALTER TABLE im_guild_role_member ADD COLUMN scope_id BIGINT UNSIGNED NOT NULL DEFAULT 0',
    'SELECT 1'
);
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'im_guild_role_member' AND column_name = 'expires_at') = 0,
    'ALTER TABLE im_guild_role_member ADD COLUMN expires_at DATETIME(3) NULL',
    'SELECT 1'
);
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'im_guild_role_member' AND column_name = 'grant_reason') = 0,
    'ALTER TABLE im_guild_role_member ADD COLUMN grant_reason VARCHAR(120) NULL',
    'SELECT 1'
);
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'im_guild_role_member' AND column_name = 'granted_by') = 0,
    'ALTER TABLE im_guild_role_member ADD COLUMN granted_by BIGINT NULL',
    'SELECT 1'
);
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

-- 7) 频道打卡：每人每频道每个上海自然日一行；连续天数在写入时按前一日计算。
CREATE TABLE IF NOT EXISTS im_channel_checkin (
    id BIGINT NOT NULL AUTO_INCREMENT,
    channel_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    checkin_date DATE NOT NULL,
    source VARCHAR(16) NOT NULL DEFAULT 'CHANNEL',
    streak INT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    checked_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_channel_checkin_day (channel_id, member_id, checkin_date),
    KEY idx_channel_checkin_rank (channel_id, checkin_date),
    CONSTRAINT ck_channel_checkin_source CHECK (source IN ('CHANNEL','EVENT','MANUAL')),
    CONSTRAINT ck_channel_checkin_streak CHECK (streak > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='频道每日打卡';

-- 8) 通知偏好：scope_type 1=频道/会话 2=俱乐部 4=账号默认(scope_id=本人)；level 0全部 1仅提及 2关闭 3继承。
CREATE TABLE IF NOT EXISTS im_notify_pref (
    member_id BIGINT NOT NULL,
    scope_type TINYINT NOT NULL,
    scope_id BIGINT NOT NULL,
    level TINYINT NOT NULL DEFAULT 3,
    permanent_muted TINYINT NOT NULL DEFAULT 0,
    muted_until DATETIME(3) NULL,
    quiet_start CHAR(5) NULL,
    quiet_end CHAR(5) NULL,
    quiet_tz VARCHAR(64) NULL,
    suppress_broadcast TINYINT NOT NULL DEFAULT 0,
    suppress_team_status TINYINT NOT NULL DEFAULT 0,
    show_preview TINYINT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (member_id, scope_type, scope_id),
    CONSTRAINT ck_notify_pref_scope CHECK (scope_type IN (1,2,4) AND scope_id > 0),
    CONSTRAINT ck_notify_pref_level CHECK (level BETWEEN 0 AND 3),
    CONSTRAINT ck_notify_pref_mute CHECK (NOT (permanent_muted = 1 AND muted_until IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='频道/会话通知偏好';

-- 9) 进度隐私：本人（汇总/明细/照片/榜单）与频道（汇总/明细/榜开关）两层；updated_at 微秒做 CAS。
CREATE TABLE IF NOT EXISTS member_progress_privacy (
    member_id BIGINT NOT NULL,
    aggregate_scope VARCHAR(8) NOT NULL DEFAULT 'PUBLIC',
    detail_scope VARCHAR(8) NOT NULL DEFAULT 'SELF',
    photo_scope VARCHAR(8) NOT NULL DEFAULT 'SELF',
    leaderboard_scope VARCHAR(4) NOT NULL DEFAULT 'IN',
    revision BIGINT NOT NULL DEFAULT 1,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (member_id),
    CONSTRAINT ck_member_privacy_scopes CHECK (aggregate_scope IN ('SELF','FRIEND','TEAM','CHANNEL','PUBLIC')
        AND detail_scope IN ('SELF','TEAM','CHANNEL') AND photo_scope IN ('SELF','TEAM','ADMIN') AND leaderboard_scope IN ('IN','OUT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='本人进度隐私';

CREATE TABLE IF NOT EXISTS im_channel_progress_privacy (
    channel_id BIGINT NOT NULL,
    aggregate_scope VARCHAR(8) NOT NULL DEFAULT 'PUBLIC',
    detail_scope VARCHAR(8) NOT NULL DEFAULT 'SELF',
    board_enabled TINYINT NOT NULL DEFAULT 1,
    revision INT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_by BIGINT NULL,
    PRIMARY KEY (channel_id),
    CONSTRAINT ck_channel_privacy_scopes CHECK (aggregate_scope IN ('SELF','FRIEND','TEAM','CHANNEL','PUBLIC')
        AND detail_scope IN ('SELF','TEAM','CHANNEL') AND board_enabled IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='频道进度隐私';

-- 10) 好友邀请：拉好友进频道所在空间（俱乐部/队伍）与拉好友入队。只记邀请与其去向，入会/入队仍走原链路。
CREATE TABLE IF NOT EXISTS friend_channel_invite (
    id BIGINT NOT NULL AUTO_INCREMENT,
    inviter_id BIGINT NOT NULL,
    peer_id BIGINT NOT NULL,
    channel_id BIGINT NOT NULL,
    guild_kind TINYINT NOT NULL,
    guild_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'INVITED',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_friend_channel_invite (inviter_id, request_id, peer_id),
    KEY idx_friend_channel_invite_peer (peer_id, state),
    CONSTRAINT ck_friend_channel_invite_state CHECK (state IN ('INVITED','SUBMITTED','INVITE_ENDED','REQUEST_ENDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='好友频道邀请';

CREATE TABLE IF NOT EXISTS friend_team_offer (
    seq BIGINT NOT NULL AUTO_INCREMENT,
    offer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    team_id BIGINT NOT NULL,
    inviter_id BIGINT NOT NULL,
    recipient_id BIGINT NOT NULL,
    join_mode TINYINT NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'WAITING_CONFIRM',
    request_id VARCHAR(64) NOT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (seq),
    UNIQUE KEY uk_friend_team_offer (offer_id),
    KEY idx_friend_team_offer_inviter (inviter_id, seq),
    KEY idx_friend_team_offer_recipient (recipient_id, seq),
    CONSTRAINT ck_friend_team_offer_state CHECK (state IN ('WAITING_CONFIRM','PENDING_REVIEW','JOINED','DECLINED','REJECTED','WITHDRAWN','REVOKED','EXPIRED','ACCESS_ENDED')),
    CONSTRAINT ck_friend_team_offer_mode CHECK (join_mode IN (1,2))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='好友入队邀请';

-- 11) 空间准入：入会申请原请求台账（真实成员关系仍由俱乐部 club_member / 队伍 play_team_member 原链路写）与暂停入会开关。
CREATE TABLE IF NOT EXISTS im_join_request (
    id BIGINT NOT NULL AUTO_INCREMENT,
    guild_kind TINYINT NOT NULL,
    guild_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    invite_id BIGINT NULL,
    message VARCHAR(60) NULL,
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0待审 1通过/已加入 2拒绝 3撤回',
    reject_reason_code INT NULL,
    note VARCHAR(200) NULL,
    reviewer_id BIGINT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_join_request_guild (guild_kind, guild_id, status),
    KEY idx_join_request_member (member_id, guild_kind, guild_id),
    CONSTRAINT ck_join_request_status CHECK (status BETWEEN 0 AND 3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='空间入会申请';

CREATE TABLE IF NOT EXISTS im_space_admission (
    guild_kind TINYINT NOT NULL,
    guild_id BIGINT NOT NULL,
    join_paused TINYINT NOT NULL DEFAULT 0,
    paused_until DATETIME(3) NULL,
    reason VARCHAR(200) NULL,
    update_by BIGINT NULL,
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (guild_kind, guild_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='空间暂停入会';
