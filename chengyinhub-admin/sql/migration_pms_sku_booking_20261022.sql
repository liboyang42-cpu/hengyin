-- Doc2 F4(2026-10-22):商家 SKU 可预约容量模型。只新建两张表,不改 pms_product / pms_sku、不回填数据。
--   pms_sku_booking_rule   每个 SKU 一行:是否可预约、单时段分钟数、每时段容量。商家工作台(小程序)可写,owner 校验到自己门店。
--   pms_sku_capacity_slot  按时段展开的容量。**由 App/后台批量生成**(本 PR 不含生成逻辑),小程序只读结果。
-- 本 PR 不实现预约占用/释放:capacity_reserved 只由后续批次的预约链路写(见 survey C5-1,预约模型未批准)。
-- 读写方:MerchantDeskServiceImpl(chengyinhub-system)。仅在 sys_config['merchant.desk.f4.enabled']=true 时读写。
-- 幂等:表不存在则建;已存在则核对列 fingerprint,不一致时 guard 主键冲突让执行器报错,不改不删。
-- ⚠️ 生产执行前必须走 chengyin-db-migrate 的只读预检 + owner 确认,本 PR 不在任何库上执行。
-- 回滚(确认开关为 false 后):DROP TABLE pms_sku_capacity_slot; DROP TABLE pms_sku_booking_rule;
DROP TEMPORARY TABLE IF EXISTS pms_sku_booking_guard;
CREATE TEMPORARY TABLE pms_sku_booking_guard(id TINYINT PRIMARY KEY);
INSERT INTO pms_sku_booking_guard VALUES(1);

CREATE TABLE IF NOT EXISTS pms_sku_booking_rule (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  sku_id BIGINT NOT NULL COMMENT 'pms_sku.id',
  product_id BIGINT NOT NULL COMMENT 'pms_product.id(冗余,归属校验用)',
  merchant_id BIGINT NOT NULL COMMENT 'mms_merchant.id(同 pms_product.merchant_id 口径)',
  bookable TINYINT NOT NULL DEFAULT 0 COMMENT '1=可预约 0=不可预约',
  slot_minutes INT NOT NULL DEFAULT 60 COMMENT '单时段分钟数 15..480',
  capacity_per_slot INT NOT NULL DEFAULT 0 COMMENT '每时段可接待人数 0..1000',
  version INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
  updated_by BIGINT NOT NULL COMMENT '最后修改人 memberId',
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_pms_sku_booking_rule_sku (sku_id),
  KEY idx_pms_sku_booking_rule_merchant (merchant_id, product_id),
  CONSTRAINT ck_pms_sku_booking_rule_range CHECK (bookable IN (0, 1) AND slot_minutes BETWEEN 15 AND 480
    AND capacity_per_slot BETWEEN 0 AND 1000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Doc2 F4 SKU 预约规则(商家可写)';

CREATE TABLE IF NOT EXISTS pms_sku_capacity_slot (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  sku_id BIGINT NOT NULL COMMENT 'pms_sku.id',
  product_id BIGINT NOT NULL COMMENT 'pms_product.id',
  merchant_id BIGINT NOT NULL COMMENT 'mms_merchant.id',
  slot_start DATETIME NOT NULL COMMENT '时段开始(Asia/Shanghai)',
  slot_end DATETIME NOT NULL COMMENT '时段结束',
  capacity_total INT NOT NULL COMMENT '本时段容量(生成时按规则快照)',
  capacity_reserved INT NOT NULL DEFAULT 0 COMMENT '已占用(预约链路写,本 PR 不写)',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '1开放 0关闭',
  source_batch VARCHAR(64) NULL COMMENT '生成批次号(App/后台批量生成)',
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_pms_sku_capacity_slot (sku_id, slot_start),
  KEY idx_pms_sku_capacity_slot_merchant (merchant_id, slot_start),
  CONSTRAINT ck_pms_sku_capacity_slot_range CHECK (slot_end > slot_start AND capacity_total >= 0
    AND capacity_reserved >= 0 AND capacity_reserved <= capacity_total AND status IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Doc2 F4 SKU 时段容量(App/后台生成,小程序只读)';

SET @pms_sku_booking_rule_columns := (SELECT GROUP_CONCAT(CONCAT_WS(':', column_name, column_type, is_nullable)
    ORDER BY ordinal_position SEPARATOR '|')
  FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'pms_sku_booking_rule');
INSERT INTO pms_sku_booking_guard
SELECT 1 FROM DUAL WHERE COALESCE(@pms_sku_booking_rule_columns, '') <> CONCAT(
  'id:bigint:NO|sku_id:bigint:NO|product_id:bigint:NO|merchant_id:bigint:NO|bookable:tinyint:NO',
  '|slot_minutes:int:NO|capacity_per_slot:int:NO|version:int:NO|updated_by:bigint:NO',
  '|created_at:datetime(3):NO|updated_at:datetime(3):NO');

SET @pms_sku_capacity_slot_columns := (SELECT GROUP_CONCAT(CONCAT_WS(':', column_name, column_type, is_nullable)
    ORDER BY ordinal_position SEPARATOR '|')
  FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'pms_sku_capacity_slot');
INSERT INTO pms_sku_booking_guard
SELECT 1 FROM DUAL WHERE COALESCE(@pms_sku_capacity_slot_columns, '') <> CONCAT(
  'id:bigint:NO|sku_id:bigint:NO|product_id:bigint:NO|merchant_id:bigint:NO|slot_start:datetime:NO',
  '|slot_end:datetime:NO|capacity_total:int:NO|capacity_reserved:int:NO|status:tinyint:NO',
  '|source_batch:varchar(64):YES|created_at:datetime(3):NO|updated_at:datetime(3):NO');
DROP TEMPORARY TABLE pms_sku_booking_guard;

-- runner: assert=scalar-true
SELECT SUM(table_name = 'pms_sku_booking_rule' AND index_name = 'uk_pms_sku_booking_rule_sku') = 1
   AND SUM(table_name = 'pms_sku_capacity_slot' AND index_name = 'uk_pms_sku_capacity_slot') = 2 AS assertion_ok
FROM information_schema.statistics
WHERE table_schema = DATABASE() AND table_name IN ('pms_sku_booking_rule', 'pms_sku_capacity_slot')
  AND non_unique = 0;
