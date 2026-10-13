-- 三身份活动级游戏局 P0 权威事实表。
-- 合并前必须走 chengyin-db-migrate；本文件只定义 schema，不包含样例数据。
-- 回滚（仅确认无业务数据后）：依依赖反序 DROP 九张 game_* 表，并 DROP cms_topic.game_module_config_json。

SET @has_game_module_config := (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema=DATABASE() AND table_name='cms_topic'
       AND column_name='game_module_config_json');
SET @ddl := IF(@has_game_module_config=0,
    'ALTER TABLE cms_topic ADD COLUMN game_module_config_json JSON NULL COMMENT ''三身份游戏模组配置，仅服务端保存权威配置''',
    'SELECT ''cms_topic.game_module_config_json already exists''');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

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

-- runner: assert=scalar-true
SELECT (
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='cms_topic'
        AND column_name='game_module_config_json'
        AND data_type='json' AND is_nullable='YES')=1
    AND
    (SELECT COUNT(*) FROM information_schema.tables
      WHERE table_schema=DATABASE() AND table_name IN (
        'game_session','game_station_run','game_team_role_assignment','game_story_state',
        'game_command_receipt','game_task_submission','game_player_node_outcome',
        'game_broadcast','game_broadcast_delivery')
        AND table_type='BASE TABLE' AND engine='InnoDB')=9
) AS game_schema_objects_match;

-- CREATE TABLE IF NOT EXISTS 遇到同名残缺旧表不会补列；逐表回读全部运行时必需列，禁止假绿。
-- runner: assert=scalar-true
SELECT (
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='game_session' AND column_name IN (
        'id','activity_id','topic_id','club_id','module_config_snapshot_json','status','current_chapter_id',
        'revision','prepared_by','started_by','finished_by','started_at','finished_at','leaderboard_visible',
        'leaderboard_updated_by','leaderboard_updated_at','create_time','update_time'))=18
    AND
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='game_station_run' AND column_name IN (
        'id','game_session_id','activity_id','node_id','node_name','station_code','merchant_member_id','source_type',
        'source_id','status','preparation_snapshot_json','capacity','service_start_at','service_end_at','pause_reason_code',
        'pause_reason','resume_eta','fallback_node_id','fallback_plan_code','fallback_plan_version','revision',
        'create_time','update_time'))=23
    AND
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='game_team_role_assignment' AND column_name IN (
        'id','game_session_id','team_id','member_id','role_code','role_name','status','active_guard','assigned_by',
        'superseded_by','superseded_at','revision','create_time','update_time'))=14
    AND
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='game_story_state' AND column_name IN (
        'id','game_session_id','owner_type','owner_id','variables_json','applied_choices_json','ending_code','status',
        'revision','create_time','update_time'))=11
    AND
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='game_command_receipt' AND column_name IN (
        'id','game_session_id','activity_id','node_id','actor_member_id','actor_perspective','request_id','request_hash',
        'action','outcome','reason_code','revision','result_json','create_time','update_time'))=15
    AND
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='game_task_submission' AND column_name IN (
        'id','game_session_id','activity_id','station_run_id','node_id','team_id','member_id','task_code','evidence_json',
        'status','active_guard','decided_by','decision_reason_code','decision_reason','revision','create_time','update_time'))=17
    AND
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='game_player_node_outcome' AND column_name IN (
        'id','game_session_id','activity_id','station_run_id','node_id','team_id','member_id','status','completion_source',
        'source_submission_id','source_receipt_id','fallback_plan_code','fallback_plan_version','completed_at','create_time'))=15
    AND
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='game_broadcast' AND column_name IN (
        'id','game_session_id','activity_id','sender_member_id','target_type','target_id','role_code','content','status',
        'create_time','update_time'))=11
    AND
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='game_broadcast_delivery' AND column_name IN (
        'id','broadcast_id','member_id','status','message_id','error_code','create_time','update_time'))=8
) AS game_required_columns_present;

