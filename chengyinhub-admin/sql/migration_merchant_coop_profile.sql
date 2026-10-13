-- 商家承接档案字段(P1.5 §7.5):B2B 撮合用,商家维护可承接能力
-- 执行方式:连生产库 source 本文件(走 chengyin-db-migrate skill,只读预检后确认再执行)
-- 背景:达人带俱乐部成员去商家消费,商家需声明容纳人数/可承接时段/适合活动/收费/诉求/是否开放对接。
-- 影响:仅新增可空/默认列,对存量数据无破坏。
-- ⚠️ 部署顺序:本迁移必须先于新 jar 落库,否则 MmsMerchantMapper 引用这些列会 SQL 报错。

ALTER TABLE mms_merchant
    ADD COLUMN capacity            INT          NULL COMMENT '可容纳/承接人数',
    ADD COLUMN available_time      VARCHAR(255) NULL COMMENT '可承接时段',
    ADD COLUMN suit_activity_types VARCHAR(255) NULL COMMENT '适合/想承接的活动类型(逗号分隔)',
    ADD COLUMN charge_type         TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '承接收费 0免费 1收费',
    ADD COLUMN demand              VARCHAR(500) NULL COMMENT '诉求(想要什么)',
    ADD COLUMN coop_open           TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否开放被达人对接 0否1是';
