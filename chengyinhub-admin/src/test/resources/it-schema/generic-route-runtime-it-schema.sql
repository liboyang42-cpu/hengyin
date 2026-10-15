-- Isolated worker B fixture. Game and team CREATE statements copied from the existing migrations; no production connection.
CREATE TABLE IF NOT EXISTS game_session (
    id BIGINT NOT NULL AUTO_INCREMENT,
    activity_id BIGINT NOT NULL,
    topic_id BIGINT NOT NULL,
    club_id BIGINT NOT NULL,
    module_config_snapshot_json JSON NOT NULL,
    status VARCHAR(16) NOT NULL,
    current_chapter_id BIGINT NULL,
    revision INT NOT NULL DEFAULT 0,
    prepared_by BIGINT NOT NULL,
    started_by BIGINT NULL,
    finished_by BIGINT NULL,
    started_at DATETIME NULL,
    finished_at DATETIME NULL,
    leaderboard_visible TINYINT(1) NOT NULL DEFAULT 0,
    leaderboard_updated_by BIGINT NULL,
    leaderboard_updated_at DATETIME NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_game_session_activity (activity_id),
    KEY idx_game_session_topic (topic_id),
    KEY idx_game_session_club_status (club_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='活动级三身份游戏总局';

CREATE TABLE IF NOT EXISTS game_station_run (
    id BIGINT NOT NULL AUTO_INCREMENT,
    game_session_id BIGINT NOT NULL,
    activity_id BIGINT NOT NULL,
    node_id BIGINT NOT NULL,
    node_name VARCHAR(120) NOT NULL,
    station_code VARCHAR(64) NOT NULL,
    merchant_member_id BIGINT NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    preparation_snapshot_json JSON NULL,
    capacity INT NULL,
    service_start_at DATETIME NULL,
    service_end_at DATETIME NULL,
    pause_reason_code VARCHAR(64) NULL,
    pause_reason VARCHAR(200) NULL,
    resume_eta DATETIME NULL,
    fallback_node_id BIGINT NULL,
    fallback_plan_code VARCHAR(64) NULL,
    fallback_plan_version INT NULL,
    revision INT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_game_station_session_node (game_session_id, node_id),
    UNIQUE KEY uk_game_station_session_code (game_session_id, station_code),
    KEY idx_game_station_merchant (game_session_id, merchant_member_id, status),
    KEY idx_game_station_activity (activity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本局商家站点运行态';

CREATE TABLE IF NOT EXISTS game_team_role_assignment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    game_session_id BIGINT NOT NULL,
    team_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    role_code VARCHAR(64) NOT NULL,
    role_name VARCHAR(120) NOT NULL,
    status VARCHAR(16) NOT NULL,
    active_guard TINYINT GENERATED ALWAYS AS (
        CASE WHEN status IN ('ASSIGNED','CONFIRMED') THEN 1 ELSE NULL END
    ) STORED,
    assigned_by BIGINT NOT NULL,
    superseded_by BIGINT NULL,
    superseded_at DATETIME NULL,
    revision INT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_game_role_active (game_session_id, member_id, active_guard),
    KEY idx_game_role_team (game_session_id, team_id, status),
    KEY idx_game_role_code (game_session_id, role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本局队伍剧情角色分配';

CREATE TABLE IF NOT EXISTS game_story_state (
    id BIGINT NOT NULL AUTO_INCREMENT,
    game_session_id BIGINT NOT NULL,
    owner_type VARCHAR(16) NOT NULL,
    owner_id BIGINT NOT NULL,
    variables_json JSON NOT NULL,
    applied_choices_json JSON NOT NULL,
    ending_code VARCHAR(64) NULL,
    status VARCHAR(24) NOT NULL,
    revision INT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_game_story_owner (game_session_id, owner_type, owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本局队伍或玩家剧情变量';

CREATE TABLE IF NOT EXISTS game_command_receipt (
    id BIGINT NOT NULL AUTO_INCREMENT,
    game_session_id BIGINT NULL,
    activity_id BIGINT NOT NULL,
    node_id BIGINT NULL,
    actor_member_id BIGINT NOT NULL,
    actor_perspective VARCHAR(16) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    action VARCHAR(64) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    reason_code VARCHAR(64) NULL,
    revision INT NULL,
    result_json JSON NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_game_receipt_actor_request (activity_id, actor_member_id, request_id),
    KEY idx_game_receipt_session (game_session_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本局幂等命令权威回执';

CREATE TABLE IF NOT EXISTS game_task_submission (
    id BIGINT NOT NULL AUTO_INCREMENT,
    game_session_id BIGINT NOT NULL,
    activity_id BIGINT NOT NULL,
    station_run_id BIGINT NOT NULL,
    node_id BIGINT NOT NULL,
    team_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    task_code VARCHAR(64) NOT NULL,
    evidence_json JSON NOT NULL,
    status VARCHAR(16) NOT NULL,
    active_guard TINYINT GENERATED ALWAYS AS (
        CASE WHEN status IN ('PENDING','APPROVED','RECORDED') THEN 1 ELSE NULL END
    ) STORED,
    decided_by BIGINT NULL,
    decision_reason_code VARCHAR(64) NULL,
    decision_reason VARCHAR(200) NULL,
    revision INT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_game_submission_active (game_session_id, member_id, node_id, task_code, active_guard),
    KEY idx_game_submission_station (game_session_id, station_run_id, status),
    KEY idx_game_submission_member (game_session_id, member_id, id),
    KEY idx_game_submission_team (game_session_id, team_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='玩家任务提交与商家核验';

CREATE TABLE IF NOT EXISTS game_player_node_outcome (
    id BIGINT NOT NULL AUTO_INCREMENT,
    game_session_id BIGINT NOT NULL,
    activity_id BIGINT NOT NULL,
    station_run_id BIGINT NOT NULL,
    node_id BIGINT NOT NULL,
    team_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL,
    completion_source VARCHAR(32) NOT NULL,
    source_submission_id BIGINT NULL,
    source_receipt_id BIGINT NULL,
    fallback_plan_code VARCHAR(64) NULL,
    fallback_plan_version INT NULL,
    completed_at DATETIME NOT NULL,
    create_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_game_outcome_player_node (game_session_id, member_id, node_id),
    KEY idx_game_outcome_team (game_session_id, team_id, status),
    KEY idx_game_outcome_station (game_session_id, station_run_id, status),
    CONSTRAINT chk_game_outcome_status CHECK (status IN ('COMPLETED','FALLBACK_COMPLETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='玩家本局节点首次完成权威结果';

CREATE TABLE IF NOT EXISTS game_broadcast (
    id BIGINT NOT NULL AUTO_INCREMENT,
    game_session_id BIGINT NOT NULL,
    activity_id BIGINT NOT NULL,
    sender_member_id BIGINT NOT NULL,
    target_type VARCHAR(16) NOT NULL,
    target_id BIGINT NULL,
    role_code VARCHAR(64) NULL,
    content VARCHAR(200) NOT NULL,
    status VARCHAR(16) NOT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    KEY idx_game_broadcast_session (game_session_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='俱乐部定向广播';

CREATE TABLE IF NOT EXISTS game_broadcast_delivery (
    id BIGINT NOT NULL AUTO_INCREMENT,
    broadcast_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    message_id BIGINT NULL,
    error_code VARCHAR(64) NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_game_delivery_member (broadcast_id, member_id),
    KEY idx_game_delivery_status (broadcast_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='广播逐玩家送达回执';

CREATE TABLE play_team (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  owner_type TINYINT NOT NULL COMMENT '1=topic 2=activity(对齐 oms_ticket.owner_type)',
  owner_id BIGINT NOT NULL,
  leader_member_id BIGINT NOT NULL,
  title VARCHAR(60) DEFAULT NULL COMMENT '队名/一句话招募语,走UGC机审',
  join_mode TINYINT NOT NULL DEFAULT 1 COMMENT '1=邀请制(仅链接/码可进) 2=公开申请制',
  max_members INT NOT NULL DEFAULT 4,
  joined_count INT NOT NULL DEFAULT 1 COMMENT '原子扣位用',
  status TINYINT NOT NULL DEFAULT 0 COMMENT '0招募中 1已锁定(满员) 2进行中 3已结束 4已解散',
  expire_time DATETIME DEFAULT NULL COMMENT '默认=场次开始时间',
  create_time DATETIME,
  update_time DATETIME,
  KEY idx_team_owner (owner_type, owner_id, status),
  KEY idx_team_expire (status, expire_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='玩家队伍';

CREATE TABLE play_team_member (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  team_id BIGINT NOT NULL,
  member_id BIGINT NOT NULL,
  role TINYINT NOT NULL DEFAULT 0 COMMENT '0成员 1队长',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '0待审核 1已加入 2已退出 3已移出 4已拒绝',
  apply_message VARCHAR(200) DEFAULT NULL,
  apply_expire_time DATETIME DEFAULT NULL,
  create_time DATETIME,
  update_time DATETIME,
  UNIQUE KEY uk_team_member (team_id, member_id),
  KEY idx_member_teams (member_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='玩家队伍成员';

ALTER TABLE play_team ADD COLUMN invite_code VARCHAR(32) NULL;

CREATE TABLE cms_activity (id BIGINT PRIMARY KEY, topic_id BIGINT, del_flag INT DEFAULT 0) ENGINE=InnoDB;

CREATE TABLE cms_registration (member_id BIGINT, owner_type INT, owner_id BIGINT, topic_id BIGINT, payment_status INT, registration_status INT) ENGINE=InnoDB;