-- runner: assert=scalar-true
SELECT (
    (SELECT GROUP_CONCAT(CONCAT(seq_in_index, ':', column_name, ':', non_unique)
                 ORDER BY seq_in_index SEPARATOR ',')
       FROM information_schema.statistics
      WHERE table_schema=DATABASE() AND table_name='game_session'
        AND index_name='uk_game_session_activity')
        = '1:activity_id:0'
    AND
    (SELECT GROUP_CONCAT(CONCAT(seq_in_index, ':', column_name, ':', non_unique)
                 ORDER BY seq_in_index SEPARATOR ',')
       FROM information_schema.statistics
      WHERE table_schema=DATABASE() AND table_name='game_station_run'
        AND index_name='uk_game_station_session_node')
        = '1:game_session_id:0,2:node_id:0'
    AND
    (SELECT GROUP_CONCAT(CONCAT(seq_in_index, ':', column_name, ':', non_unique)
                 ORDER BY seq_in_index SEPARATOR ',')
       FROM information_schema.statistics
      WHERE table_schema=DATABASE() AND table_name='game_station_run'
        AND index_name='uk_game_station_session_code')
        = '1:game_session_id:0,2:station_code:0'
    AND
    (SELECT GROUP_CONCAT(CONCAT(seq_in_index, ':', column_name, ':', non_unique)
                 ORDER BY seq_in_index SEPARATOR ',')
       FROM information_schema.statistics
      WHERE table_schema=DATABASE() AND table_name='game_team_role_assignment'
        AND index_name='uk_game_role_active')
        = '1:game_session_id:0,2:member_id:0,3:active_guard:0'
    AND
    (SELECT GROUP_CONCAT(CONCAT(seq_in_index, ':', column_name, ':', non_unique)
                 ORDER BY seq_in_index SEPARATOR ',')
       FROM information_schema.statistics
      WHERE table_schema=DATABASE() AND table_name='game_story_state'
        AND index_name='uk_game_story_owner')
        = '1:game_session_id:0,2:owner_type:0,3:owner_id:0'
    AND
    (SELECT GROUP_CONCAT(CONCAT(seq_in_index, ':', column_name, ':', non_unique)
                 ORDER BY seq_in_index SEPARATOR ',')
       FROM information_schema.statistics
      WHERE table_schema=DATABASE() AND table_name='game_command_receipt'
        AND index_name='uk_game_receipt_actor_request')
        = '1:activity_id:0,2:actor_member_id:0,3:request_id:0'
    AND
    (SELECT GROUP_CONCAT(CONCAT(seq_in_index, ':', column_name, ':', non_unique)
                 ORDER BY seq_in_index SEPARATOR ',')
       FROM information_schema.statistics
      WHERE table_schema=DATABASE() AND table_name='game_task_submission'
        AND index_name='uk_game_submission_active')
        = '1:game_session_id:0,2:member_id:0,3:node_id:0,4:task_code:0,5:active_guard:0'
    AND
    (SELECT GROUP_CONCAT(CONCAT(seq_in_index, ':', column_name, ':', non_unique)
                 ORDER BY seq_in_index SEPARATOR ',')
       FROM information_schema.statistics
      WHERE table_schema=DATABASE() AND table_name='game_player_node_outcome'
        AND index_name='uk_game_outcome_player_node')
        = '1:game_session_id:0,2:member_id:0,3:node_id:0'
    AND
    (SELECT GROUP_CONCAT(CONCAT(seq_in_index, ':', column_name, ':', non_unique)
                 ORDER BY seq_in_index SEPARATOR ',')
       FROM information_schema.statistics
      WHERE table_schema=DATABASE() AND table_name='game_broadcast_delivery'
        AND index_name='uk_game_delivery_member')
        = '1:broadcast_id:0,2:member_id:0'
) AS game_unique_indexes_match;

