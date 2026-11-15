-- R1 marketplace (2026-11-15): the city's market on the roam map, without selling rank inside the fog.
--   marketplace_deal        admin opt-in list: which merchant coupons appear in the players' opt-in deals layer.
--                           A coupon is NOT public until an operator adds it here (play-reward coupons stay rewards).
--   marketplace_deal_claim  one claim per member per offer (unique key). Stock is still enforced by
--                           sms_coupon.receive_count through the existing atomic issue path.
--   admin menu              城市集市: deals opt-in, promotion-slot purchases awaiting a refund decision, sales leads.
--
-- Rollback (nothing here changes existing tables or rows):
--   DELETE FROM sys_menu WHERE perms IN ('business:marketplace:list','business:marketplace:edit');
--   DROP TABLE IF EXISTS marketplace_deal_claim;
--   DROP TABLE IF EXISTS marketplace_deal;
-- Claimed coupons already sit in sms_coupon_history like any other granted coupon and stay valid after a rollback.

CREATE TABLE IF NOT EXISTS marketplace_deal (
  coupon_id   BIGINT      NOT NULL COMMENT 'sms_coupon.id (scope_type=1 merchant coupon)',
  enabled     TINYINT     NOT NULL DEFAULT 1 COMMENT '1 shown in the deals layer, 0 paused',
  sort        INT         NOT NULL DEFAULT 0,
  create_by   VARCHAR(64) NOT NULL DEFAULT '',
  create_time DATETIME    NOT NULL,
  update_by   VARCHAR(64) NOT NULL DEFAULT '',
  update_time DATETIME    NULL,
  PRIMARY KEY (coupon_id),
  KEY idx_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='城市集市公开优惠(后台逐张开启)';

CREATE TABLE IF NOT EXISTS marketplace_deal_claim (
  id          BIGINT   NOT NULL AUTO_INCREMENT,
  coupon_id   BIGINT   NOT NULL,
  member_id   BIGINT   NOT NULL,
  history_id  BIGINT   NULL COMMENT 'sms_coupon_history.id written in the same transaction',
  create_time DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_deal_member (coupon_id, member_id),
  KEY idx_member (member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='城市集市优惠领取(每人每券一次)';

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '城市集市',
       COALESCE((SELECT m.menu_id FROM (SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='merchants-coop' LIMIT 1) m), 0),
       60,'marketplace','business/marketplace/index',1,0,'C','0','0','business:marketplace:list','shopping','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:marketplace:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '城市集市维护',(SELECT m.menu_id FROM (SELECT menu_id FROM sys_menu WHERE perms='business:marketplace:list') m),1,'#','',1,0,'F','0','0','business:marketplace:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:marketplace:edit');

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
          AND table_name IN ('marketplace_deal','marketplace_deal_claim')) = 2
   AND (SELECT COUNT(*) FROM sys_menu WHERE perms IN ('business:marketplace:list','business:marketplace:edit')) = 2 AS assertion_ok;
