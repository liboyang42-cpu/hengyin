-- 平台抽成 3%(2026-11-03,walk-fix pay-gate Round 3D;业主口径「平台收 3%」)
--
-- 改什么:settlement_rule 的通用行(merchant_id = 0)
--   activity    platform_commission_rate 0.0300 / merchant_rate 0.9700 / creator_rate 0.0000
--   topic       platform_commission_rate 0.0300 / merchant_rate 0.9700 / creator_rate 0.0000
--   topic_club  同 topic —— 只在这一行已存在时才改,不存在不新建(俱乐部主题会回落 topic 行)
--
-- 读侧(谁吃这几行):
--   activity:CmsActivityServiceImpl.executeActivitySettlement 结算时现读;三项之和必须 = 1(validateActivityCommissionSettings)。
--             SettlementEntryServiceImpl 对无主题快照的场次票按它的平台率记明细。
--   topic / topic_club:PricingServiceImpl 新锁价的主题把平台率冻进 cms_topic.fee_platform_rate;
--             结算读那份快照(CmsTopicServiceImpl.settlementPlatformRate)。H8 要求 creator_rate = 0。
-- 不改什么:
--   · cms_topic.fee_platform_rate —— 已锁价的存量主题保留各自冻结的费率(业主口径),本文件一行都不碰;
--   · merchant_id <> 0 的商家专属规则、inviter_rate、保底三档、结算周期与退款窗口;
--   · 平台自有主题不收 3% 由代码判定(内容槽 PLATFORM 时结算平台率取 0),不靠这里的数据。
--
-- 可重跑:UPDATE 写绝对值,第二次执行结果相同。前置断言在改任何行之前停下:
--   activity / topic 的通用启用行必须各恰好 1 条,topic_club 至多 1 条(多条时 selectActiveRule 只取最高 id,改了也不知道谁生效)。
-- 回退:把三行改回执行前的值(执行前请先 SELECT 记下;生产 2026-08 时为 activity 0.10/0.90/0、topic 0.10/0.90/0)。

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM settlement_rule
        WHERE merchant_id = 0 AND biz_type = 'activity' AND status = 1 AND del_flag = 0) = 1
   AND (SELECT COUNT(*) FROM settlement_rule
        WHERE merchant_id = 0 AND biz_type = 'topic' AND status = 1 AND del_flag = 0) = 1
   AND (SELECT COUNT(*) FROM settlement_rule
        WHERE merchant_id = 0 AND biz_type = 'topic_club' AND status = 1 AND del_flag = 0) <= 1
   AS settlement_rule_generic_rows_unique;

UPDATE settlement_rule
SET platform_commission_rate = 0.0300,
    merchant_rate            = 0.9700,
    creator_rate             = 0.0000,
    update_time              = NOW()
WHERE merchant_id = 0
  AND biz_type IN ('activity', 'topic', 'topic_club')
  AND status = 1 AND del_flag = 0;

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM settlement_rule
        WHERE merchant_id = 0 AND biz_type IN ('activity', 'topic', 'topic_club')
          AND status = 1 AND del_flag = 0
          AND NOT (platform_commission_rate = 0.0300 AND merchant_rate = 0.9700 AND creator_rate = 0.0000)) = 0
   AND (SELECT COUNT(*) FROM settlement_rule
        WHERE merchant_id = 0 AND biz_type IN ('activity', 'topic')
          AND status = 1 AND del_flag = 0
          AND platform_commission_rate = 0.0300 AND merchant_rate = 0.9700 AND creator_rate = 0.0000) = 2
   AS settlement_rule_three_percent;
