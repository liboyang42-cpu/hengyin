-- DOC2 FXE · 资金链只读对账查询(O-07 + F3 Phase 2 设计配套)· 2026-10-23
--
-- 【性质】只读。每条都是单个 SELECT / WITH ... SELECT,不建临时表、不写任何表、不加锁(无 FOR UPDATE)。
--         FundsChainO07PinMySqlIT 会逐条在真 MySQL 8 + 真 DDL 上执行本文件,并断言:
--           ① 每条语句以 SELECT / WITH 开头(只读守卫);② 列名与真表一致(能跑通);
--           ③ 关键条目对 master 当前行为造出的不一致场景确实命中。
-- 【怎么跑】只在只读副本 / 本地还原库上跑。不要连生产主库;生产执行走 chengyin-db-migrate 的只读预检流程并经用户确认。
-- 【格式】每条以 `-- @query Rxx` 开头,后跟一行标题;返回的每一行就是一条待人工核查的差异。返回 0 行 = 该项一致。
-- 【时间口径】批次归属与 MerchantPayableMapper.sumNetByMerchantAndPeriod 同源:date_format(create_time,'%Y-%m'),
--           即 MySQL 会话时区。不要改成 Java 侧算月。

-- @query R01
-- 存量 merchant_payable 行没有 claim_id(DDL 注释「存量待历史裁决;新写必填」;冲正会把 NULL 复制下去)。
SELECT p.id, p.entry_type, p.redemption_id, p.merchant_member_id, p.amount, p.create_time
FROM merchant_payable p
WHERE p.claim_id IS NULL
ORDER BY p.id;

-- @query R02
-- 有「计酬档」claim(route=CHAPTER_OFFER)却没有任何 ACCRUAL 应付:claim 在 isPlatformOwned 闸之前就创建
-- (MerchantPayableServiceImpl:113 vs :130),非自营主题的计酬核销会留下一条「欠钱凭据」但账上没钱。
SELECT c.id AS claim_id, c.commercial_context_id AS topic_id, c.fulfillment_id AS redemption_id,
       c.beneficiary_id AS merchant_member_id, c.source_contract_id AS offer_id, c.create_time
FROM settlement_claim c
WHERE c.fulfillment_type = 'REDEMPTION'
  AND c.settlement_route = 'CHAPTER_OFFER'
  AND NOT EXISTS (SELECT 1 FROM merchant_payable p
                  WHERE p.claim_id = c.id AND p.entry_type = 'ACCRUAL')
ORDER BY c.id;

-- @query R03
-- ACCRUAL 行挂的 claim 与行本身对不上(claim 不存在 / 不是 REDEMPTION / 履约 id、商家、路线不一致)。
SELECT p.id AS payable_id, p.claim_id, p.redemption_id, p.merchant_member_id,
       c.fulfillment_type, c.fulfillment_id, c.beneficiary_role, c.beneficiary_id, c.settlement_route
FROM merchant_payable p
LEFT JOIN settlement_claim c ON c.id = p.claim_id
WHERE p.claim_id IS NOT NULL
  AND p.entry_type = 'ACCRUAL'
  AND (c.id IS NULL
       OR c.fulfillment_type <> 'REDEMPTION'
       OR c.fulfillment_id <> p.redemption_id
       OR c.beneficiary_role <> 'MERCHANT'
       OR c.beneficiary_id <> p.merchant_member_id
       OR c.settlement_route <> 'CHAPTER_OFFER')
ORDER BY p.id;

-- @query R04
-- 核销已撤销,但 claim 仍是 ACTIVE(全仓没有任何把 settlement_claim.status 改离 ACTIVE 的写路径)。
-- 商家台 selectClaimSummary 按 status 计数 ⇒ 撤销过的核销仍被算成生效 claim。
SELECT c.id AS claim_id, c.fulfillment_id AS redemption_id, c.beneficiary_id AS merchant_member_id,
       c.settlement_route, r.status AS redemption_status, r.reversed_at
FROM settlement_claim c
JOIN redemption r ON r.id = c.fulfillment_id
WHERE c.fulfillment_type = 'REDEMPTION'
  AND c.status = 'ACTIVE'
  AND r.status = 'REVERSED'
