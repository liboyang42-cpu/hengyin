-- 文档二 UI 实施 L8:主题的「可玩时段」与「开局前准备」(原型 A e-time / e-prep,原型 B t-safe)。
-- 一主题一行;没有行 = 不限时段、没有准备项(老主题行为不变)。
-- window_mode:any 不限 / day 06:00–20:00 / night 20:00–06:00 / custom 自定义(window_start / window_end 为 HH:mm)。
-- prep_items_json:JSON 字符串数组,最多 6 项、每项 1–20 字(服务层校验)。
-- 只建新表,不改任何已有表与数据;可重复执行。
-- 部署顺序:先落库,再部署读写本表的 jar。生产执行须走 chengyin-db-migrate 只读预检并取得用户确认。
CREATE TABLE IF NOT EXISTS cms_topic_play_setup (
    topic_id        BIGINT       NOT NULL COMMENT 'cms_topic.id',
    window_mode     VARCHAR(16)  NOT NULL DEFAULT 'any' COMMENT 'any/day/night/custom',
    window_start    CHAR(5)      NULL DEFAULT NULL COMMENT '自定义开始 HH:mm',
    window_end      CHAR(5)      NULL DEFAULT NULL COMMENT '自定义结束 HH:mm',
    prep_items_json VARCHAR(1024) NULL DEFAULT NULL COMMENT '开局前准备项 JSON 数组',
    create_time     DATETIME     NULL DEFAULT NULL,
    update_time     DATETIME     NULL DEFAULT NULL,
    PRIMARY KEY (topic_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='主题可玩时段与开局前准备';
