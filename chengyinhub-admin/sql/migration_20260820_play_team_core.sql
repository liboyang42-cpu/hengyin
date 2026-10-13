-- 玩家组队 P1：邀请制队伍与成员。队伍和订单零耦合，不含退款字段或外键。
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
  create_time DATETIME,
  update_time DATETIME,
  UNIQUE KEY uk_team_member (team_id, member_id),
  KEY idx_member_teams (member_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='玩家队伍成员';
