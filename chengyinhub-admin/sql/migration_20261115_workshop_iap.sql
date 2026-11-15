-- 2026-11-15 R6c workshop-iap:工坊模板(DIGITAL_MOD)在 iOS 上用 App Store 内购(StoreKit 2,非消耗型)出售。
-- 新增三张表 + 运营后台菜单(business:modcommerce:query / :edit,不授予任何角色)。不改已有表结构、不写任何数据行。
-- 订单与许可仍落在 workshop_order(provider='APPLE_IAP'),所以创作者收益台账(creator_earning_ledger)的
-- SALE / SALE_REVERSAL 与已有的工坊许可、安装流程照常适用;本迁移只加「上架商品目录」与「App 订单状态」两张扩展表
-- 和一张 App Store Server Notifications V2 收件箱。
-- 前置:z_workshop_purchase_20261109、z_workshop_paid_review_20261109(workshop_version / workshop_order 与 Apple 列)、
--       20261115_money_switch(资金与售后菜单之下)、20261115_creator_earnings(台账)。
-- 可重复执行:CREATE TABLE IF NOT EXISTS;菜单行按 perms 判重。
--
-- 回滚(仅在没有 mod_commerce_order 行时;有行时先归档,它们是购买证据):
--   DELETE FROM sys_menu WHERE perms = 'business:modcommerce:edit';
--   DELETE FROM sys_menu WHERE perms = 'business:modcommerce:query';
--   DROP TABLE IF EXISTS mod_commerce_apple_notification;
--   DROP TABLE IF EXISTS mod_commerce_order;
--   DROP TABLE IF EXISTS mod_commerce_offer;
-- 本迁移不打开任何开关:接口还要 chengyin.mod-commerce.enabled=true 且资金开关 pass 区域打开才会应答。

CREATE TABLE IF NOT EXISTS mod_commerce_offer (
  id bigint NOT NULL AUTO_INCREMENT,
  offer_id varchar(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'MCO_ + 32 位十六进制,App 用它购买',
  version_id varchar(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'workshop_version.version_id(已审核的模板发布版本)',
  market char(2) CHARACTER SET ascii NOT NULL COMMENT 'CN / US',
  currency char(3) CHARACTER SET ascii NOT NULL COMMENT 'CN=CNY, US=USD',
  price_minor bigint NOT NULL COMMENT '分/美分;必须等于 App Store 商品的价格档位',
  product_id varchar(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'App Store Connect 非消耗型商品 ID',
  enabled tinyint NOT NULL DEFAULT 0 COMMENT '1 = 上架;新建默认下架',
  create_by varchar(64) NOT NULL DEFAULT '',
  create_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_by varchar(64) NOT NULL DEFAULT '',
  update_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_mod_commerce_offer_id (offer_id),
  UNIQUE KEY uk_mod_commerce_offer_product (product_id),
  KEY idx_mod_commerce_offer_version (version_id),
  CONSTRAINT fk_mod_commerce_offer_version FOREIGN KEY (version_id) REFERENCES workshop_version (version_id),
  CONSTRAINT ck_mod_commerce_offer_price CHECK (price_minor > 0 AND price_minor <= 2147483647),
  CONSTRAINT ck_mod_commerce_offer_market CHECK ((market = 'CN' AND currency = 'CNY') OR (market = 'US' AND currency = 'USD'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='R6c 工坊模板 App Store 商品目录(运营维护)';

CREATE TABLE IF NOT EXISTS mod_commerce_order (
  order_no varchar(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'workshop_order.order_no',
  member_id bigint NOT NULL,
  request_id varchar(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'App 生成的请求号(同一请求只建一单)',
  offer_id varchar(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  offer_json text NOT NULL COMMENT '建单时冻结的商品视图(价格、条款),之后不随目录变化',
  client_state varchar(20) CHARACTER SET ascii NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED / NATIVE_READY / SUBMITTING / CANCELLED',
  cancellation_requested tinyint NOT NULL DEFAULT 0,
  dispatched_at datetime(3) NULL COMMENT '服务端允许 App 调起 StoreKit 购买的时间(只允许一次)',
  create_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (order_no),
  UNIQUE KEY uk_mod_commerce_order_request (member_id, request_id),
  KEY idx_mod_commerce_order_offer (member_id, offer_id),
  CONSTRAINT fk_mod_commerce_order_workshop FOREIGN KEY (order_no) REFERENCES workshop_order (order_no),
  CONSTRAINT ck_mod_commerce_order_state CHECK (client_state IN ('CREATED', 'NATIVE_READY', 'SUBMITTING', 'CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='R6c App 工坊订单的 StoreKit 状态(付款与许可在 workshop_order)';

CREATE TABLE IF NOT EXISTS mod_commerce_apple_notification (
  notification_uuid char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  notification_type varchar(40) CHARACTER SET ascii NULL,
  subtype varchar(40) CHARACTER SET ascii NULL,
  transaction_id varchar(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
  payload_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  state varchar(16) CHARACTER SET ascii NOT NULL DEFAULT 'RECEIVED' COMMENT 'RECEIVED / PENDING / APPLIED / IGNORED',
  reason_code varchar(64) NULL,
  create_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (notification_uuid),
  KEY idx_mod_commerce_notification_txn (transaction_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='R6c App Store Server Notifications V2 收件箱(工坊模板)';

SET @modCommerceParentCount := (SELECT COUNT(1) FROM sys_menu WHERE menu_type = 'M' AND path = 'funds-aftercare');
SET @modCommerceParent := (SELECT MIN(menu_id) FROM sys_menu WHERE menu_type = 'M' AND path = 'funds-aftercare');
SET @modCommerceExisting := (SELECT COUNT(1) FROM sys_menu WHERE perms = 'business:modcommerce:query');
INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, `query`, route_name,
   is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '工坊内购商品', @modCommerceParent, 82, 'mod-commerce-offers', 'business/modcommerce/offers', '', 'ModCommerceOffers',
       1, 0, 'C', '0', '0', 'business:modcommerce:query', 'shopping', 'migration', NOW(),
       'R6c:把已审核的模板发布版本对应到 App Store 商品 ID、价格与币种'
FROM dual
WHERE @modCommerceParentCount = 1 AND @modCommerceExisting = 0;

SET @modCommerceMenu := (SELECT MIN(menu_id) FROM sys_menu WHERE perms = 'business:modcommerce:query' AND menu_type = 'C');
SET @modCommerceEditExisting := (SELECT COUNT(1) FROM sys_menu WHERE perms = 'business:modcommerce:edit');
INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, `query`, route_name,
   is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '工坊内购商品修改', @modCommerceMenu, 1, '', '', '', '',
       1, 0, 'F', '0', '0', 'business:modcommerce:edit', '#', 'migration', NOW(),
       'R6c:新建商品、上架或下架'
FROM dual
WHERE @modCommerceMenu IS NOT NULL AND @modCommerceEditExisting = 0;

-- runner: assert=scalar-true
SELECT (
  (SELECT COUNT(1) FROM information_schema.tables WHERE table_schema = DATABASE()
      AND table_name IN ('mod_commerce_offer', 'mod_commerce_order', 'mod_commerce_apple_notification')) = 3
  AND (SELECT COUNT(1) FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id = rm.menu_id
        WHERE m.perms IN ('business:modcommerce:query', 'business:modcommerce:edit')) = 0
) AS workshop_iap_ready;