-- runner: assert=scalar-true
SELECT (
    (SELECT CONCAT(data_type, ':', is_nullable, ':', extra, ':',
                   REGEXP_REPLACE(
                       REPLACE(LOWER(generation_expression), '_utf8mb4', ''),
                       -- 生产 MySQL 把表达式里的引号渲染成 \\' ；归一化必须连反斜杠一起扣，
                       -- 否则这条断言在真库上恒红（2026-08-24 实测 readback=0）。
                       '[[:space:]`()\\\\]', ''))
       FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='game_team_role_assignment'
        AND column_name='active_guard')
        = 'tinyint:YES:STORED GENERATED:casewhenstatusin''assigned'',''confirmed''then1elsenullend'
    AND
    (SELECT CONCAT(data_type, ':', is_nullable, ':', extra, ':',
                   REGEXP_REPLACE(
                       REPLACE(LOWER(generation_expression), '_utf8mb4', ''),
                       -- 生产 MySQL 把表达式里的引号渲染成 \\' ；归一化必须连反斜杠一起扣，
                       -- 否则这条断言在真库上恒红（2026-08-24 实测 readback=0）。
                       '[[:space:]`()\\\\]', ''))
       FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='game_task_submission'
        AND column_name='active_guard')
        = 'tinyint:YES:STORED GENERATED:casewhenstatusin''pending'',''approved'',''recorded''then1elsenullend'
) AS game_generated_guards_match;

-- runner: assert=scalar-true
SELECT (
    (SELECT GROUP_CONCAT(CONCAT(table_name, '.', column_name, ':', column_type, ':', is_nullable)
                 ORDER BY table_name, column_name SEPARATOR ',')
       FROM information_schema.columns
      WHERE table_schema=DATABASE() AND (
        (table_name='game_station_run' AND column_name IN ('fallback_plan_code','fallback_plan_version'))
        OR (table_name='game_player_node_outcome' AND column_name IN (
            'status','completion_source','source_submission_id','source_receipt_id',
            'fallback_plan_code','fallback_plan_version','completed_at'))
      )) = CONCAT(
        'game_player_node_outcome.completed_at:datetime:NO,',
        'game_player_node_outcome.completion_source:varchar(32):NO,',
        'game_player_node_outcome.fallback_plan_code:varchar(64):YES,',
        'game_player_node_outcome.fallback_plan_version:int:YES,',
        'game_player_node_outcome.source_receipt_id:bigint:YES,',
        'game_player_node_outcome.source_submission_id:bigint:YES,',
        'game_player_node_outcome.status:varchar(24):NO,',
        'game_station_run.fallback_plan_code:varchar(64):YES,',
        'game_station_run.fallback_plan_version:int:YES')
) AS game_outcome_columns_match;

-- runner: assert=scalar-true
SELECT (
    (SELECT GROUP_CONCAT(CONCAT(table_name, '.', column_name, ':', data_type, ':', is_nullable)
                 ORDER BY table_name, column_name SEPARATOR ',')
       FROM information_schema.columns
      WHERE table_schema=DATABASE() AND (
        (table_name='cms_topic' AND column_name='game_module_config_json')
        OR (table_name='game_session' AND column_name='module_config_snapshot_json')
        OR (table_name='game_station_run' AND column_name='preparation_snapshot_json')
        OR (table_name='game_story_state' AND column_name IN ('variables_json','applied_choices_json'))
        OR (table_name='game_command_receipt' AND column_name='result_json')
        OR (table_name='game_task_submission' AND column_name='evidence_json')
      )) = CONCAT(
        'cms_topic.game_module_config_json:json:YES,',
        'game_command_receipt.result_json:json:YES,',
        'game_session.module_config_snapshot_json:json:NO,',
        'game_station_run.preparation_snapshot_json:json:YES,',
        'game_story_state.applied_choices_json:json:NO,',
        'game_story_state.variables_json:json:NO,',
        'game_task_submission.evidence_json:json:NO')
) AS game_json_columns_match;

-- 独立负控（仅预演库事务内手工执行并回滚）：重复 activity_id 应报 1062。
-- INSERT INTO game_session(activity_id,topic_id,club_id,module_config_snapshot_json,status,revision,
-- prepared_by,leaderboard_visible,create_time,update_time)
-- VALUES(-823,-1,-1,JSON_OBJECT(),'PREPARING',0,-1,0,NOW(),NOW()),
--       (-823,-1,-1,JSON_OBJECT(),'PREPARING',0,-1,0,NOW(),NOW());
