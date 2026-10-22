-- Doc2 F4(2026-10-22):商家工作台写操作回执表。只新建表,不改既有表、不回填数据。
-- 读写方:MerchantDeskServiceImpl(chengyinhub-system)。仅在 sys_config['merchant.desk.f4.enabled']=true 时读写;
-- 默认 false 时 /api/merchant/desk/* 全部直接返回 F4_DISABLED,不碰本表,所以新 jar 先于本迁移上线也不会报错。
-- 打开开关之前必须先落本迁移(以及同批的 anchor / sku booking / review evidence 三个迁移)。
-- 用途:每个写请求带客户端 requestId(幂等键)。同键同指纹 = 重放,直接读回已落地的目标;同键不同指纹 = 409;
-- 客户端超时(UNKNOWN)时用 /api/merchant/desk/receipt 按 requestId 读回,查不到 = 未落地,可用同键重试。
-- 本表只记「哪个写落到了哪个目标」,不存业务快照、不存金额。
-- 幂等:表不存在则建;已存在则核对列 fingerprint,不一致时 guard 主键冲突让执行器报错,不改不删。
-- ⚠️ 生产执行前必须走 chengyin-db-migrate 的只读预检 + owner 确认,本 PR 不在任何库上执行。
-- 回滚(确认开关为 false 后):DROP TABLE merchant_desk_receipt;
DROP TEMPORARY TABLE IF EXISTS merchant_desk_receipt_guard;
CREATE TEMPORARY TABLE merchant_desk_receipt_guard(id TINYINT PRIMARY KEY);
INSERT INTO merchant_desk_receipt_guard VALUES(1);

CREATE TABLE IF NOT EXISTS merchant_desk_receipt (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  owner_member_id BIGINT NOT NULL COMMENT '商家 Owner memberId(归属口径同 redemption.merchant_id)',
  request_id VARCHAR(64) NOT NULL COMMENT '客户端幂等键',
  action VARCHAR(40) NOT NULL COMMENT '写动作 PRODUCT_SAVE/PRODUCT_DELIST/SKU_SAVE/ANCHOR_SAVE/REVIEW_EVIDENCE',
  fingerprint CHAR(64) NOT NULL COMMENT '请求体规范化后的 SHA-256(十六进制)',
  target_type VARCHAR(32) NOT NULL COMMENT '落地目标类型 pms_product/pms_sku/merchant_redemption_anchor/verification_review_evidence',
  target_id BIGINT NOT NULL COMMENT '落地目标 id',
  actor_member_id BIGINT NOT NULL COMMENT '实际操作人 memberId(Owner 或员工)',
  created_at DATETIME(3) NOT NULL COMMENT '落地时间(服务端)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_merchant_desk_receipt (owner_member_id, request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Doc2 F4 商家工作台写操作幂等回执';

SET @merchant_desk_receipt_columns := (SELECT GROUP_CONCAT(CONCAT_WS(':', column_name, column_type, is_nullable)
    ORDER BY ordinal_position SEPARATOR '|')
  FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'merchant_desk_receipt');
INSERT INTO merchant_desk_receipt_guard
SELECT 1 FROM DUAL WHERE COALESCE(@merchant_desk_receipt_columns, '') <> CONCAT(
  'id:bigint:NO|owner_member_id:bigint:NO|request_id:varchar(64):NO|action:varchar(40):NO',
  '|fingerprint:char(64):NO|target_type:varchar(32):NO|target_id:bigint:NO|actor_member_id:bigint:NO',
  '|created_at:datetime(3):NO');
DROP TEMPORARY TABLE merchant_desk_receipt_guard;

-- runner: assert=scalar-true
SELECT COUNT(*) = 2 AS assertion_ok FROM information_schema.statistics
WHERE table_schema = DATABASE() AND table_name = 'merchant_desk_receipt'
  AND index_name = 'uk_merchant_desk_receipt' AND non_unique = 0;
