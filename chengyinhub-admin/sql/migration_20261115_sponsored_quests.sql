-- S3b sponsored quests (2026-11-15): brand / city campaigns shown to players ONLY as quests labelled 「广告」 ("Ad")
-- inside the opt-in deals layer. Campaign rows are never read by the fog map, roam pin ranking or search.
--   sponsored_quest_campaign  one campaign: sponsor, quest content, budget, start/end, status (operations create it).
--   sponsored_quest_place     the places (published roam_poi rows) a campaign's quest visits.
--   sponsored_quest_charge    budget ledger. Rows are written ONLY while the money switch area merchantMoney is open
--                             (server ceiling + stored flag, both off by default and in production).
--   admin menu                赞助任务 under 商家合作, perms business:sponsoredQuest:list|edit|charge (granted to no role).
--
-- Rollback (nothing here changes existing tables or rows):
--   DELETE FROM sys_menu WHERE perms IN ('business:sponsoredQuest:list','business:sponsoredQuest:edit','business:sponsoredQuest:charge');
--   DROP TABLE IF EXISTS sponsored_quest_charge;
--   DROP TABLE IF EXISTS sponsored_quest_place;
--   DROP TABLE IF EXISTS sponsored_quest_campaign;

CREATE TABLE IF NOT EXISTS sponsored_quest_campaign (
  id                  BIGINT        NOT NULL AUTO_INCREMENT,
  sponsor_name        VARCHAR(64)   NOT NULL COMMENT '赞助方(玩家可见)',
  sponsor_merchant_id BIGINT        NULL     COMMENT 'mms_merchant.id when the sponsor is a merchant',
  title               VARCHAR(64)   NOT NULL COMMENT '任务标题',
  content             VARCHAR(1000) NOT NULL COMMENT '任务内容',
  cover_img           VARCHAR(500)  NULL,
  budget_cent         BIGINT        NOT NULL DEFAULT 0 COMMENT '预算(分);仅在资金开关打开时计费',
  spent_cent          BIGINT        NOT NULL DEFAULT 0 COMMENT '已计费(分);资金开关关闭时恒为 0',
  start_time          DATETIME      NOT NULL,
  end_time            DATETIME      NOT NULL,
  status              TINYINT       NOT NULL DEFAULT 0 COMMENT '0 草稿 1 投放中 2 已暂停 3 已结束',
  create_by           VARCHAR(64)   NOT NULL DEFAULT '',
  create_time         DATETIME      NOT NULL,
  update_by           VARCHAR(64)   NOT NULL DEFAULT '',
  update_time         DATETIME      NULL,
  PRIMARY KEY (id),
  KEY idx_live (status, start_time, end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='赞助任务(玩家侧始终标「广告」,不进迷雾/排序/搜索)';

CREATE TABLE IF NOT EXISTS sponsored_quest_place (
  campaign_id BIGINT NOT NULL,
  poi_id      BIGINT NOT NULL COMMENT 'roam_poi.id',
  sort        INT    NOT NULL DEFAULT 0,
  PRIMARY KEY (campaign_id, poi_id),
  KEY idx_poi (poi_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='赞助任务途经地点';

CREATE TABLE IF NOT EXISTS sponsored_quest_charge (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  campaign_id BIGINT       NOT NULL,
  ref_key     VARCHAR(80)  NOT NULL COMMENT 'idempotency key: a retried charge never bills twice',
  amount_cent BIGINT       NOT NULL,
  reason      VARCHAR(255) NOT NULL DEFAULT '',
  create_by   VARCHAR(64)  NOT NULL DEFAULT '',
  create_time DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ref (ref_key),
  KEY idx_campaign (campaign_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='赞助任务预算流水(仅资金开关打开时写入)';

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '赞助任务',
       COALESCE((SELECT m.menu_id FROM (SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='merchants-coop' LIMIT 1) m), 0),
       61,'sponsored-quests','business/sponsoredquest/index',1,0,'C','0','0','business:sponsoredQuest:list','guide','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:sponsoredQuest:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '赞助任务维护',(SELECT m.menu_id FROM (SELECT menu_id FROM sys_menu WHERE perms='business:sponsoredQuest:list') m),1,'#','',1,0,'F','0','0','business:sponsoredQuest:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:sponsoredQuest:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '赞助任务计费',(SELECT m.menu_id FROM (SELECT menu_id FROM sys_menu WHERE perms='business:sponsoredQuest:list') m),2,'#','',1,0,'F','0','0','business:sponsoredQuest:charge','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:sponsoredQuest:charge');

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
          AND table_name IN ('sponsored_quest_campaign','sponsored_quest_place','sponsored_quest_charge')) = 3
   AND (SELECT COUNT(*) FROM sys_menu WHERE perms IN ('business:sponsoredQuest:list','business:sponsoredQuest:edit','business:sponsoredQuest:charge')) = 3 AS assertion_ok;
