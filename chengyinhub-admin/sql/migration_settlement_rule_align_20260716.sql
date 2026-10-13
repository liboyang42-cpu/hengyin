-- migration_settlement_rule_align_20260716.sql
--
-- 【背景】P0-28 抽成收敛:结算主路径(settleActivity/settleTopic 存量分支)从读 cms_setting
--   百分数费率列,改为读 settlement_rule 小数费率(单一真源)。本迁移把 settlement_rule 的
--   通用行(merchant_id=0)对齐到 cms_setting id=1 的现值,使「切读真源」= 零行为变化。
--   ⚠️ 必须先于新 jar 部署落库:否则新代码读到旧 settlement_rule 值(activity creator=0.00)
--   会把设计者 10% 静默算成 0%。生产 settlement_detail/结算产出当前全 0,窗口无实害,但顺序仍须遵守。
--
--   对齐依据(cms_setting id=1 现值,2026-07-16 只读实测):
--     activity: 平台10 / 商家80 / 设计者10(百分)  → settlement_rule 0.10 / 0.80 / 0.10
--     topic:    平台10 / 商家70 / 发布者20 / 邀请2(占商家) → settlement_rule 0.10 / 0.70 / 0.20 / inviter 0.02
--   ★ topic 的 inviter_rate=0.02 语义是「占商家分润的比例」(存量主题路 inviter 从商家份额内抠),
--     不是「占 gross 的比例」;故 topic 四率之和 1.02>1.0 属**预期**(inviter 是商家份额的再切分,
--     非 gross 分区的加项),saveRule 的 requireSumWithinOne 不适用于该行——该行由系统管理、后台不编辑。
--
-- 【幂等】UPDATE 写绝对值,重复执行结果一致;WHERE 精确锁定通用启用行。
-- 【可逆】回滚:activity 恢复 merchant=0.9000/creator=0.0000;
--         topic 恢复 merchant=0.8000/creator=0.1000/inviter=0.0000(见迁移前实测值)。
-- 【风险】仅改 2 行费率配置,不动表结构、不动余额、不动订单;MySQL 8 普通 UPDATE,秒级。

UPDATE settlement_rule
SET merchant_rate = 0.8000,
    creator_rate  = 0.1000
WHERE merchant_id = 0 AND biz_type = 'activity' AND status = 1 AND del_flag = 0;

UPDATE settlement_rule
SET merchant_rate = 0.7000,
    creator_rate  = 0.2000,
    inviter_rate  = 0.0200
WHERE merchant_id = 0 AND biz_type = 'topic' AND status = 1 AND del_flag = 0;

-- 回读断言:应看到 activity=0.10/0.80/0.10/0.00、topic=0.10/0.70/0.20/0.02
SELECT biz_type, platform_commission_rate, merchant_rate, creator_rate, inviter_rate
FROM settlement_rule
WHERE merchant_id = 0 AND biz_type IN ('activity', 'topic') AND status = 1 AND del_flag = 0
ORDER BY biz_type;
