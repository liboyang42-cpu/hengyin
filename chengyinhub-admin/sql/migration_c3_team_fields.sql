-- 成团配置字段(C3):钉在 oms_ticket 票种层;仅"城市定向"票种(mode=1)使用这些字段
-- 执行方式:连生产库 source 本文件(走 chengyin-db-migrate skill,只读预检后确认再执行)
-- 背景:成团人数/名额/报名截止/集合点/退款规则放票种层,不新建 club_event 表。
--       集合点文字复用已有 meeting_point 列,本迁移只补经纬度两列与成团语义列。
-- 影响:仅新增可空/默认列,对存量数据无破坏(非城市定向票种这些列为空/默认)。
-- ⚠️ 部署顺序:本迁移必须先于新 jar 落库,否则 OmsTicketMapper 引用这些列会 SQL 报错。

ALTER TABLE oms_ticket
    ADD COLUMN min_people      INT           NULL COMMENT '最低成团人数(达到则成团)' AFTER team_size,
    ADD COLUMN max_people      INT           NULL COMMENT '成团人数上限' AFTER min_people,
    ADD COLUMN signup_deadline DATETIME      NULL COMMENT '报名截止时间' AFTER max_people,
    ADD COLUMN gather_lng      DECIMAL(10,6)  NULL COMMENT '集合点经度(集合点文字复用 meeting_point)' AFTER signup_deadline,
    ADD COLUMN gather_lat      DECIMAL(10,6)  NULL COMMENT '集合点纬度' AFTER gather_lng,
    ADD COLUMN team_status     TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '成团状态 0募集中 1已成团 2未成团已散' AFTER gather_lat,
    ADD COLUMN refund_rule     VARCHAR(255)   NULL COMMENT '退款规则(JSON/文本)' AFTER team_status,
    ADD COLUMN need_audit      TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '报名是否需主理人确认 0否1是(预留)' AFTER refund_rule;
