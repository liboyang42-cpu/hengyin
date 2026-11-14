-- 频道平台持久层（native App 频道合同：docs/channels/contract.md，App 仓 ui/channels-contract）。
-- 只新增表/列；不回填历史会话、不改既有消息正文；本地/测试库候选，不在生产执行。
-- 频道 = im_channel(#726) + 同 id 的 im_conversation(type=5)；消息仍存 im_message。

-- 1) 消息版本：编辑/撤回走 CAS；旧行默认 1，不改写历史内容。
-- MDL-safe online DDL guard: lock_wait_timeout=5s fails fast if a long tx holds MDL on im_message;
-- ALGORITHM=INPLACE, LOCK=NONE allows concurrent reads/writes without table copy.
-- Documented pt-osc fallback (if production MySQL rejects INPLACE/LOCK=NONE or requires throttled execution):
--   pt-online-schema-change --alter "ADD COLUMN version INT NOT NULL DEFAULT 1 COMMENT '消息版本(编辑/撤回 CAS)', ADD COLUMN edited_at DATETIME(3) NULL COMMENT '最近一次编辑时间'" --max-load Threads_running=25 --critical-load Threads_running=50 --chunk-time=0.5 --execute D=<db>,t=im_message
SET @prev_lock_wait_timeout = @@SESSION.lock_wait_timeout;
SET SESSION lock_wait_timeout = 5;
SET @im_msg_has_version = (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'im_message' AND column_name = 'version'
);
SET @im_msg_has_edited_at = (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'im_message' AND column_name = 'edited_at'
);
SET @ddl = IF(
    @im_msg_has_version = 0 AND @im_msg_has_edited_at = 0,
    'ALTER TABLE im_message ADD COLUMN version INT NOT NULL DEFAULT 1 COMMENT ''消息版本(编辑/撤回 CAS)'', ADD COLUMN edited_at DATETIME(3) NULL COMMENT ''最近一次编辑时间'', ALGORITHM=INPLACE, LOCK=NONE',
    'SELECT 1'
);
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'im_message' AND column_name = 'version') = 0,
    'ALTER TABLE im_message ADD COLUMN version INT NOT NULL DEFAULT 1 COMMENT ''消息版本(编辑/撤回 CAS)'', ALGORITHM=INPLACE, LOCK=NONE',
    'SELECT 1'
);
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'im_message' AND column_name = 'edited_at') = 0,
    'ALTER TABLE im_message ADD COLUMN edited_at DATETIME(3) NULL COMMENT ''最近一次编辑时间'', ALGORITHM=INPLACE, LOCK=NONE',
    'SELECT 1'
);
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;
SET SESSION lock_wait_timeout = @prev_lock_wait_timeout;

-- 1b) 频道元数据版本（App 的 version；与权限纪元 perm_version 分开 CAS）。
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'im_channel' AND column_name = 'meta_version') = 0,
    'ALTER TABLE im_channel ADD COLUMN meta_version BIGINT NOT NULL DEFAULT 0 COMMENT ''元数据版本(名称/主题/可见性/慢速/生命周期/覆盖)''',
    'SELECT 1'
);
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

-- 2) 频道操作日志动作扩到编辑与权限覆盖。
SET @ck_exists = (
    SELECT COUNT(*) FROM information_schema.table_constraints
     WHERE constraint_schema = DATABASE() AND table_name = 'im_channel_op_log'
       AND constraint_name = 'ck_channel_op_action' AND constraint_type = 'CHECK'
);
SET @ck_ready = (
    SELECT COUNT(*) FROM information_schema.check_constraints
     WHERE constraint_schema = DATABASE() AND constraint_name = 'ck_channel_op_action'
       AND check_clause LIKE '%''PROGRESS_BIND''%'
);
SET @ddl = IF(
    @ck_exists > 0 AND @ck_ready = 0,
    'ALTER TABLE im_channel_op_log DROP CHECK ck_channel_op_action',
    'SELECT 1'
);
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;
SET @ck_exists = (
    SELECT COUNT(*) FROM information_schema.table_constraints
     WHERE constraint_schema = DATABASE() AND table_name = 'im_channel_op_log'
       AND constraint_name = 'ck_channel_op_action' AND constraint_type = 'CHECK'
);
SET @ddl = IF(
    @ck_exists = 0,
    'ALTER TABLE im_channel_op_log ADD CONSTRAINT ck_channel_op_action CHECK (action IN (''CREATE'',''ARCHIVE'',''UNARCHIVE'',''BAN'',''UNBAN'',''DELETE'',''RESTORE'',''MOVE'',''TRANSFER_BUMP'',''EDIT'',''PERMISSION_PUT'',''PERMISSION_DELETE'',''PROGRESS_BIND''))',
    'SELECT 1'
);
PREPARE ddl_stmt FROM @ddl;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

