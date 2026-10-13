-- P1-3A 双审补充资金合同：订单费率快照、退款固定业务号与派发 fencing lease。
-- 只提交 migration 文件；生产执行必须另走 chengyin-db-migrate 预检、备份与回读。
--
-- 执行顺序即安全合同，别调换：
--   1) 幂等追加列（可重跑，纯结构、不改数据）
--   2) 前置 fail-closed guard —— 只读判定，在任何 UPDATE 之前。它红了整个迁移只落下第 1 步的
--      可空列，不留半应用的数据改写
--   3) 安全回填 —— 每条都能逐行自证、单向、可重跑
--   4) 回填后 fail-closed guard —— 只对「还会动钱」的残留变红
--   5) 唯一键修复 + 从 information_schema 回读
--   6) 末端回读 —— 残留一律显性计数，不靠人记得
--
-- ⚠️ 为什么第 2 步不能再带「payable>0 且 fee_platform_rate IS NULL」：列是本文件第 1 步刚建出来的，
--    真库里所有存量行此刻必然为 NULL ⇒ 那条判定在生产上恒真，迁移永远跑不完，而 ALTER 已经落下。
--    快照缺失的真实防线在运行时（SettlementEntryServiceImpl 对可付款报名缺快照直接抛），
--    迁移这里的职责是「先安全回填、再对不可推导的已支付单变红」。
--
-- ⚠️ 生产实测(2026-07-25，同 migration_p1_funds_core_uniqueness_20260724.sql 的坑)：
-- refund_application/cms_registration 是老库遗留表，collation 是 utf8mb4_general_ci，
-- 会话默认按 utf8mb4_0900_ai_ci 解析字面量 ⇒ 下面 TRIM(refund_no)=''、CONCAT('RF', source_id)、
-- source_type='registration' 这类「列 vs 字面量」比较会撞 1267。同样钉死连接 collation。
SET collation_connection = utf8mb4_general_ci;

SET @p1_fee_rate_column_exists := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'cms_registration'
    AND column_name = 'fee_platform_rate'
);
SET @p1_add_fee_rate_column_sql := IF(
  @p1_fee_rate_column_exists = 0,
  'ALTER TABLE cms_registration ADD COLUMN fee_platform_rate DECIMAL(10,4) NULL COMMENT ''下单冻结平台费率。NULL 有两种历史成因:①存量单(本列迁移前下的单),'
    '②源头主题从未配置过费率——两者读侧都需回落现取,别当成 bug'' AFTER wechat_payment_amount',
  'SELECT 1'
);
PREPARE p1_add_fee_rate_column_stmt FROM @p1_add_fee_rate_column_sql;
EXECUTE p1_add_fee_rate_column_stmt;
DEALLOCATE PREPARE p1_add_fee_rate_column_stmt;

SET @p1_payout_lease_column_exists := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'refund_application'
    AND column_name = 'payout_lease_token'
);
SET @p1_add_payout_lease_column_sql := IF(
  @p1_payout_lease_column_exists = 0,
  'ALTER TABLE refund_application ADD COLUMN payout_lease_token VARCHAR(64) NULL COMMENT ''退款派发 fencing token'' AFTER payout_remark',
  'SELECT 1'
);
PREPARE p1_add_payout_lease_column_stmt FROM @p1_add_payout_lease_column_sql;
EXECUTE p1_add_payout_lease_column_stmt;
DEALLOCATE PREPARE p1_add_payout_lease_column_stmt;

SET @p1_payout_owner_column_exists := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'refund_application'
    AND column_name = 'payout_lease_owner'
);
SET @p1_add_payout_owner_column_sql := IF(
  @p1_payout_owner_column_exists = 0,
  'ALTER TABLE refund_application ADD COLUMN payout_lease_owner BIGINT NULL COMMENT ''人工打款租约 owner'' AFTER payout_lease_token',
  'SELECT 1'
);
PREPARE p1_add_payout_owner_column_stmt FROM @p1_add_payout_owner_column_sql;
EXECUTE p1_add_payout_owner_column_stmt;
DEALLOCATE PREPARE p1_add_payout_owner_column_stmt;

