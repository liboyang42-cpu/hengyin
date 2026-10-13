-- W5 历史 settlement_claim 只读扫描。只产清单,绝不 UPDATE/INSERT。
-- 输出 A = 无歧义候选;输出 B = 冲突/证据不足,必须冻结后续付款并人工裁决。

-- A1:只有 REVSHARE 章节应付的履约/受益人/offer 与金额方向一致,可自动构造付费 claim。
SELECT 'AUTO_CHAPTER_PAYABLE' AS decision,
       p.id AS ledger_id, 'TOPIC_EDITION' AS commercial_context_type, r.topic_id AS commercial_context_id,
       'REDEMPTION' AS fulfillment_type, r.id AS fulfillment_id,
       'MERCHANT' AS beneficiary_role, r.merchant_id AS beneficiary_id, 'CHAPTER_SUPPLY' AS service_unit,
       'CHAPTER_OFFER' AS settlement_route,
       'MERCHANT_CHAPTER_OFFER' AS source_contract_type, o.id AS source_contract_id
FROM merchant_payable p
JOIN redemption r ON r.id = p.redemption_id
JOIN merchant_chapter_offer o ON o.id = r.offer_id
WHERE o.terms_mode = 'REVSHARE'
  AND r.topic_id IS NOT NULL AND r.merchant_id IS NOT NULL AND o.id IS NOT NULL;

-- A2:没有任何有效 coop_order 的直接报名商家分账,可按 registration 直接 owner 回填。
SELECT 'AUTO_DIRECT_REGISTRATION_SPLIT' AS decision,
       d.id AS ledger_id,
       CASE WHEN r.owner_type = 1 THEN 'TOPIC_EDITION' ELSE 'ACTIVITY' END AS commercial_context_type,
       r.owner_id AS commercial_context_id, 'REGISTRATION' AS fulfillment_type, r.id AS fulfillment_id,
       UPPER(d.receiver_type) AS beneficiary_role, d.receiver_id AS beneficiary_id,
       'MERCHANT_PARTICIPATION' AS service_unit, 'REGISTRATION_SPLIT' AS settlement_route,
       'DIRECT_REGISTRATION' AS source_contract_type, r.id AS source_contract_id
FROM settlement_detail d
JOIN cms_registration r ON r.id = d.source_id AND d.source_type = 'registration'
WHERE d.receiver_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM coop_order co
      WHERE r.owner_type = 1 AND co.topic_id = r.owner_id AND co.status = 0 AND co.del_flag = 0
  );

-- B1:同一 topic/merchant 同时存在直接报名分账和 coop 聚合头,可能跨轨双付。
-- verified_reg_ids 只能作为审计文本展示,不得据此自动拆 claim。
SELECT 'MANUAL_CROSS_ROUTE' AS decision,
       r.owner_id AS topic_id, d.receiver_id AS beneficiary_id,
       GROUP_CONCAT(DISTINCT r.id ORDER BY r.id) AS direct_registration_ids,
       GROUP_CONCAT(DISTINCT cs.id ORDER BY cs.id) AS coop_settlement_ids,
       GROUP_CONCAT(DISTINCT cs.verified_reg_ids SEPARATOR ' | ') AS untrusted_verified_reg_ids
FROM settlement_detail d
JOIN cms_registration r ON r.id = d.source_id AND d.source_type = 'registration' AND r.owner_type = 1
JOIN coop_settlement cs ON cs.topic_id = r.owner_id AND cs.merchant_id = d.receiver_id AND cs.del_flag = 0
GROUP BY r.owner_id, d.receiver_id;

-- B1.1:PERK/TRAFFIC 理应只有 NONE claim；若历史已有 payable，金额方向已冲突，禁止自动回填掩盖资损。
SELECT 'MANUAL_ZERO_CASH_HAS_PAYABLE' AS decision,
       p.id AS ledger_id, p.amount, r.id AS redemption_id, r.topic_id, r.merchant_id,
       o.id AS offer_id, o.terms_mode
FROM merchant_payable p
JOIN redemption r ON r.id = p.redemption_id
JOIN merchant_chapter_offer o ON o.id = r.offer_id
WHERE o.terms_mode IN ('PERK', 'TRAFFIC');

-- B2:每个存量 coop 聚合头逐条列证据强度。非 PROVEN_PER_REGISTRATION 全部人工裁决。
SELECT cs.id AS settlement_id, cs.coop_order_id, cs.topic_id, cs.merchant_id, cs.verified_reg_ids,
       CASE
         WHEN cs.coop_order_id IS NULL THEN 'MANUAL_MISSING_ORDER'
         WHEN cs.verified_reg_ids IS NULL OR cs.verified_reg_ids = '' THEN 'MANUAL_NO_REGISTRATION_TEXT'
         WHEN cs.line_type NOT IN ('MERCHANT_VERIFY', 'LEAD_FEE') THEN 'MANUAL_NON_ATOMIC_HEADER'
         ELSE 'MANUAL_TEXT_IS_NOT_PROOF'
       END AS decision
FROM coop_settlement cs
WHERE cs.del_flag = 0
ORDER BY cs.id;

-- B3:可逐条证明的旧原子账本按 claimKey 扫重复；route 不进分组。
-- coop_settlement 不参与逐 registration 展开：verified_reg_ids 不是证据，强行 JOIN 整个 topic 会制造假 claim。
SELECT commercial_context_type, commercial_context_id, fulfillment_type, fulfillment_id,
       beneficiary_role, beneficiary_id, service_unit,
       COUNT(*) AS ledger_rows, COUNT(DISTINCT settlement_route) AS route_count,
       GROUP_CONCAT(DISTINCT settlement_route ORDER BY settlement_route) AS routes
FROM (
    SELECT CASE WHEN r.owner_type=1 THEN 'TOPIC_EDITION' ELSE 'ACTIVITY' END commercial_context_type,
           r.owner_id commercial_context_id, 'REGISTRATION' fulfillment_type, r.id fulfillment_id,
           UPPER(d.receiver_type) beneficiary_role, d.receiver_id beneficiary_id,
           'MERCHANT_PARTICIPATION' service_unit, 'REGISTRATION_SPLIT' settlement_route
    FROM settlement_detail d JOIN cms_registration r ON d.source_type='registration' AND r.id=d.source_id
    UNION ALL
    SELECT 'TOPIC_EDITION', r.topic_id, 'REDEMPTION', r.id,
           'MERCHANT', r.merchant_id, 'CHAPTER_SUPPLY',
           CASE WHEN o.terms_mode='REVSHARE' THEN 'CHAPTER_OFFER' ELSE 'NONE' END
    FROM merchant_payable p
    JOIN redemption r ON r.id=p.redemption_id
    JOIN merchant_chapter_offer o ON o.id=r.offer_id
) historical_claim_candidates
GROUP BY commercial_context_type, commercial_context_id, fulfillment_type, fulfillment_id,
         beneficiary_role, beneficiary_id, service_unit
HAVING COUNT(*) > 1 OR COUNT(DISTINCT settlement_route) > 1;
