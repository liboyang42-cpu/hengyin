-- 多方分账 P1:coop_settlement 扩分项列。
-- 注意:MySQL 8 不支持 ADD COLUMN IF NOT EXISTS(那是 MariaDB 扩展),故用普通 ADD COLUMN;
-- 非幂等,执行前先跑 _precheck_migration_status.sql 确认 coop_settlement.line_type 为「待落」再执行。
-- line_type:PLATFORM/INITIATOR_GUARANTEE/INITIATOR_MARGIN/LEAD_FEE/MERCHANT_VERIFY
-- payee_type:platform/initiator/club/merchant(对账口径;收款人仍落 merchant_id=真实 member_id)
ALTER TABLE `coop_settlement`
  ADD COLUMN `line_type` varchar(24) DEFAULT 'MERCHANT_VERIFY' COMMENT '分项类型' AFTER `coop_order_id`,
  ADD COLUMN `payee_type` varchar(16) DEFAULT 'merchant' COMMENT '收款方类型:platform/initiator/club/merchant' AFTER `line_type`;
-- coop_order_id 平台/发起人行无对应合作单,须可空(原本可空则此句幂等无害)
ALTER TABLE `coop_settlement` MODIFY COLUMN `coop_order_id` bigint NULL COMMENT '合作单id(平台/发起人行为空)';