SET @p1_payout_expiry_column_exists := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'refund_application'
    AND column_name = 'payout_lease_expires_at'
);
SET @p1_add_payout_expiry_column_sql := IF(
  @p1_payout_expiry_column_exists = 0,
  'ALTER TABLE refund_application ADD COLUMN payout_lease_expires_at DATETIME(3) NULL COMMENT ''人工打款租约到期时间'' AFTER payout_lease_owner',
  'SELECT 1'
);
PREPARE p1_add_payout_expiry_column_stmt FROM @p1_add_payout_expiry_column_sql;
EXECUTE p1_add_payout_expiry_column_stmt;
DEALLOCATE PREPARE p1_add_payout_expiry_column_stmt;

-- ── 前置 fail-closed guard（纯只读，在任何数据改写之前）────────────────────────
-- 临时主键冲突是 MySQL 8 全版本都可执行的 fail-closed guard，不用 CHECK（8.0.15 及更早会忽略）。
DROP TEMPORARY TABLE IF EXISTS p1_funds_spec_pre_guard;
CREATE TEMPORARY TABLE p1_funds_spec_pre_guard (
  id TINYINT NOT NULL,
  PRIMARY KEY (id)
);
INSERT INTO p1_funds_spec_pre_guard (id) VALUES (1);
INSERT INTO p1_funds_spec_pre_guard (id)
SELECT 1
FROM (
  SELECT 1
  FROM refund_application
  WHERE refund_no IS NOT NULL
    AND (TRIM(refund_no) = '' OR refund_no <> TRIM(refund_no) OR CHAR_LENGTH(refund_no) > 64)
  UNION ALL
  SELECT 1
  FROM (
    SELECT refund_no
    FROM refund_application
    WHERE refund_no IS NOT NULL
    GROUP BY refund_no
    HAVING COUNT(*) > 1
  ) duplicate_refund_no
  UNION ALL
  SELECT 1
  FROM refund_application
  WHERE source_type = 'registration'
    AND source_id IS NOT NULL
    AND refund_no IS NOT NULL
    AND refund_no <> CONCAT('RF', source_id)
  UNION ALL
  SELECT 1
  FROM cms_registration
  WHERE fee_platform_rate IS NOT NULL
    AND (fee_platform_rate < 0 OR fee_platform_rate >= 1)
  UNION ALL
  -- 不可自证的已支付半态：交易号或冻结微信金额对不上，人工判定前不许继续，
  -- 更不许让下面那条可自证修复先落地（那就是半应用状态）。
  SELECT 1
  FROM cms_registration
  WHERE payment_status = 2
    AND registration_status = 1
    AND NOT (transaction_id IS NOT NULL
      AND TRIM(transaction_id) <> ''
      AND payable_amount IS NOT NULL
      AND payable_amount > 0
      AND wechat_payment_amount IS NOT NULL
      AND wechat_payment_amount = payable_amount)
) p1_funds_spec_pre_violation
LIMIT 1;
DROP TEMPORARY TABLE p1_funds_spec_pre_guard;

-- ── 安全回填（逐行自证、单向、可重跑）─────────────────────────────────────────
-- 历史已支付半态：交易号与冻结微信金额都能自证时才单向修复；不可自证的已在上方 guard 拦死。
UPDATE cms_registration
SET registration_status = 2,
    update_by = 'funds-half-state-migration',
    update_time = NOW(3)
WHERE payment_status = 2
  AND registration_status = 1
  AND transaction_id IS NOT NULL
  AND TRIM(transaction_id) <> ''
  AND payable_amount IS NOT NULL
  AND payable_amount > 0
  AND wechat_payment_amount IS NOT NULL
  AND wechat_payment_amount = payable_amount;

