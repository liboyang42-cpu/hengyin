-- App 客服工单只读投影（/api/support/tickets/list|detail）。工单由客服后台/机器人写入；会员侧只读本人工单、
-- 非内部消息与面向会员的事件时间线。内部备注（is_internal=1）永不对会员下发。
CREATE TABLE IF NOT EXISTS support_ticket (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 ticket_no VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 member_id BIGINT NOT NULL,
 requester_role VARCHAR(16) CHARACTER SET ascii NOT NULL,
 category VARCHAR(16) CHARACTER SET ascii NOT NULL,
 subject VARCHAR(500) NOT NULL,
 status VARCHAR(16) CHARACTER SET ascii NOT NULL DEFAULT 'OPEN',
 version BIGINT NOT NULL DEFAULT 1,
 timezone VARCHAR(64) NULL,
 locale VARCHAR(32) NULL,
 sla_state VARCHAR(32) NULL,
 sla_first_due_at DATETIME NULL,
 sla_resolve_due_at DATETIME NULL,
 related_source VARCHAR(32) CHARACTER SET ascii NULL,
 related_domain_type VARCHAR(16) CHARACTER SET ascii NULL,
 related_resource_id BIGINT NULL,
 create_time DATETIME NOT NULL,
 update_time DATETIME NOT NULL,
 resolved_at DATETIME NULL,
 closed_at DATETIME NULL,
 UNIQUE KEY uk_support_ticket_no(ticket_no),
 KEY idx_support_ticket_member(member_id,id),
 CONSTRAINT ck_support_ticket_status CHECK (status IN ('OPEN','PENDING_MEMBER','PENDING_AGENT','RESOLVED','CLOSED','REOPENED')),
 CONSTRAINT ck_support_ticket_role CHECK (requester_role IN ('PLAYER','MERCHANT','CLUB_OWNER')),
 CONSTRAINT ck_support_ticket_category CHECK (category IN ('ORDER','REFUND','PAYMENT','ACCOUNT','CONTENT','ACTIVITY','VERIFY','OTHER')),
 CONSTRAINT ck_support_ticket_version CHECK (version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS support_ticket_message (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 ticket_id BIGINT NOT NULL,
 sender_type VARCHAR(8) CHARACTER SET ascii NOT NULL,
 content MEDIUMTEXT NOT NULL,
 content_type VARCHAR(64) CHARACTER SET ascii NOT NULL DEFAULT 'text/plain',
 attachments_json VARCHAR(2000) CHARACTER SET ascii NOT NULL DEFAULT '[]',
 is_internal TINYINT NOT NULL DEFAULT 0,
 create_time DATETIME NOT NULL,
 KEY idx_support_message_ticket(ticket_id,id),
 CONSTRAINT ck_support_message_sender CHECK (sender_type IN ('MEMBER','AGENT','SYSTEM','BOT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS support_ticket_event (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 ticket_id BIGINT NOT NULL,
 event_type VARCHAR(24) CHARACTER SET ascii NOT NULL,
 actor_type VARCHAR(8) CHARACTER SET ascii NOT NULL,
 result_state VARCHAR(16) CHARACTER SET ascii NOT NULL,
 historical_status VARCHAR(16) CHARACTER SET ascii NULL,
 historical_sla_state VARCHAR(32) NULL,
 historical_version BIGINT NULL,
 reason VARCHAR(255) NULL,
 request_id VARCHAR(64) CHARACTER SET ascii NULL,
 message_id BIGINT NULL,
 created_at DATETIME NOT NULL,
 KEY idx_support_event_ticket(ticket_id,id),
 CONSTRAINT ck_support_event_actor CHECK (actor_type IN ('MEMBER','SUPPORT','SYSTEM')),
 CONSTRAINT ck_support_event_result CHECK (result_state IN ('APPLIED','REJECTED','NOT_CAPTURED','UNAVAILABLE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- runner: assert=scalar-true
SELECT COUNT(*)=3 FROM information_schema.tables WHERE table_schema=DATABASE()
 AND table_name IN ('support_ticket','support_ticket_message','support_ticket_event');
