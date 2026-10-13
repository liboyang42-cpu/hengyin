-- RBAC 角色地基 R1 迁移
-- 执行方式：在 MySQL 命令行连接到生产库后 source 本文件，或在数据库管理工具里运行
-- 注意：执行前请备份 ums_member 表

-- 1. 会员角色字段(player/club/merchant)，默认 player，不破坏现有数据
ALTER TABLE ums_member
    ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'player' COMMENT '会员角色 player/club/merchant'
    AFTER user_type;

-- 2. 老数据迁移：商家(user_type=2)→merchant，其余→player
UPDATE ums_member SET role = 'merchant' WHERE user_type = 2;
UPDATE ums_member SET role = 'player'   WHERE user_type = 1 OR user_type IS NULL;

-- 3. 角色级配额配置表
CREATE TABLE IF NOT EXISTS user_permission (
    id                 BIGINT(20)  NOT NULL AUTO_INCREMENT COMMENT '主键',
    role               VARCHAR(16) NOT NULL COMMENT '角色 player/club/merchant',
    max_themes         INT NOT NULL DEFAULT 0 COMMENT '主题同时存在上限',
    max_events         INT NOT NULL DEFAULT 0 COMMENT '活动同时存在上限',
    max_coupons        INT NOT NULL DEFAULT 0 COMMENT '优惠券同时存在上限',
    can_create_theme   TINYINT(1) NOT NULL DEFAULT 0 COMMENT '可创建主题',
    can_create_event   TINYINT(1) NOT NULL DEFAULT 0 COMMENT '可创建活动',
    can_publish_coupon TINYINT(1) NOT NULL DEFAULT 0 COMMENT '可发布优惠券',
    membership_level   VARCHAR(16) NULL COMMENT '预留:free/pro/premium,本期不用',
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_level (role, membership_level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色配额配置';

INSERT INTO user_permission (role, max_themes, max_events, max_coupons, can_create_theme, can_create_event, can_publish_coupon) VALUES
('player',   1, 0, 1, 1, 0, 1),
('club',     5, 3, 3, 1, 1, 1),
('merchant', 0, 0, 3, 0, 0, 1);