ORDER BY c.id;

-- @query R05
-- 应付与核销状态不成对:已撤销核销有 ACCRUAL 无 REVERSAL(冲正漏记),或生效核销却已有 REVERSAL(冲早了)。
SELECT r.id AS redemption_id, r.status AS redemption_status,
       SUM(p.entry_type = 'ACCRUAL') AS accruals, SUM(p.entry_type = 'REVERSAL') AS reversals
FROM redemption r
JOIN merchant_payable p ON p.redemption_id = r.id
GROUP BY r.id, r.status
HAVING (r.status = 'REVERSED' AND SUM(p.entry_type = 'ACCRUAL') <> SUM(p.entry_type = 'REVERSAL'))
    OR (r.status = 'ACTIVE' AND SUM(p.entry_type = 'REVERSAL') > 0)
    OR SUM(p.entry_type = 'ACCRUAL') > 1
ORDER BY r.id;

-- @query R06
-- 生效的平台采购计酬核销(REVSHARE、非俱乐部直付、有章节)没有 ACCRUAL —— 商家真核销、平台没记账。
-- 已知合法来源只有一个:主题非自营(会在日志打 warn,见 MerchantPayableServiceImpl:130-135)。逐条人工裁决。
SELECT r.id AS redemption_id, r.registration_id, r.topic_id, r.chapter_id, r.merchant_id, o.id AS offer_id,
       o.per_head_fee, r.create_time
FROM redemption r
JOIN merchant_chapter_offer o ON o.id = r.offer_id
WHERE r.status = 'ACTIVE'
  AND r.chapter_id IS NOT NULL AND r.chapter_id <> 0
  AND o.terms_mode = 'REVSHARE'
  AND o.payer_club_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM merchant_payable p
                  WHERE p.redemption_id = r.id AND p.entry_type = 'ACCRUAL')
ORDER BY r.id;

-- @query R07
-- 已退款报名(payment_status=4)仍挂着净额为正的商家应付:退款审批只冲 settlement_detail,
-- 从不碰 merchant_payable(RefundServiceImpl:642-669);③ 已核销单经后台人工审批可全额退(RefundPolicy:136-138)。
-- 这是「玩家拿回钱 + 平台仍欠商家」的组合,是否合理需负责人裁决(见 FXE.md §5 D-3)。
SELECT g.id AS registration_id, g.member_id, g.payable_amount, g.wechat_payment_amount,
       SUM(CASE p.entry_type WHEN 'ACCRUAL' THEN p.amount ELSE -p.amount END) AS payable_net,
       (SELECT COALESCE(SUM(a.refund_amount), 0) FROM refund_application a
         WHERE a.source_type = 'registration' AND a.source_id = g.id AND a.status = 1) AS refunded_amount
FROM cms_registration g
JOIN redemption r ON r.registration_id = g.id
JOIN merchant_payable p ON p.redemption_id = r.id
WHERE g.payment_status = 4
GROUP BY g.id, g.member_id, g.payable_amount, g.wechat_payment_amount
HAVING payable_net > 0
ORDER BY g.id;

-- @query R08
-- 批次冻结额 ≠ 当前台账净额 − 已执行的冲抵倒扣。
-- FourLedgerReconcileServiceImpl:122-143 只比「冻结额 vs 台账净额」,会把每笔合法的 OFFSET_BATCH 倒扣
-- (deductForClawback 直接改 amount_total)都报成差异;本条把倒扣扣掉后再比,剩下的才是真漂移。
WITH net AS (
    SELECT merchant_member_id, date_format(create_time, '%Y-%m') AS period_ym,
           SUM(CASE entry_type WHEN 'ACCRUAL' THEN amount ELSE -amount END) AS ledger_net
    FROM merchant_payable
    GROUP BY merchant_member_id, date_format(create_time, '%Y-%m')
), offset_clawback AS (
    SELECT target_batch_id, SUM(amount) AS clawback_total
    FROM merchant_clawback_application
    WHERE status = 'EXECUTED' AND settle_path = 'OFFSET_BATCH'
    GROUP BY target_batch_id
)
SELECT b.id AS batch_id, b.merchant_member_id, b.period_ym, b.status, b.amount_total,
       COALESCE(n.ledger_net, 0) AS ledger_net, COALESCE(oc.clawback_total, 0) AS clawback_total,
       COALESCE(n.ledger_net, 0) - COALESCE(oc.clawback_total, 0) - b.amount_total AS drift