-- 3) 原请求回执：同一操作者 + 族 + request_id 只落一次，重放返回原响应。
CREATE TABLE IF NOT EXISTS im_channel_request_receipt (
    operator_id BIGINT NOT NULL,
    family VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'META/GOV/MSG',
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation VARCHAR(32) NOT NULL,
    channel_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    response_json LONGTEXT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (operator_id, family, request_id),
    CONSTRAINT ck_channel_receipt_family CHECK (family IN ('META','GOV','MSG')),
    CONSTRAINT ck_channel_receipt_json CHECK (response_json IS NULL OR JSON_VALID(response_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='频道写请求原回执';

-- 4) 空间角色（频道权限覆盖的 role 目标）。俱乐部主理人/管理员仍以 club / club_role_assignment 为真源。
CREATE TABLE IF NOT EXISTS im_guild_role (
    id BIGINT NOT NULL AUTO_INCREMENT,
    guild_kind TINYINT NOT NULL,
    guild_id BIGINT UNSIGNED NOT NULL,
    name VARCHAR(64) NOT NULL,
    position INT NOT NULL DEFAULT 0,
    permissions BIGINT UNSIGNED NOT NULL DEFAULT 0,
    mentionable TINYINT NOT NULL DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
    is_system TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_guild_role (guild_kind, guild_id, status, position),
    CONSTRAINT ck_guild_role_scope CHECK (guild_kind BETWEEN 1 AND 7 AND guild_id > 0),
    CONSTRAINT ck_guild_role_flags CHECK (mentionable IN (0,1) AND status IN (0,1) AND is_system IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='频道空间角色';

CREATE TABLE IF NOT EXISTS im_guild_role_member (
    role_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (role_id, member_id),
    KEY idx_guild_role_member (member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='频道空间角色成员';

-- 5) 频道权限覆盖：target_type 1=@everyone(target_id=0) 2=角色。位图同 App 能力表，空间级位不得进 allow。
CREATE TABLE IF NOT EXISTS im_channel_permission_overwrite (
    channel_id BIGINT UNSIGNED NOT NULL,
    target_type TINYINT NOT NULL,
    target_id BIGINT UNSIGNED NOT NULL,
    allow_bits BIGINT UNSIGNED NOT NULL DEFAULT 0,
    deny_bits BIGINT UNSIGNED NOT NULL DEFAULT 0,
    note VARCHAR(120) NULL,
    update_by BIGINT NULL,
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (channel_id, target_type, target_id),
    CONSTRAINT ck_overwrite_target CHECK ((target_type = 1 AND target_id = 0) OR (target_type = 2 AND target_id > 0)),
    CONSTRAINT ck_overwrite_bits CHECK ((allow_bits & deny_bits) = 0 AND allow_bits <= 67108863 AND deny_bits <= 67108863)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='频道权限覆盖';

-- 6) 消息修订：编辑前内容的不可变快照。
CREATE TABLE IF NOT EXISTS im_message_revision (
    id BIGINT NOT NULL AUTO_INCREMENT,
    message_id BIGINT NOT NULL,
    revision_no INT NOT NULL,
    content VARCHAR(2000) NULL,
    check_status TINYINT NOT NULL DEFAULT 1,
    editor_id BIGINT NOT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_message_revision (message_id, revision_no),
    CONSTRAINT ck_message_revision_no CHECK (revision_no > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='频道消息修订快照';

-- 7) 消息变更流：id 即 mutation_cursor，按会话单调递增。
CREATE TABLE IF NOT EXISTS im_message_mutation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    message_id BIGINT NOT NULL,
    version INT NOT NULL,
    kind VARCHAR(16) NOT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_message_mutation_conv (conversation_id, id),
    CONSTRAINT ck_message_mutation_kind CHECK (kind IN ('EDIT','RECALL','RESTORE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='频道消息变更流';

-- 8) 置顶（每会话最多 20 条，服务层加锁校验）。
CREATE TABLE IF NOT EXISTS im_message_pin (
    id BIGINT NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    message_id BIGINT NOT NULL,
    sort_no INT NOT NULL,
    pinned_by BIGINT NOT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_message_pin (conversation_id, message_id),
    KEY idx_message_pin_sort (conversation_id, sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='频道消息置顶';

-- 9) 编辑后提及（只存当前版本的提及目标）。
CREATE TABLE IF NOT EXISTS im_message_mention (
    message_id BIGINT NOT NULL,
    mention_type VARCHAR(16) NOT NULL,
    target_id BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (message_id, mention_type, target_id),
    CONSTRAINT ck_message_mention_type CHECK (mention_type IN ('USER','ROLE','REPLY','EVERYONE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='频道消息提及';

-- 10) 频道进度榜绑定：频道 ↔ (主题, 场次)；事实仍取 player_node_progress。
CREATE TABLE IF NOT EXISTS im_channel_progress_board (
    id BIGINT NOT NULL AUTO_INCREMENT,
    channel_id BIGINT UNSIGNED NOT NULL,
    board_key VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title VARCHAR(64) NOT NULL,
    topic_id BIGINT NOT NULL,
    activity_id BIGINT NOT NULL DEFAULT 0,
    row_limit INT NOT NULL DEFAULT 20,
    revision BIGINT NOT NULL DEFAULT 1,
    status TINYINT NOT NULL DEFAULT 1,
    create_by BIGINT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_channel_board (channel_id, board_key),
    CONSTRAINT ck_channel_board_scope CHECK (topic_id > 0 AND activity_id >= 0 AND row_limit BETWEEN 1 AND 50 AND status IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='频道进度榜绑定';

-- 11) 队伍共享资源池（UI清单 §25.1）：额度在建池时按本局冻结配置定格，提示/跳过同节点只扣一次。
CREATE TABLE IF NOT EXISTS play_team_resource_pool (
    team_id BIGINT NOT NULL,
    topic_id BIGINT NOT NULL,
    activity_id BIGINT NOT NULL DEFAULT 0,
    hints_total INT NOT NULL DEFAULT 0,
    hints_used INT NOT NULL DEFAULT 0,
    skips_total INT NOT NULL DEFAULT 0,
    skips_used INT NOT NULL DEFAULT 0,
    timer_limit_sec INT NOT NULL DEFAULT 0,
    timer_started_at DATETIME(3) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    frozen_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (team_id),
    CONSTRAINT ck_team_pool_counts CHECK (hints_used BETWEEN 0 AND hints_total AND skips_used BETWEEN 0 AND skips_total
        AND timer_limit_sec >= 0 AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='队伍共享资源池';

CREATE TABLE IF NOT EXISTS play_team_resource_use (
    id BIGINT NOT NULL AUTO_INCREMENT,
    team_id BIGINT NOT NULL,
    kind VARCHAR(8) NOT NULL,
    node_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_team_resource_node (team_id, kind, node_id),
    CONSTRAINT ck_team_resource_kind CHECK (kind IN ('HINT','SKIP'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='队伍共享资源消耗';

CREATE TABLE IF NOT EXISTS play_team_pool_receipt (
    member_id BIGINT NOT NULL,
    request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    team_id BIGINT NOT NULL,
    payload_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    response_json LONGTEXT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (member_id, request_id),
    CONSTRAINT ck_team_pool_receipt_json CHECK (response_json IS NULL OR JSON_VALID(response_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='队伍共享资源池原回执';
