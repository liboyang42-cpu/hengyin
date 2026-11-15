-- P2 links-and-push（roadmap slug links-push）：App 远程推送设备登记 + 俱乐部通知 APNS 渠道标记。
--
-- 回滚说明（rollback）：
--   DROP TABLE IF EXISTS club_event_campaign_push;
--   DROP TABLE IF EXISTS push_device;
--   两张表都是本迁移新建的，删掉不影响任何既有业务表；代码回滚后 APNS 渠道重新回 501。
--
-- 只新增表，不改既有表：club_event_notification_campaign 的 channel 约束（= 'IN_APP'）保持原样，
-- 因为 migration_club_role_event_ops_20260823.sql 对它做了精确形状断言。APNS 任务照旧落 IN_APP 站内信行，
-- 另在 club_event_campaign_push 记一行“该任务同时向受众已登记设备推送”。
--
-- 幂等：全部 CREATE TABLE IF NOT EXISTS，可重复执行。
-- 依赖：migration_club_role_event_ops_20260823.sql（club_event_notification_campaign）。

-- 1) 推送设备登记：token 按 (token, environment) 唯一；换账号登录时同一 token 改绑到当前会员。
CREATE TABLE IF NOT EXISTS push_device (
    id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL COMMENT '当前绑定会员',
    platform VARCHAR(8) NOT NULL COMMENT 'IOS',
    token VARCHAR(200) CHARACTER SET ascii NOT NULL COMMENT 'APNs device token（小写十六进制）',
    environment VARCHAR(12) NOT NULL COMMENT 'SANDBOX / PRODUCTION',
    bundle_id VARCHAR(128) NULL,
    locale VARCHAR(35) NULL,
    enabled TINYINT NOT NULL DEFAULT 1,
    last_seen_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_push_device_token_env (token, environment),
    KEY idx_push_device_member (member_id, enabled, environment),
    CONSTRAINT ck_push_device_platform CHECK (platform IN ('IOS')),
    CONSTRAINT ck_push_device_env CHECK (environment IN ('SANDBOX', 'PRODUCTION')),
    CONSTRAINT ck_push_device_enabled CHECK (enabled IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='App 远程推送设备登记';

-- 2) 俱乐部通知任务的 APNS 渠道标记：有行 = 该任务每条站内信送达后，再向该收件人已登记设备推送一次。
CREATE TABLE IF NOT EXISTS club_event_campaign_push (
    campaign_id BIGINT NOT NULL,
    channel VARCHAR(16) NOT NULL DEFAULT 'APNS',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (campaign_id),
    CONSTRAINT ck_club_event_campaign_push_channel CHECK (channel IN ('APNS'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='俱乐部通知任务附加 APNs 推送标记';