FROM merchant_payable_batch b
LEFT JOIN net n ON n.merchant_member_id = b.merchant_member_id AND n.period_ym = b.period_ym
LEFT JOIN offset_clawback oc ON oc.target_batch_id = b.id
WHERE ABS(COALESCE(n.ledger_net, 0) - COALESCE(oc.clawback_total, 0) - b.amount_total) > 0.01
ORDER BY b.period_ym, b.merchant_member_id;

-- @query R09
-- 孤儿应付:某商家某个已过完的月份有台账行,但该月已经开过批(别家有批次)而它没有 —— 永远不会被打款。
-- 典型成因:批次生成后才提交的跨月事务;JVM 与 MySQL 时区对月边界判断不一致。
SELECT p.merchant_member_id, date_format(p.create_time, '%Y-%m') AS period_ym, COUNT(*) AS row_count,
       SUM(CASE p.entry_type WHEN 'ACCRUAL' THEN p.amount ELSE -p.amount END) AS ledger_net
FROM merchant_payable p
WHERE date_format(p.create_time, '%Y-%m') < date_format(CURRENT_DATE, '%Y-%m')
  AND EXISTS (SELECT 1 FROM merchant_payable_batch x WHERE x.period_ym = date_format(p.create_time, '%Y-%m'))
  AND NOT EXISTS (SELECT 1 FROM merchant_payable_batch b
                  WHERE b.merchant_member_id = p.merchant_member_id
                    AND b.period_ym = date_format(p.create_time, '%Y-%m'))
GROUP BY p.merchant_member_id, date_format(p.create_time, '%Y-%m')
ORDER BY period_ym, p.merchant_member_id;

-- @query R10
-- 非正额批次卡死:amount_total <= 0 的批次 confirm/markPaid 都过不了(SQL 与 requireProgressionAllowed 双闸),
-- 也没有任何「结转到下一期」的机制 ⇒ 若之后的批次照常 PAID,这笔负额(商家反欠平台)就永久丢失。
SELECT b.id AS batch_id, b.merchant_member_id, b.period_ym, b.amount_total, b.status,
       (SELECT COUNT(*) FROM merchant_payable_batch later
         WHERE later.merchant_member_id = b.merchant_member_id
           AND later.period_ym > b.period_ym AND later.status = 'PAID') AS later_paid_batches
FROM merchant_payable_batch b
WHERE b.amount_total <= 0 AND b.status = 'PENDING'
ORDER BY b.period_ym, b.merchant_member_id;

-- @query R11
-- 冲正命中「已开蓝票」批次却没回填红票号:代码只打 warn(MerchantPayableServiceImpl:225-232),没有待办落库。
SELECT rev.id AS reversal_id, rev.redemption_id, acc.merchant_member_id,
       date_format(acc.create_time, '%Y-%m') AS accrual_period, b.id AS batch_id, b.invoice_status,
       b.invoice_no, b.red_invoice_no, rev.amount
FROM merchant_payable rev
JOIN merchant_payable acc ON acc.redemption_id = rev.redemption_id AND acc.entry_type = 'ACCRUAL'
JOIN merchant_payable_batch b ON b.merchant_member_id = acc.merchant_member_id
                             AND b.period_ym = date_format(acc.create_time, '%Y-%m')
WHERE rev.entry_type = 'REVERSAL'
  AND b.invoice_status = 'ISSUED'
  AND b.red_invoice_no IS NULL
ORDER BY rev.id;

-- @query R12
-- 俱乐部直付应收与核销状态不成对(撤销后仍 OPEN/SETTLED,或核销生效却已 VOID/REFUND_DUE/REFUNDED)。
SELECT d.id AS receivable_id, d.redemption_id, d.status AS receivable_status, r.status AS redemption_status,
       d.payer_club_id, d.merchant_member_id, d.per_head_fee_snapshot
