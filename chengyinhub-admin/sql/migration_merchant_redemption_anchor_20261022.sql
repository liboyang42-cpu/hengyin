-- Doc2 F4(2026-10-22):商家核销锚点配置(地图点 + 半径 + WiFi)。只新建表,不改既有表、不回填数据。
-- 读写方:MerchantDeskServiceImpl(chengyinhub-system)。仅在 sys_config['merchant.desk.f4.enabled']=true 时读写。
-- 本迁移只承载「配置」:核销链路(scan_qr_code / scan_qr_code_chapter / citynode / groupcode)本 PR 一行未改,
-- 不读本表、不因锚点拒绝任何核销。锚点用于工作台自检(/api/merchant/desk/anchor/check);是否接入核销拦截由 owner 另行决定。
-- 隐私:WiFi 只存 BSSID 规范化后的 SHA-256(十六进制,JSON 数组,最多 8 个),不存明文 BSSID / SSID。
-- 每家商家(mms_merchant.id)一行;version 做乐观锁。
-- 幂等:表不存在则建;已存在则核对列 fingerprint,不一致时 guard 主键冲突让执行器报错,不改不删。
-- ⚠️ 生产执行前必须走 chengyin-db-migrate 的只读预检 + owner 确认,本 PR 不在任何库上执行。
-- 回滚(确认开关为 false 后):DROP TABLE merchant_redemption_anchor;
DROP TEMPORARY TABLE IF EXISTS merchant_redemption_anchor_guard;
CREATE TEMPORARY TABLE merchant_redemption_anchor_guard(id TINYINT PRIMARY KEY);
INSERT INTO merchant_redemption_anchor_guard VALUES(1);

CREATE TABLE IF NOT EXISTS merchant_redemption_anchor (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  merchant_id BIGINT NOT NULL COMMENT 'mms_merchant.id(门店行主键,非 memberId)',
  owner_member_id BIGINT NOT NULL COMMENT '商家 Owner memberId(写入时的归属快照)',
  anchor_lat DECIMAL(10,7) NOT NULL COMMENT '锚点纬度(商家地图选点)',
  anchor_lng DECIMAL(10,7) NOT NULL COMMENT '锚点经度',
  radius_m INT NOT NULL COMMENT '核销半径(米) 30..500',
  wifi_bssid_hashes VARCHAR(600) NULL COMMENT 'BSSID SHA-256 JSON 数组,最多 8 个;NULL=未配置 WiFi',
  version INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
  updated_by BIGINT NOT NULL COMMENT '最后修改人 memberId',
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_merchant_redemption_anchor (merchant_id),
  CONSTRAINT ck_merchant_redemption_anchor_radius CHECK (radius_m BETWEEN 30 AND 500)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Doc2 F4 商家核销锚点配置(地图+半径+WiFi)';

SET @merchant_redemption_anchor_columns := (SELECT GROUP_CONCAT(CONCAT_WS(':', column_name, column_type, is_nullable)
    ORDER BY ordinal_position SEPARATOR '|')
  FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'merchant_redemption_anchor');
INSERT INTO merchant_redemption_anchor_guard
SELECT 1 FROM DUAL WHERE COALESCE(@merchant_redemption_anchor_columns, '') <> CONCAT(
  'id:bigint:NO|merchant_id:bigint:NO|owner_member_id:bigint:NO|anchor_lat:decimal(10,7):NO',
  '|anchor_lng:decimal(10,7):NO|radius_m:int:NO|wifi_bssid_hashes:varchar(600):YES|version:int:NO',
  '|updated_by:bigint:NO|created_at:datetime(3):NO|updated_at:datetime(3):NO');
DROP TEMPORARY TABLE merchant_redemption_anchor_guard;

-- runner: assert=scalar-true
SELECT COUNT(*) = 1 AS assertion_ok FROM information_schema.statistics
WHERE table_schema = DATABASE() AND table_name = 'merchant_redemption_anchor'
  AND index_name = 'uk_merchant_redemption_anchor' AND non_unique = 0;