-- 费率快照回填：来源与运行时 resolveFeeSnapshot 同源 —— 卖方主题上冻结的 fee_platform_rate。
-- 自玩单 owner_type=1，owner_id 直接是主题；只回填合法区间的值，越界的一律留空由 guard 兜。
UPDATE cms_registration reg
JOIN cms_topic topic ON topic.id = reg.owner_id
SET reg.fee_platform_rate = topic.fee_platform_rate
WHERE reg.owner_type = 1
  AND reg.payable_amount IS NOT NULL
  AND reg.payable_amount > 0
  AND reg.fee_platform_rate IS NULL
  AND topic.fee_platform_rate IS NOT NULL
  AND topic.fee_platform_rate >= 0
  AND topic.fee_platform_rate < 1;

-- 活动单 owner_type=2，卖方主题是 cms_activity.topic_id 指向的源主题；独立活动（topic_id 为空）
-- 推导不出来，留空并在末端显性计数，绝不用当前 live 规则费率去改写历史订单的分账基数。
UPDATE cms_registration reg
JOIN cms_activity act ON act.id = reg.owner_id
JOIN cms_topic topic ON topic.id = act.topic_id
SET reg.fee_platform_rate = topic.fee_platform_rate
WHERE reg.owner_type = 2
  AND reg.payable_amount IS NOT NULL
  AND reg.payable_amount > 0
  AND reg.fee_platform_rate IS NULL
  AND topic.fee_platform_rate IS NOT NULL
  AND topic.fee_platform_rate >= 0
  AND topic.fee_platform_rate < 1;

-- ── 回填后 fail-closed guard ─────────────────────────────────────────────────
-- 只对「还会动钱」的残留变红：已支付（payment_status=2）且 payable>0 仍缺快照 ⇒ 它会进结算，
-- 必须逐单人工定夺后再跑，不允许迁移静默放过。
-- 存量策略：未支付的历史单（过期/取消/从未支付）永远不会进结算，保持 NULL 是刻意的，
-- 由末端回读显性计数；它们要付款时运行时 preparePaymentRetry 的 assertFeeRateValid 会拦。
--
-- ⚠️ 生产实测(2026-07-25)：上面这条本会对「源头主题本身从未配置过费率」的历史已支付单误红——
-- 这不是 backfill 漏跑，是当时那个主题压根没有可回填的真值（不是本迁移引入的空值）。
-- 判据：能定位到 owner_type=1 直接主题 / owner_type=2 经 cms_activity.topic_id 间接主题，
-- 且该主题的 fee_platform_rate 本身就是 NULL —— 放行，保持订单 fee_platform_rate 仍为 NULL，
-- 语义同 refund_policy_code 列注释「NULL=存量单,读侧回落现取」。
-- 反之：主题能定位到且主题有费率、订单却仍是 NULL ⇒ backfill 真漏跑，或 owner_id/topic_id
-- 指向已被删除的主题（悬空引用，同样不可信）—— 这两种都必须继续拦，不能被这条豁免捎带放过。
DROP TEMPORARY TABLE IF EXISTS p1_funds_spec_post_guard;
CREATE TEMPORARY TABLE p1_funds_spec_post_guard (
  id TINYINT NOT NULL,
  PRIMARY KEY (id)
);
INSERT INTO p1_funds_spec_post_guard (id) VALUES (1);
INSERT INTO p1_funds_spec_post_guard (id)
SELECT 1
FROM (
  SELECT 1
  FROM cms_registration reg
  LEFT JOIN cms_topic direct_topic
    ON reg.owner_type = 1 AND direct_topic.id = reg.owner_id
  LEFT JOIN cms_activity act
    ON reg.owner_type = 2 AND act.id = reg.owner_id
  LEFT JOIN cms_topic activity_topic
    ON act.topic_id IS NOT NULL AND activity_topic.id = act.topic_id
  WHERE reg.payment_status = 2
    AND reg.payable_amount IS NOT NULL
    AND reg.payable_amount > 0
    AND reg.fee_platform_rate IS NULL
    AND NOT (
      (reg.owner_type = 1 AND direct_topic.id IS NOT NULL AND direct_topic.fee_platform_rate IS NULL)
      OR (reg.owner_type = 2 AND act.topic_id IS NOT NULL AND activity_topic.id IS NOT NULL
        AND activity_topic.fee_platform_rate IS NULL)
    )
  UNION ALL
  SELECT 1
  FROM cms_registration
  WHERE fee_platform_rate IS NOT NULL
    AND (fee_platform_rate < 0 OR fee_platform_rate >= 1)
  UNION ALL
  SELECT 1
  FROM cms_registration
  WHERE payment_status = 2
    AND registration_status = 1
) p1_funds_spec_post_violation
LIMIT 1;

