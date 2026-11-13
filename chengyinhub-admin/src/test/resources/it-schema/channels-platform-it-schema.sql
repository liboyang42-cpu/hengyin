-- 频道平台 IT 的前置表：只含本套件读写的列；列定义取自 base_schema_20260818 及相关 migration。
CREATE TABLE ums_member (
  id bigint NOT NULL AUTO_INCREMENT, nickname varchar(64) DEFAULT NULL, name varchar(64) DEFAULT NULL,
  avatar varchar(255) DEFAULT NULL, wx_open_id varchar(64) DEFAULT NULL, del_flag int DEFAULT 0, PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE club (
  id bigint NOT NULL AUTO_INCREMENT, member_id bigint DEFAULT NULL, name varchar(64) DEFAULT NULL,
  status bigint DEFAULT 1, del_flag int DEFAULT 0, PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE club_member (
  id bigint NOT NULL AUTO_INCREMENT, club_id bigint NOT NULL, member_id bigint NOT NULL, role tinyint(1) NOT NULL DEFAULT 0,
  status tinyint(1) NOT NULL DEFAULT 1, join_time datetime DEFAULT NULL, del_flag int NOT NULL DEFAULT 0,
  PRIMARY KEY (id), UNIQUE KEY uk_club_member_uniq (club_id, member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE club_role_assignment (
  id BIGINT NOT NULL AUTO_INCREMENT, club_id BIGINT NOT NULL, target_member_id BIGINT NOT NULL, role_code VARCHAR(32) NOT NULL,
  scope_type VARCHAR(16) NOT NULL, scope_id BIGINT NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', expires_at DATETIME DEFAULT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE play_team (
  id BIGINT NOT NULL AUTO_INCREMENT, owner_type TINYINT NOT NULL, owner_id BIGINT NOT NULL, leader_member_id BIGINT NOT NULL,
  status TINYINT NOT NULL DEFAULT 0, PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE play_team_member (
  id BIGINT NOT NULL AUTO_INCREMENT, team_id BIGINT NOT NULL, member_id BIGINT NOT NULL, role TINYINT NOT NULL DEFAULT 0,
  status TINYINT NOT NULL DEFAULT 1, PRIMARY KEY (id), UNIQUE KEY uk_team_member (team_id, member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE im_conversation (
  id bigint NOT NULL AUTO_INCREMENT, type tinyint(1) NOT NULL DEFAULT 1, biz_key varchar(64) NOT NULL,
  last_msg_type tinyint(1) DEFAULT NULL, last_msg_text varchar(500) DEFAULT NULL, last_msg_at datetime DEFAULT NULL,
  status tinyint(1) NOT NULL DEFAULT 0, create_time datetime DEFAULT NULL, update_time datetime DEFAULT NULL,
  PRIMARY KEY (id), UNIQUE KEY uk_type_bizkey (type, biz_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE im_conversation_member (
  id bigint NOT NULL AUTO_INCREMENT, conversation_id bigint NOT NULL, member_id bigint NOT NULL, role tinyint(1) NOT NULL DEFAULT 0,
  unread_count int NOT NULL DEFAULT 0, last_read_msg_id bigint NOT NULL DEFAULT 0, muted tinyint(1) NOT NULL DEFAULT 0,
  deleted tinyint(1) NOT NULL DEFAULT 0, create_time datetime DEFAULT NULL,
  PRIMARY KEY (id), UNIQUE KEY uk_conv_member (conversation_id, member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE im_message (
  id bigint NOT NULL AUTO_INCREMENT, conversation_id bigint NOT NULL, sender_id bigint NOT NULL DEFAULT 0,
  receiver_id bigint NOT NULL DEFAULT 0, msg_type tinyint(1) NOT NULL DEFAULT 1, content varchar(2000) DEFAULT NULL,
  extra_json varchar(1000) DEFAULT NULL, status tinyint(1) NOT NULL DEFAULT 0, check_status tinyint(1) NOT NULL DEFAULT 0,
  trace_id varchar(64) DEFAULT NULL, create_time datetime DEFAULT NULL, visible_at DATETIME(6) NULL,
  PRIMARY KEY (id), KEY idx_conv (conversation_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE cms_topic (
  id bigint NOT NULL AUTO_INCREMENT, del_flag int DEFAULT 0, published_theme_runtime_config_json longtext,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE cms_topic_node (
  id bigint NOT NULL AUTO_INCREMENT, topic_id bigint DEFAULT NULL, status int DEFAULT 0, PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE cms_activity (
  id bigint NOT NULL AUTO_INCREMENT, topic_id bigint DEFAULT NULL, PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE player_node_progress (
  id bigint NOT NULL AUTO_INCREMENT, member_id bigint NOT NULL, activity_id bigint NOT NULL, topic_id bigint DEFAULT NULL,
  node_id bigint NOT NULL, status tinyint DEFAULT 1, checkin_time datetime DEFAULT NULL, del_flag int DEFAULT 0,
  PRIMARY KEY (id), UNIQUE KEY uk_member_activity_node (member_id, activity_id, node_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
