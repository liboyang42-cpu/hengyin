-- migration_d7_topic_rate_20260716.sql
--
-- 【背景】P0-28 拍板2(D7):主题平台抽成 10% → 3%,按 biz_type 分行,仅改 topic 那行,
--   coupon/activity 各留自己的行不动。⚠️ 必须在 migration_settlement_rule_align_20260716.sql
--   之后执行(该迁移先把 topic 行对齐成 0.10/0.70/0.20/0.02,本迁移再把平台率降到 0.03)。
--
--   效果分两条路(2026-07-16 实测:coop_order=0、cms_topic 17 题全 lifecycle=0,均休眠):
--     · 存量主题 settleTopic:平台抽成是**幻影**(算了不出账,由平台"永不派发"隐式留存),
--       故 D7 在这条路上不改任何打款;副作用是 validateCommissionDistribution 的差额告警会因
--       3+70+20=93 而触发(纯日志,休眠路径无害)。
--     · 支付回调 SettlementEntry(系统2,向前活路径,当前无新单):平台份额 = gross×0.03,
--       商家残值 = gross − 0.03 − 发布者0.20 = 0.77 → 平台 10→3、商家残值 70→77(7% 归商家)。
--       coop 分支 platformRateOf 快照回落也降到 3%。
--
-- 【幂等】UPDATE 写绝对值,重复执行结果一致。
-- 【可逆】回滚:UPDATE ... SET platform_commission_rate=0.1000 WHERE 同条件。
-- 【风险】仅改 1 行 topic 平台率,不动表结构/余额/订单;MySQL 8 普通 UPDATE,秒级。
--   ⚠️ requireSumWithinOne 只在 saveRule 生效,直接 SQL UPDATE 完全绕过,不是安全网(见对账)。

UPDATE settlement_rule
SET platform_commission_rate = 0.0300
WHERE merchant_id = 0 AND biz_type = 'topic' AND status = 1 AND del_flag = 0;

-- 回读断言:应看到 topic 平台率=0.0300(其余 0.70/0.20/0.02 不变)
SELECT biz_type, platform_commission_rate, merchant_rate, creator_rate, inviter_rate
FROM settlement_rule
WHERE merchant_id = 0 AND biz_type = 'topic' AND status = 1 AND del_flag = 0;
