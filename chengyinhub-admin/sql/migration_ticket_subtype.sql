-- oms_ticket 扩列(报告§7.4 · D-7):有人带/无人带双票子类型
-- 执行方式:连生产库 source 本文件(走 chengyin-db-migrate skill,只读预检后确认再执行)
-- 影响:加可空列,默认 NULL(视为不区分),对现有票无副作用。
-- ⚠️ 部署顺序:先于新 jar 落库,否则 OmsTicket 引用 sub_type 会 SQL 报错。

ALTER TABLE oms_ticket
    ADD COLUMN sub_type VARCHAR(8) NULL COMMENT '票种子类型 guided有人带/self无人带(仅mode=1城市定向)' AFTER mode;
