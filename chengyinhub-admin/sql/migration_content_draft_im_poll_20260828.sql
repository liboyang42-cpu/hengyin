-- NF-04 云端草稿 + NF-06 群聊投票（仅准备，禁止随 PR 自动执行）

CREATE TABLE IF NOT EXISTS `content_draft` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `owner_member_id` BIGINT NOT NULL,
  `business_type` VARCHAR(16) NOT NULL,
  `client_draft_key` VARCHAR(64) NOT NULL,
  `subject_id` BIGINT NULL,
  `payload_json` JSON NOT NULL,
  `payload_hash` CHAR(64) NOT NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  `version` BIGINT NOT NULL DEFAULT 1,
  `updated_by_device` VARCHAR(64) NOT NULL,
  `published_resource_id` BIGINT NULL,
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_content_draft_business_key`
    (`owner_member_id`, `business_type`, `client_draft_key`),
  KEY `idx_content_draft_owner_status_time`
    (`owner_member_id`, `status`, `update_time`),
  CONSTRAINT `chk_content_draft_business_type`
    CHECK (`business_type` IN ('ACTIVITY', 'TOPIC')),
  CONSTRAINT `chk_content_draft_status`
    CHECK (`status` IN ('DRAFT', 'PUBLISHED', 'DELETED')),
  CONSTRAINT `chk_content_draft_version` CHECK (`version` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='活动/主题发布流云端草稿';

CREATE TABLE IF NOT EXISTS `im_poll` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `conversation_id` BIGINT NOT NULL,
  `creator_member_id` BIGINT NOT NULL,
  `client_poll_key` VARCHAR(64) NOT NULL,
  `request_hash` CHAR(64) NOT NULL,
  `question` VARCHAR(200) NOT NULL,
  `selection_mode` VARCHAR(16) NOT NULL DEFAULT 'SINGLE',
  `visibility` VARCHAR(16) NOT NULL DEFAULT 'PUBLIC',
  `status` VARCHAR(16) NOT NULL DEFAULT 'OPEN',
  `deadline_at` DATETIME(3) NULL,
  `version` BIGINT NOT NULL DEFAULT 1,
  `message_id` BIGINT NULL,
  `closed_by` BIGINT NULL,
  `closed_at` DATETIME(3) NULL,
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_im_poll_business_key`
    (`conversation_id`, `creator_member_id`, `client_poll_key`),
  UNIQUE KEY `uk_im_poll_message` (`message_id`),
  KEY `idx_im_poll_conversation_time` (`conversation_id`, `create_time`),
  CONSTRAINT `chk_im_poll_selection_mode` CHECK (`selection_mode` = 'SINGLE'),
  CONSTRAINT `chk_im_poll_visibility` CHECK (`visibility` = 'PUBLIC'),
  CONSTRAINT `chk_im_poll_status` CHECK (`status` IN ('OPEN', 'CLOSED')),
  CONSTRAINT `chk_im_poll_version` CHECK (`version` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='群聊投票';

CREATE TABLE IF NOT EXISTS `im_poll_option` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `poll_id` BIGINT NOT NULL,
  `position` INT NOT NULL,
  `content` VARCHAR(100) NOT NULL,
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_im_poll_option_position` (`poll_id`, `position`),
  KEY `idx_im_poll_option_poll` (`poll_id`),
  CONSTRAINT `chk_im_poll_option_position` CHECK (`position` BETWEEN 1 AND 10)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='群聊投票选项';

CREATE TABLE IF NOT EXISTS `im_poll_vote` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `poll_id` BIGINT NOT NULL,
  `option_id` BIGINT NOT NULL,
  `member_id` BIGINT NOT NULL,
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_im_poll_vote_member` (`poll_id`, `member_id`),
  KEY `idx_im_poll_vote_option` (`poll_id`, `option_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='群聊投票记录（一人一票，不允许改票）';

CREATE TABLE IF NOT EXISTS `im_poll_audience` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `poll_id` BIGINT NOT NULL,
  `member_id` BIGINT NOT NULL,
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_im_poll_audience_member` (`poll_id`, `member_id`),
  KEY `idx_im_poll_audience_member` (`member_id`, `poll_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='投票创建时的群成员快照（退群后仅可读历史）';
