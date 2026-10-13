-- 俱乐部定级/审计字段(P0b D15:后台审核页从"通过/驳回"改为"定级/打标",仅记录等级与审计)
-- 执行方式:连生产库 source 本文件(走 chengyin-db-migrate skill,只读预检后确认再执行)
-- 背景:填完即有(D14)后,后台不再做 role 升降 gate,改为给俱乐部定 L1-L5 等级 + 打运营标签;
--       需要存等级、标签、定级审核人、定级审核时间。
-- 影响:仅新增可空/默认列,对存量数据无破坏。
-- ⚠️ 部署顺序:本迁移必须先于新 jar 落库,否则 ClubMapper 引用 level/tags/audit_by/audit_time 列会 SQL 报错。

ALTER TABLE club
    ADD COLUMN level      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '俱乐部定级 0未定级 1-5' AFTER status,
    ADD COLUMN tags       VARCHAR(255) NULL COMMENT '运营标签(逗号分隔)' AFTER level,
    ADD COLUMN audit_by   VARCHAR(64)  NULL COMMENT '定级审核人' AFTER reject_reason,
    ADD COLUMN audit_time DATETIME     NULL COMMENT '定级审核时间' AFTER audit_by;
