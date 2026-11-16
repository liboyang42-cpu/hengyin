-- ================= migration_20261116_merchant_review_sla.sql =================
SELECT 'migration_20261116_merchant_review_sla.sql' AS running;
-- W7 x-merchant-tools MERCHANT-14:商家入驻审核时长的公示文案。
--   sys_config 种子(只在键缺失时插入,不覆盖运维已配置值):
--     merchant.onboarding.review-sla = ''   (留空则 App 不写具体时长;owner 确认承诺后在后台「参数设置」填入,如「1-3 个工作日」)
-- 读方:ApiConfigController /api/config/features(merchantReviewSla)。
-- 可重复执行。依赖:sys_config(RuoYi 基础表)。
-- ⚠️ 生产执行前必须走 chengyin-db-migrate 的只读预检 + owner 确认,本分支不在任何库上执行。
--
-- 回滚(仅在确认无读方后):
--   DELETE FROM sys_config WHERE config_key = 'merchant.onboarding.review-sla';
--
-- 落库前只读预检:
--   SELECT config_key, config_value FROM sys_config WHERE config_key = 'merchant.onboarding.review-sla';

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '商家入驻审核时长', 'merchant.onboarding.review-sla', '', 'Y', 'admin', NOW(),
       '公示给申请商家的审核时长原文,如「1-3 个工作日」;留空则 App 只说「审核结果会通过消息通知你」'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'merchant.onboarding.review-sla');
