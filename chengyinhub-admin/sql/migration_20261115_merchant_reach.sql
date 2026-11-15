-- Q1 merchant-reach (2026-11-15)
-- Rollback: DELETE FROM sys_config WHERE config_key = 'feature.flag.merchantNonMoney';
--           (no table or column is created; the weekly proof report and the CITY cohort read existing rows only)
--
-- One switch for merchant non-money writes from the app: counter redemption (including the scan camera), shop profile,
-- map node save/claim/offline, staff, review replies, CRM notes and aftercare. It also opens the counter desk
-- (/api/merchant/desk/*) the same way merchant.desk.f4.enabled does. Products and prices stay behind
-- feature.flag.merchantDeskProducts; payment, payout, withdrawal and refunds stay behind the money switch.
-- Default OFF. The owner turns it on at deploy by setting config_value = 'true'.

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '功能开关-商家非资金写入', 'feature.flag.merchantNonMoney', 'false', 'N', 'admin', NOW(),
       'Q1 merchant-reach:App 商家核销/资料/据点/店员/回复/CRM/售后(不含任何资金写入),默认关'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'feature.flag.merchantNonMoney');
