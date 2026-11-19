-- 2026-11-19 CG-ALL-11 替代站 / 桌面完成（业主 2026-11-17 批准）
-- 1) cms_topic_alt_station:每条路线可配的公共街面替代起点(内容侧填)。开局时玩家可改在替代站 300 米内开始。
-- 2) play_run_start_mode:本局开局方式。DESK = 桌面完成(不定位开局，奖励只发探索值与徽章);
--    ALT = 在替代站开局。正常在首站开局不写行(开局时清掉同主题旧行)。
-- 只新建两张表，不改既有表与数据，可重复执行。本文件只生成，不直接操作任何线上库。

CREATE TABLE IF NOT EXISTS cms_topic_alt_station (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    topic_id    BIGINT       NOT NULL COMMENT '主题(路线) id',
    name        VARCHAR(64)  NOT NULL COMMENT '替代站名称(公共街面地点)',
    longitude   VARCHAR(32)  NOT NULL COMMENT '经度 gcj02',
    latitude    VARCHAR(32)  NOT NULL COMMENT '纬度 gcj02',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_alt_station_topic (topic_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '路线替代起点(CG-ALL-11)';

CREATE TABLE IF NOT EXISTS play_run_start_mode (
    id             BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    member_id      BIGINT      NOT NULL COMMENT '玩家',
    activity_id    BIGINT      NOT NULL DEFAULT 0 COMMENT '场次 id;自玩为 0',
    topic_id       BIGINT      NOT NULL COMMENT '主题 id',
    run_mode       VARCHAR(8)  NOT NULL COMMENT 'DESK 桌面完成 / ALT 替代站开局',
    alt_station_id BIGINT      NULL COMMENT 'ALT 时的替代站 id',
    create_time    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '开局时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_run_start_mode (member_id, activity_id, topic_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '本局开局方式(CG-ALL-11)';