FROM club_direct_merchant_receivable d
JOIN redemption r ON r.id = d.redemption_id
WHERE (r.status = 'REVERSED' AND d.status IN ('OPEN', 'SETTLED'))
   OR (r.status = 'ACTIVE' AND d.status IN ('VOID', 'REFUND_DUE', 'REFUNDED'))
ORDER BY d.id;

-- @query R13
-- 同一核销同时进了平台应付和俱乐部直付应收(双付)。
SELECT d.redemption_id, d.payer_club_id, d.merchant_member_id
FROM club_direct_merchant_receivable d
WHERE EXISTS (SELECT 1 FROM merchant_payable p WHERE p.redemption_id = d.redemption_id)
ORDER BY d.redemption_id;

-- @query R14
-- F3 套票单位与核销流水不成对:单位 REDEEMED 但流水不是 ACTIVE;或 ACTIVE 流水命中某单位但单位不是 REDEEMED。
SELECT u.id AS unit_id, u.unit_code, u.status AS unit_status, u.redemption_id, r.status AS redemption_status
FROM explore_bundle_unit u
LEFT JOIN redemption r ON r.id = u.redemption_id
WHERE (u.status = 'REDEEMED' AND (r.id IS NULL OR r.status <> 'ACTIVE'))
   OR (u.status <> 'REDEEMED' AND u.redemption_id IS NOT NULL)
ORDER BY u.id;

-- @query R15
-- F3 套票:进入 REFUND_PENDING(商家退出,持有人选择退未履约部分)但该报名没有任何退款单 ——
-- Phase 1 只把单位置 REFUND_PENDING,资金动作走「既有人工售后」,没有落库的待办。
SELECT u.registration_id, COUNT(*) AS pending_units, SUM(u.unit_value) AS pending_value
FROM explore_bundle_unit u
WHERE u.status = 'REFUND_PENDING'
  AND NOT EXISTS (SELECT 1 FROM refund_application a
                  WHERE a.source_type = 'registration' AND a.source_id = u.registration_id AND a.status IN (0, 1))
GROUP BY u.registration_id
ORDER BY u.registration_id;

-- @query R16
-- 退款审批通过但钱没出去:payout_status 0 处理中 / 2 原路失败待人工,超过 24 小时。
SELECT a.id AS refund_id, a.source_type, a.source_id, a.refund_amount, a.payout_status,
       a.payout_submission_state, a.reviewed_at
FROM refund_application a
WHERE a.status = 1
  AND a.payout_status IN (0, 2)
  AND a.reviewed_at < NOW() - INTERVAL 24 HOUR
ORDER BY a.reviewed_at;

-- @query R17
-- 退款累计超过实付(按报名):已批准退款额之和 > 实付快照。
SELECT g.id AS registration_id, g.payable_amount, SUM(a.refund_amount) AS approved_refund_total
FROM cms_registration g
JOIN refund_application a ON a.source_type = 'registration' AND a.source_id = g.id AND a.status = 1
GROUP BY g.id, g.payable_amount
HAVING SUM(a.refund_amount) > COALESCE(g.payable_amount, 0)
ORDER BY g.id;

-- @query R18
-- 购物车结算(积分商城)扣了积分却没有积分流水:UmsMemberCartServiceImpl:285-295 直接 deductPoints,
-- 不写 ums_member_points_detail、不带订单 id ⇒ 积分账与订单无法逐笔回读。返回的就是没有流水可对的订单。
-- 注意:settleCart 把 pay_type 写成 cart_type(0本地/1线上/2积分商城,:319),与列注释「0未支付 1积分支付」口径不一,
--       且三种 cart_type 一律扣积分 ⇒ 这里不能按 pay_type 过滤,只按「有金额的订单」找。
SELECT o.id AS order_id, o.order_sn, o.member_id, o.pay_type, o.pay_amount, o.create_time
FROM oms_order o
WHERE o.pay_amount > 0
  AND NOT EXISTS (SELECT 1 FROM ums_member_points_detail d
                  WHERE d.member_id = o.member_id AND d.event_id = o.id AND d.change_type = 2)
ORDER BY o.id;
