-- SYS-weather (P2): weather_event_rule for weather/time-window gated events/nodes.
-- Additive only; reversible via rollback_20261114_weather_event_rule.sql. Does not touch ledger/money tables.

CREATE TABLE IF NOT EXISTS weather_event_rule (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    topic_id BIGINT NOT NULL COMMENT '关联主题ID',
    rule_name VARCHAR(64) NOT NULL DEFAULT '' COMMENT '规则名称',
    condition_key VARCHAR(16) NOT NULL COMMENT '环境键：weather / daypart / weekpart / season',
    condition_values VARCHAR(255) NOT NULL COMMENT '匹配值列表（JSON数组或逗号分隔）',
    unlock_node_id BIGINT NULL COMMENT '解锁点位/节点ID（可选）',
    task_label VARCHAR(120) NULL COMMENT '限时任务 / 彩蛋说明（可选）',
    window_start VARCHAR(8) NULL COMMENT '每日开始时段 HH:mm 或 HH:mm:ss（可选）',
    window_end VARCHAR(8) NULL COMMENT '每日结束时段 HH:mm 或 HH:mm:ss（可选）',
    enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用：1启用 0停用',
    del_flag TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 2删除',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_weather_event_topic (topic_id, del_flag, enabled),
    KEY idx_weather_event_node (unlock_node_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='天气与环境触发规则表';
