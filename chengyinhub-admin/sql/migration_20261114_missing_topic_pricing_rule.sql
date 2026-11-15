-- 补齐空测试库缺失的默认主题费率；已有规则、停用规则与所有冻结快照均不覆盖。
-- 口径沿用 migration_20261103_settlement_rule_three_percent.sql：平台3%，主办方97%。
-- 不用于重设已存在规则；生产执行须独立预检与授权。

-- runner: assert=scalar-true
SELECT COUNT(*) <= 1 AS topic_rule_unambiguous
FROM settlement_rule WHERE merchant_id = 0 AND biz_type = 'topic' AND del_flag = 0;

INSERT INTO settlement_rule
 (merchant_id, biz_type, rule_name, settlement_cycle,
  platform_commission_rate, merchant_rate, creator_rate, inviter_rate,
  min_settlement_amount, refund_deadline_hours, allow_refund_after_verified, auto_approve_refund,
  guarantee_base_rate, guarantee_step_rate, guarantee_max_merchant, status, del_flag, create_time)
SELECT 0, 'topic', '平台默认主题结算规则', 'monthly',
       0.0300, 0.9700, 0.0000, 0.0000, 100.00, 24, 0, 0,
       0.0500, 0.0200, 5, 1, 0, NOW()
WHERE NOT EXISTS (SELECT 1 FROM settlement_rule WHERE merchant_id = 0 AND biz_type = 'topic');

-- runner: assert=scalar-true
SELECT COUNT(*) = 1 AS topic_pricing_rule_ready
FROM settlement_rule WHERE merchant_id = 0 AND biz_type = 'topic' AND status = 1 AND del_flag = 0
 AND platform_commission_rate IS NOT NULL AND guarantee_base_rate IS NOT NULL
 AND guarantee_step_rate IS NOT NULL AND guarantee_max_merchant IS NOT NULL;