-- 先修复同名错误索引，再建立并回读固定退款业务号唯一键。
SET @p1_refund_unique_definition := (
  SELECT IFNULL(CONCAT(MIN(non_unique), ':', GROUP_CONCAT(column_name ORDER BY seq_in_index)), '')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'refund_application'
    AND index_name = 'uk_refund_no'
);
SET @p1_drop_wrong_refund_unique_sql := IF(
  @p1_refund_unique_definition <> '' AND @p1_refund_unique_definition <> '0:refund_no',
  'ALTER TABLE refund_application DROP INDEX uk_refund_no',
  'SELECT 1'
);
PREPARE p1_drop_wrong_refund_unique_stmt FROM @p1_drop_wrong_refund_unique_sql;
EXECUTE p1_drop_wrong_refund_unique_stmt;
DEALLOCATE PREPARE p1_drop_wrong_refund_unique_stmt;

SET @p1_add_refund_unique_sql := IF(
  @p1_refund_unique_definition = '0:refund_no',
  'SELECT 1',
  'ALTER TABLE refund_application ADD UNIQUE KEY uk_refund_no (refund_no)'
);
PREPARE p1_add_refund_unique_stmt FROM @p1_add_refund_unique_sql;
EXECUTE p1_add_refund_unique_stmt;
DEALLOCATE PREPARE p1_add_refund_unique_stmt;

SET @p1_refund_unique_definition := (
  SELECT IFNULL(CONCAT(MIN(non_unique), ':', GROUP_CONCAT(column_name ORDER BY seq_in_index)), '')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'refund_application'
    AND index_name = 'uk_refund_no'
);
INSERT INTO p1_funds_spec_post_guard (id)
SELECT 1 WHERE @p1_refund_unique_definition <> '0:refund_no';

DROP TEMPORARY TABLE p1_funds_spec_post_guard;

SELECT table_name, index_name, non_unique, column_name, seq_in_index
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'refund_application'
  AND index_name = 'uk_refund_no'
ORDER BY seq_in_index;

-- 末端回读：invalid_fee_snapshot / unresolved_paid_half_state 必须为 0（否则上方 guard 已经把
-- 这次执行拦红）；paid_missing_fee_snapshot 现在允许 > 0 —— 上方 guard 已经放行了「源头主题
-- 本身从未配置过费率」这一类历史存量（判据见 guard 注释），这里的计数就是那批订单的数量，
-- 不代表 bug，是待观察项；legacy_unpaid_missing_fee_snapshot 同样是刻意保留的存量残留，
-- 数值本身就是待办量，不允许靠"没人提起"消失。
SELECT
  SUM(payment_status = 2 AND payable_amount > 0 AND fee_platform_rate IS NULL)
    AS paid_missing_fee_snapshot,
  SUM((payment_status IS NULL OR payment_status <> 2)
      AND payable_amount > 0 AND fee_platform_rate IS NULL)
    AS legacy_unpaid_missing_fee_snapshot,
  SUM(fee_platform_rate IS NOT NULL
      AND (fee_platform_rate < 0 OR fee_platform_rate >= 1)) AS invalid_fee_snapshot,
  SUM(payment_status = 2 AND registration_status = 1) AS unresolved_paid_half_state
FROM cms_registration;

SELECT column_name, column_type, is_nullable
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'refund_application'
  AND column_name IN ('payout_lease_token', 'payout_lease_owner', 'payout_lease_expires_at')
ORDER BY column_name;
