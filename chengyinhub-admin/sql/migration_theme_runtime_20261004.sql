-- 仅交付迁移文件；不得直接对生产库执行。
ALTER TABLE cms_topic
    ADD COLUMN theme_runtime_config_json MEDIUMTEXT NULL COMMENT '通用主题背包广播场景终局配置',
    ADD COLUMN published_theme_runtime_config_json MEDIUMTEXT NULL COMMENT '已发布通用主题配置';

ALTER TABLE game_runtime_session
    ADD COLUMN play_instance_id VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '主题游玩实例',
    DROP INDEX uk_runtime_owner,
    ADD UNIQUE KEY uk_runtime_owner(activity_id,topic_id,node_id,owner_type,owner_id,play_instance_id);
ALTER TABLE player_node_progress
    ADD COLUMN play_instance_id VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '主题游玩实例',
    DROP INDEX uk_progress,
    DROP INDEX uk_member_activity_node,
    ADD UNIQUE KEY uk_progress(member_id,topic_id,node_id,activity_id,play_instance_id),
    ADD UNIQUE KEY uk_member_activity_node(member_id,activity_id,node_id,play_instance_id);

CREATE TABLE theme_broadcast_delivery (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    event_id VARCHAR(32) NOT NULL,
    content VARCHAR(500) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    message_id BIGINT NULL,
    attempts INT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL,
    update_time DATETIME NULL,
    PRIMARY KEY(id),
    UNIQUE KEY uk_theme_event(session_id,member_id,event_id),
    KEY ix_theme_pending(session_id,member_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单人主题自动广播投递回执';
