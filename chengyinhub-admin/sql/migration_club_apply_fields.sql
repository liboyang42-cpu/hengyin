-- 俱乐部申请采集字段补充(D17:城市 + 能力档案,作为 L1-L5 定级证据)
-- 执行方式:连生产库 source 本文件(走 chengyin-db-migrate skill,只读预检后确认再执行)
-- 背景:小程序申请向导扩展采集"城市/风格/关键词/能力自评/资质",当前 club 表无对应列会被静默丢弃。
-- 影响:仅新增可空/默认列,对存量数据无破坏。
-- ⚠️ 部署顺序:本迁移必须先于新 jar 落库,否则 ClubMapper 引用这些列会 SQL 报错。

ALTER TABLE club
    ADD COLUMN city              VARCHAR(64)   NULL COMMENT '所在城市' AFTER address,
    ADD COLUMN keywords          VARCHAR(255)  NULL COMMENT '核心关键词(逗号分隔,≤3)' AFTER city,
    ADD COLUMN style             VARCHAR(64)   NULL COMMENT '风格/调性' AFTER keywords,
    ADD COLUMN max_event_size    INT           NULL COMMENT '可承接最大活动人数' AFTER style,
    ADD COLUMN avg_event_size    INT           NULL COMMENT '常规活动人数' AFTER max_event_size,
    ADD COLUMN can_design_route  TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '能否设计路线 0否1是' AFTER avg_event_size,
    ADD COLUMN can_design_task   TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '能否设计任务 0否1是' AFTER can_design_route,
    ADD COLUMN can_npc           TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '能否提供NPC 0否1是' AFTER can_design_task,
    ADD COLUMN can_merchant_coop TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '能否商家合作 0否1是' AFTER can_npc,
    ADD COLUMN has_guide_cert    TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '是否有导游/旅游资质 0否1是' AFTER can_merchant_coop,
    ADD COLUMN cert_images       TEXT          NULL COMMENT '资质证书图(逗号分隔URL)' AFTER has_guide_cert;
