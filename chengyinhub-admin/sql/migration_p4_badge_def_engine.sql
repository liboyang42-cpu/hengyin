-- migration_p4_badge_def_engine.sql
-- 走 chengyin-db-migrate:只读预检 `--describe growth_badge` 确认列不存在后执行。
-- ADD COLUMN nullable,MySQL8 INSTANT 不锁表,可逆。必须先于引用新列的 jar 上线。
--
-- 【硬依赖警告】本迁移的 INSERT 种子行是 jar 运行期硬依赖:若 jar 先于种子行上线,
-- 全部 6 个游戏勋章(FIRST_STEP/STREAK_3/STREAK_7/TOPIC_CLEAR/MILE_10/MILE_50)将静默不发
-- (PlayerProgressionServiceImpl.awardByThreshold 里 growthBadgeMapper.selectByCode(code) 返回
-- null 即 return,无报错无日志)。必须整文件(ALTER+INSERT)先于 jar 上线。
--
-- 上线后校验(应 >= 9):
-- SELECT COUNT(*) FROM growth_badge WHERE status='0' AND unlock_rule_json IS NOT NULL;

ALTER TABLE growth_badge
  ADD COLUMN asset_type       VARCHAR(16)  NULL COMMENT 'IDENTITY/GROWTH/COLLECTION/HONOR',
  ADD COLUMN category         VARCHAR(16)  NULL COMMENT 'EXPLORE/CREATE/ORGANIZE/CONNECT/CO_CREATE',
  ADD COLUMN trigger_event    VARCHAR(64)  NULL COMMENT '订阅的领域事件',
  ADD COLUMN unlock_rule_json JSON         NULL COMMENT '规则表达(aggregate/threshold/...)',
  ADD COLUMN rule_version     INT          NULL DEFAULT 1,
  ADD COLUMN rarity           VARCHAR(16)  NULL COMMENT 'L3/L4可用,L1/L2为空',
  ADD COLUMN badge_family     VARCHAR(64)  NULL COMMENT '梯度族(如MILE),同族不同阈值',
  ADD COLUMN name_en          VARCHAR(64)  NULL,
  ADD COLUMN statement        VARCHAR(255) NULL COMMENT '身份宣言/卡面主文案';

-- 种子:现有 6 个游戏勋章(沿用旧 code),补四层定义。status '0'=启用(沿用现表约定)。
INSERT INTO growth_badge (badge_code, badge_name, icon_url, asset_type, category, trigger_event, unlock_rule_json, rule_version, status, sort_no, create_time)
VALUES
 ('FIRST_STEP','初次启程','','IDENTITY','EXPLORE','node_done','{"aggregate":"exists","threshold":1}',1,'0',1,now()),
 ('TOPIC_CLEAR','通关纪念','','IDENTITY','EXPLORE','topic_complete','{"aggregate":"exists","threshold":1}',1,'0',2,now()),
 ('MILE_10','城市漫游·10km','','GROWTH','EXPLORE','node_done','{"aggregate":"sum","aggregate_field":"mileage","threshold":10}',1,'0',3,now()),
 ('MILE_50','城市行者·50km','','GROWTH','EXPLORE','node_done','{"aggregate":"sum","aggregate_field":"mileage","threshold":50}',1,'0',4,now()),
 ('STREAK_3','连续三日','','GROWTH','EXPLORE','node_done','{"aggregate":"distinct","aggregate_field":"active_day","threshold":3,"window":{"type":"natural_day_streak"}}',1,'0',5,now()),
 ('STREAK_7','一周不辍','','GROWTH','EXPLORE','node_done','{"aggregate":"distinct","aggregate_field":"active_day","threshold":7,"window":{"type":"natural_day_streak"}}',1,'0',6,now())
ON DUPLICATE KEY UPDATE
 asset_type=VALUES(asset_type), category=VALUES(category), trigger_event=VALUES(trigger_event),
 unlock_rule_json=VALUES(unlock_rule_json), rule_version=VALUES(rule_version);

-- 种子:L1/L2 新勋章定义(P0 暂不可获得,四层页锁定展示;授予在后续计划接入)。
INSERT INTO growth_badge (badge_code, badge_name, name_en, statement, icon_url, asset_type, category, trigger_event, unlock_rule_json, rule_version, status, sort_no, create_time)
VALUES
 ('ID_ROAM_FIRST','初次漫游','ROAM','第一次漫游这座城。','','IDENTITY','EXPLORE','roam_session_finished','{"aggregate":"exists","threshold":1}',1,'0',7,now()),
 ('GW_ROUTE_3','城市脚步·三','','','','GROWTH','EXPLORE','topic_complete','{"aggregate":"distinct","aggregate_field":"topicId","threshold":3}',1,'0',8,now()),
 ('GW_ROUTE_10','城市脚步·十','','','','GROWTH','EXPLORE','topic_complete','{"aggregate":"distinct","aggregate_field":"topicId","threshold":10}',1,'0',9,now())
ON DUPLICATE KEY UPDATE asset_type=VALUES(asset_type);
