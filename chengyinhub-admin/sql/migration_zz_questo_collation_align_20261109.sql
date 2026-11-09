-- qx-c1 #2：Questo 迁移建表时只写了 DEFAULT CHARSET=utf8mb4、没写 COLLATE，MySQL 8 落成 utf8mb4_0900_ai_ci；
-- 与库内既有表（utf8mb4_general_ci）做字符串 join 时报 1267 Illegal mix of collations
-- （GiftEntitlementMapper：cms_registration.parent_order_no = marketing_product_order.order_no）。
-- 本文件只把这些表里【隐式 0900_ai_ci】的字符串列逐列改到 utf8mb4_general_ci，并把表默认排序规则对齐；
-- 显式声明过 COLLATE（utf8mb4_bin / ascii_bin / 0900_bin 等哈希、键列）的列一列都不碰，所以不用 CONVERT TO。
-- 列定义（类型、空值、默认值、注释、生成式）逐字取自按迁移建出的真实表的 SHOW CREATE TABLE，只插入字符集/排序规则。
-- workshop_version/order/install、workshop_draft_lineage、workshop_apple_product 建表时显式写了 utf8mb4_unicode_ci，属作者有意声明，不在本文件范围。
-- 生产执行必须走 chengyin-db-migrate；本文件只随代码交付，不自动落库。可重复执行（目标列已是 general_ci 时 MODIFY 等价重写）。

-- 预检 1：既有表的排序规则锚点必须是 utf8mb4_general_ci，否则对齐目标就错了，整文件失败。
SET @questo_collation_anchor_ok := (
  SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'cms_registration'
     AND column_name = 'parent_order_no' AND collation_name = 'utf8mb4_general_ci') = 1;

-- 预检 2：每张表的目标列形状必须与本文件逐列 MODIFY 的定义一致（类型 + 当前排序规则只能是 0900_ai_ci 或已对齐的 general_ci）。
SET @questo_collation_shape_ok := (
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'cms_registration_seat_invitation'
      AND collation_name IN ('utf8mb4_0900_ai_ci', 'utf8mb4_general_ci')
      AND ((column_name = 'token_nonce' AND column_type = 'char(16)' AND is_nullable = 'NO') OR
        (column_name = 'token_hash' AND column_type = 'char(64)' AND is_nullable = 'NO'))) = 2
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'cms_registration_seat_invitation_request'
      AND collation_name IN ('utf8mb4_0900_ai_ci', 'utf8mb4_general_ci')
      AND ((column_name = 'request_id' AND column_type = 'varchar(64)' AND is_nullable = 'NO') OR
        (column_name = 'operation' AND column_type = 'varchar(16)' AND is_nullable = 'NO') OR
        (column_name = 'request_fingerprint' AND column_type = 'char(64)' AND is_nullable = 'NO'))) = 3
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'cms_topic_content_revision'
      AND collation_name IN ('utf8mb4_0900_ai_ci', 'utf8mb4_general_ci')
      AND ((column_name = 'snapshot_json' AND column_type = 'longtext' AND is_nullable = 'NO') OR
        (column_name = 'origin' AND column_type = 'varchar(24)' AND is_nullable = 'NO'))) = 2
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'content_landing_metadata'
      AND collation_name IN ('utf8mb4_0900_ai_ci', 'utf8mb4_general_ci')
      AND ((column_name = 'source_locale' AND column_type = 'varchar(16)' AND is_nullable = 'NO') OR
        (column_name = 'landing_summary' AND column_type = 'varchar(500)' AND is_nullable = 'YES') OR
        (column_name = 'accessibility_note' AND column_type = 'varchar(255)' AND is_nullable = 'YES'))) = 3
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'content_landing_translation'
      AND collation_name IN ('utf8mb4_0900_ai_ci', 'utf8mb4_general_ci')
      AND ((column_name = 'locale' AND column_type = 'varchar(16)' AND is_nullable = 'NO') OR
        (column_name = 'source_locale' AND column_type = 'varchar(16)' AND is_nullable = 'NO') OR
        (column_name = 'title' AND column_type = 'varchar(200)' AND is_nullable = 'YES') OR
        (column_name = 'subtitle' AND column_type = 'varchar(255)' AND is_nullable = 'YES') OR
        (column_name = 'summary' AND column_type = 'varchar(500)' AND is_nullable = 'YES') OR
        (column_name = 'translation_state' AND column_type = 'varchar(16)' AND is_nullable = 'NO'))) = 6
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'gift_entitlement'
      AND collation_name IN ('utf8mb4_0900_ai_ci', 'utf8mb4_general_ci')
      AND ((column_name = 'public_id' AND column_type = 'varchar(32)' AND is_nullable = 'NO') OR
        (column_name = 'claim_request_id' AND column_type = 'varchar(64)' AND is_nullable = 'YES') OR
        (column_name = 'revoked_reason' AND column_type = 'varchar(120)' AND is_nullable = 'YES'))) = 3
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'host_tip_config'
      AND collation_name IN ('utf8mb4_0900_ai_ci', 'utf8mb4_general_ci')
      AND ((column_name = 'currency' AND column_type = 'char(3)' AND is_nullable = 'NO') OR
        (column_name = 'payment_channel' AND column_type = 'varchar(32)' AND is_nullable = 'YES') OR
        (column_name = 'del_flag' AND column_type = 'char(1)' AND is_nullable = 'NO'))) = 3
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'host_tip_intent'
      AND collation_name IN ('utf8mb4_0900_ai_ci', 'utf8mb4_general_ci')
      AND ((column_name = 'currency' AND column_type = 'char(3)' AND is_nullable = 'NO') OR
        (column_name = 'payment_channel' AND column_type = 'varchar(32)' AND is_nullable = 'YES') OR
        (column_name = 'external_trade_no' AND column_type = 'varchar(64)' AND is_nullable = 'YES') OR
        (column_name = 'settlement_ref' AND column_type = 'varchar(64)' AND is_nullable = 'YES') OR
        (column_name = 'idempotency_key' AND column_type = 'varchar(120)' AND is_nullable = 'NO') OR
        (column_name = 'note' AND column_type = 'varchar(500)' AND is_nullable = 'YES'))) = 6
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'marketing_attribution'
      AND collation_name IN ('utf8mb4_0900_ai_ci', 'utf8mb4_general_ci')
      AND ((column_name = 'campaign_key' AND column_type = 'varchar(40)' AND is_nullable = 'NO'))) = 1
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'marketing_channel_order_verification'
      AND collation_name IN ('utf8mb4_0900_ai_ci', 'utf8mb4_general_ci')
      AND ((column_name = 'channel_order_no' AND column_type = 'varchar(96)' AND is_nullable = 'NO') OR
        (column_name = 'external_reference' AND column_type = 'varchar(128)' AND is_nullable = 'YES') OR
        (column_name = 'event_id' AND column_type = 'varchar(96)' AND is_nullable = 'YES') OR
        (column_name = 'currency' AND column_type = 'varchar(8)' AND is_nullable = 'YES') OR
        (column_name = 'sku_json' AND column_type = 'text' AND is_nullable = 'YES') OR
        (column_name = 'reason_code' AND column_type = 'varchar(48)' AND is_nullable = 'YES') OR
        (column_name = 'request_id' AND column_type = 'varchar(64)' AND is_nullable = 'NO'))) = 7
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'marketing_channel_redemption'
      AND collation_name IN ('utf8mb4_0900_ai_ci', 'utf8mb4_general_ci')
      AND ((column_name = 'channel_order_no' AND column_type = 'varchar(96)' AND is_nullable = 'NO') OR
        (column_name = 'registration_no' AND column_type = 'varchar(40)' AND is_nullable = 'YES') OR
        (column_name = 'registration_ids_json' AND column_type = 'varchar(512)' AND is_nullable = 'YES') OR
        (column_name = 'entitlement_ids_json' AND column_type = 'varchar(512)' AND is_nullable = 'YES') OR
        (column_name = 'grant_ref' AND column_type = 'varchar(128)' AND is_nullable = 'YES') OR
        (column_name = 'request_id' AND column_type = 'varchar(64)' AND is_nullable = 'NO') OR
        (column_name = 'revoke_reason' AND column_type = 'varchar(120)' AND is_nullable = 'YES'))) = 7
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'marketing_channel_refund_verification'
      AND collation_name IN ('utf8mb4_0900_ai_ci', 'utf8mb4_general_ci')
      AND ((column_name = 'channel_order_no' AND column_type = 'varchar(96)' AND is_nullable = 'NO') OR
        (column_name = 'channel_refund_no' AND column_type = 'varchar(96)' AND is_nullable = 'NO') OR
        (column_name = 'event_id' AND column_type = 'varchar(96)' AND is_nullable = 'NO') OR
        (column_name = 'currency' AND column_type = 'varchar(8)' AND is_nullable = 'NO') OR
        (column_name = 'reason_code' AND column_type = 'varchar(48)' AND is_nullable = 'YES') OR
        (column_name = 'request_id' AND column_type = 'varchar(64)' AND is_nullable = 'NO'))) = 6
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'marketing_channel_voucher'
      AND collation_name IN ('utf8mb4_0900_ai_ci', 'utf8mb4_general_ci')
      AND ((column_name = 'revoke_source' AND column_type = 'varchar(32)' AND is_nullable = 'YES') OR
        (column_name = 'revoke_reason' AND column_type = 'varchar(120)' AND is_nullable = 'YES') OR
        (column_name = 'revoke_request_id' AND column_type = 'varchar(64)' AND is_nullable = 'YES') OR
        (column_name = 'request_id' AND column_type = 'varchar(64)' AND is_nullable = 'NO') OR
        (column_name = 'grant_snapshot_json' AND column_type = 'mediumtext' AND is_nullable = 'YES'))) = 5
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'marketing_channel_voucher_account'
      AND collation_name IN ('utf8mb4_0900_ai_ci', 'utf8mb4_general_ci')
      AND ((column_name = 'display_name' AND column_type = 'varchar(120)' AND is_nullable = 'NO') OR
        (column_name = 'contract_ref' AND column_type = 'varchar(120)' AND is_nullable = 'NO'))) = 2
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'marketing_offer'
      AND collation_name IN ('utf8mb4_0900_ai_ci', 'utf8mb4_general_ci')
      AND ((column_name = 'offer_code' AND column_type = 'varchar(40)' AND is_nullable = 'NO') OR
        (column_name = 'name' AND column_type = 'varchar(80)' AND is_nullable = 'NO') OR
        (column_name = 'description' AND column_type = 'varchar(500)' AND is_nullable = 'YES') OR
        (column_name = 'create_by' AND column_type = 'varchar(64)' AND is_nullable = 'YES') OR
        (column_name = 'update_by' AND column_type = 'varchar(64)' AND is_nullable = 'YES'))) = 5
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'marketing_order_adjustment'
      AND collation_name IN ('utf8mb4_0900_ai_ci', 'utf8mb4_general_ci')
      AND ((column_name = 'offer_code' AND column_type = 'varchar(40)' AND is_nullable = 'YES') OR
        (column_name = 'rule_version' AND column_type = 'varchar(128)' AND is_nullable = 'NO'))) = 2
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'marketing_product'
      AND collation_name IN ('utf8mb4_0900_ai_ci', 'utf8mb4_general_ci')
      AND ((column_name = 'product_code' AND column_type = 'varchar(48)' AND is_nullable = 'NO') OR
        (column_name = 'product_name' AND column_type = 'varchar(120)' AND is_nullable = 'NO') OR
        (column_name = 'sale_city' AND column_type = 'varchar(64)' AND is_nullable = 'YES') OR
        (column_name = 'terms_version' AND column_type = 'varchar(40)' AND is_nullable = 'NO'))) = 4
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'marketing_product_item'
      AND collation_name IN ('utf8mb4_0900_ai_ci', 'utf8mb4_general_ci')
      AND ((column_name = 'topic_title' AND column_type = 'varchar(160)' AND is_nullable = 'NO'))) = 1
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'marketing_product_order'
      AND collation_name IN ('utf8mb4_0900_ai_ci', 'utf8mb4_general_ci')
      AND ((column_name = 'order_no' AND column_type = 'varchar(40)' AND is_nullable = 'NO') OR
        (column_name = 'currency' AND column_type = 'varchar(8)' AND is_nullable = 'NO') OR
        (column_name = 'product_snapshot_json' AND column_type = 'longtext' AND is_nullable = 'NO') OR
        (column_name = 'primary_registration_no' AND column_type = 'varchar(40)' AND is_nullable = 'YES') OR
        (column_name = 'registration_ids_json' AND column_type = 'text' AND is_nullable = 'YES') OR
        (column_name = 'revoked_reason' AND column_type = 'varchar(120)' AND is_nullable = 'YES') OR
        (column_name = 'request_id' AND column_type = 'varchar(64)' AND is_nullable = 'NO'))) = 7
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'marketing_promotion'
      AND collation_name IN ('utf8mb4_0900_ai_ci', 'utf8mb4_general_ci')
      AND ((column_name = 'promo_code' AND column_type = 'varchar(40)' AND is_nullable = 'NO') OR
        (column_name = 'name' AND column_type = 'varchar(80)' AND is_nullable = 'NO') OR
        (column_name = 'description' AND column_type = 'varchar(500)' AND is_nullable = 'YES') OR
        (column_name = 'stack_group' AND column_type = 'varchar(32)' AND is_nullable = 'NO') OR
        (column_name = 'create_by' AND column_type = 'varchar(64)' AND is_nullable = 'YES') OR
        (column_name = 'update_by' AND column_type = 'varchar(64)' AND is_nullable = 'YES'))) = 6
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'marketing_promotion_redemption'
      AND collation_name IN ('utf8mb4_0900_ai_ci', 'utf8mb4_general_ci')
      AND ((column_name = 'promo_code' AND column_type = 'varchar(40)' AND is_nullable = 'NO') OR
        (column_name = 'idempotency_key' AND column_type = 'varchar(96)' AND is_nullable = 'NO'))) = 2
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'marketing_promotion_refund_fact'
      AND collation_name IN ('utf8mb4_0900_ai_ci', 'utf8mb4_general_ci')
      AND ((column_name = 'out_refund_no' AND column_type = 'varchar(64)' AND is_nullable = 'NO') OR
        (column_name = 'idempotency_key' AND column_type = 'varchar(96)' AND is_nullable = 'NO'))) = 2
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'marketing_referral_credential'
      AND collation_name IN ('utf8mb4_0900_ai_ci', 'utf8mb4_general_ci')
      AND ((column_name = 'public_id' AND column_type = 'varchar(32)' AND is_nullable = 'NO') OR
        (column_name = 'campaign_key' AND column_type = 'varchar(40)' AND is_nullable = 'NO'))) = 2
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'marketing_revenue_allocation'
      AND collation_name IN ('utf8mb4_0900_ai_ci', 'utf8mb4_general_ci')
      AND ((column_name = 'allocation_key' AND column_type = 'varchar(96)' AND is_nullable = 'NO') OR
        (column_name = 'rule_key' AND column_type = 'varchar(96)' AND is_nullable = 'NO') OR
        (column_name = 'campaign_key' AND column_type = 'varchar(40)' AND is_nullable = 'YES') OR
        (column_name = 'contract_ref' AND column_type = 'varchar(64)' AND is_nullable = 'NO') OR
        (column_name = 'currency' AND column_type = 'char(3)' AND is_nullable = 'NO') OR
        (column_name = 'fee_basis_ref' AND column_type = 'varchar(96)' AND is_nullable = 'NO'))) = 6
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'marketing_revenue_allocation_line'
      AND collation_name IN ('utf8mb4_0900_ai_ci', 'utf8mb4_general_ci')
      AND ((column_name = 'line_role' AND column_type = 'varchar(16)' AND is_nullable = 'NO') OR
        (column_name = 'currency' AND column_type = 'char(3)' AND is_nullable = 'NO') OR
        (column_name = 'entry_key' AND column_type = 'varchar(160)' AND is_nullable = 'NO') OR
        (column_name = 'event_ref' AND column_type = 'varchar(96)' AND is_nullable = 'YES') OR
        (column_name = 'event_currency' AND column_type = 'char(3)' AND is_nullable = 'YES') OR
        (column_name = 'source_fact' AND column_type = 'varchar(96)' AND is_nullable = 'YES'))) = 6
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'marketing_revenue_posting_task'
      AND collation_name IN ('utf8mb4_0900_ai_ci', 'utf8mb4_general_ci')
      AND ((column_name = 'lease_owner' AND column_type = 'varchar(64)' AND is_nullable = 'YES') OR
        (column_name = 'reason_code' AND column_type = 'varchar(48)' AND is_nullable = 'YES') OR
        (column_name = 'detail' AND column_type = 'varchar(255)' AND is_nullable = 'YES'))) = 3
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'marketing_revenue_rule'
      AND collation_name IN ('utf8mb4_0900_ai_ci', 'utf8mb4_general_ci')
      AND ((column_name = 'rule_key' AND column_type = 'varchar(96)' AND is_nullable = 'NO') OR
        (column_name = 'currency' AND column_type = 'char(3)' AND is_nullable = 'NO') OR
        (column_name = 'contract_ref' AND column_type = 'varchar(64)' AND is_nullable = 'YES') OR
        (column_name = 'note' AND column_type = 'varchar(255)' AND is_nullable = 'YES') OR
        (column_name = 'active_key' AND column_type = 'varchar(96)' AND is_nullable = 'YES'))) = 5
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'marketing_tier'
      AND collation_name IN ('utf8mb4_0900_ai_ci', 'utf8mb4_general_ci')
      AND ((column_name = 'tier_code' AND column_type = 'varchar(64)' AND is_nullable = 'NO') OR
        (column_name = 'create_by' AND column_type = 'varchar(64)' AND is_nullable = 'YES') OR
        (column_name = 'update_by' AND column_type = 'varchar(64)' AND is_nullable = 'YES'))) = 3
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'play_help_run_quota'
      AND collation_name IN ('utf8mb4_0900_ai_ci', 'utf8mb4_general_ci')
      AND ((column_name = 'play_instance_id' AND column_type = 'varchar(64)' AND is_nullable = 'NO'))) = 1
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'video_bounty_accrual'
      AND collation_name IN ('utf8mb4_0900_ai_ci', 'utf8mb4_general_ci')
      AND ((column_name = 'group_key' AND column_type = 'varchar(64)' AND is_nullable = 'NO') OR
        (column_name = 'rule_version' AND column_type = 'varchar(24)' AND is_nullable = 'NO') OR
        (column_name = 'currency' AND column_type = 'char(3)' AND is_nullable = 'NO') OR
        (column_name = 'idempotency_key' AND column_type = 'varchar(160)' AND is_nullable = 'NO') OR
        (column_name = 'note' AND column_type = 'varchar(500)' AND is_nullable = 'YES'))) = 5
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'video_bounty_campaign'
      AND collation_name IN ('utf8mb4_0900_ai_ci', 'utf8mb4_general_ci')
      AND ((column_name = 'campaign_key' AND column_type = 'varchar(40)' AND is_nullable = 'NO') OR
        (column_name = 'title' AND column_type = 'varchar(120)' AND is_nullable = 'NO') OR
        (column_name = 'description' AND column_type = 'varchar(1000)' AND is_nullable = 'YES') OR
        (column_name = 'currency' AND column_type = 'char(3)' AND is_nullable = 'NO') OR
        (column_name = 'rule_version' AND column_type = 'varchar(24)' AND is_nullable = 'NO') OR
        (column_name = 'config_json' AND column_type = 'text' AND is_nullable = 'YES') OR
        (column_name = 'del_flag' AND column_type = 'char(1)' AND is_nullable = 'NO'))) = 7
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'video_bounty_evidence'
      AND collation_name IN ('utf8mb4_0900_ai_ci', 'utf8mb4_general_ci')
      AND ((column_name = 'source_ref' AND column_type = 'varchar(200)' AND is_nullable = 'NO') OR
        (column_name = 'captured_by' AND column_type = 'varchar(64)' AND is_nullable = 'NO') OR
        (column_name = 'note' AND column_type = 'varchar(500)' AND is_nullable = 'YES'))) = 3
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'video_bounty_submission'
      AND collation_name IN ('utf8mb4_0900_ai_ci', 'utf8mb4_general_ci')
      AND ((column_name = 'campaign_key' AND column_type = 'varchar(40)' AND is_nullable = 'NO') OR
        (column_name = 'platform' AND column_type = 'varchar(16)' AND is_nullable = 'NO') OR
        (column_name = 'source_url' AND column_type = 'varchar(512)' AND is_nullable = 'NO') OR
        (column_name = 'canonical_video_key' AND column_type = 'varchar(80)' AND is_nullable = 'NO') OR
        (column_name = 'origin_group_key' AND column_type = 'varchar(64)' AND is_nullable = 'NO') OR
        (column_name = 'reviewed_by' AND column_type = 'varchar(64)' AND is_nullable = 'YES') OR
        (column_name = 'review_note' AND column_type = 'varchar(500)' AND is_nullable = 'YES') OR
        (column_name = 'del_flag' AND column_type = 'char(1)' AND is_nullable = 'NO') OR
        (column_name = 'verified_group_key' AND column_type = 'varchar(64)' AND is_nullable = 'YES') OR
        (column_name = 'verified_source_ref' AND column_type = 'varchar(200)' AND is_nullable = 'YES'))) = 10
  AND (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'workshop_apple_notification'
      AND collation_name IN ('utf8mb4_0900_ai_ci', 'utf8mb4_general_ci')
      AND ((column_name = 'signed_payload' AND column_type = 'mediumtext' AND is_nullable = 'NO') OR
        (column_name = 'reason' AND column_type = 'varchar(96)' AND is_nullable = 'YES'))) = 2
);
-- runner: assert=scalar-true
SELECT (@questo_collation_anchor_ok AND @questo_collation_shape_ok) AS questo_collation_precheck;
SET @questo_collation_guard_sql := IF(@questo_collation_anchor_ok AND @questo_collation_shape_ok,
    'SELECT 1', 'SELECT * FROM information_schema.__questo_collation_align_precheck_failed__');
PREPARE questo_collation_guard FROM @questo_collation_guard_sql;
EXECUTE questo_collation_guard;
DEALLOCATE PREPARE questo_collation_guard;

ALTER TABLE `cms_registration_seat_invitation`
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci,
  MODIFY COLUMN `token_nonce` char(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '公开随机数；用于幂等重放重算同一枚签名码',
  MODIFY COLUMN `token_hash` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '邀请码 SHA-256；明文永不落库';

ALTER TABLE `cms_registration_seat_invitation_request`
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci,
  MODIFY COLUMN `request_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '客户端请求号；同号同指纹幂等重放',
  MODIFY COLUMN `operation` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'INVITE/CLAIM/CANCEL/RELEASE',
  MODIFY COLUMN `request_fingerprint` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '服务端请求指纹；同号不同指纹=409';

ALTER TABLE `cms_topic_content_revision`
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci,
  MODIFY COLUMN `snapshot_json` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '精确冻结私有 payload(含答案),字节原样,仅服务端读',
  MODIFY COLUMN `origin` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'PUBLISH=本次发布写入 / LEGACY_IMPORTED=存量发布头原样导入';

ALTER TABLE `content_landing_metadata`
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci,
  MODIFY COLUMN `source_locale` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'zh-CN' COMMENT '原文语言；cms_topic.name/subtitle/description 的语言',
  MODIFY COLUMN `landing_summary` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '作者填写的无剧透公开摘要；NULL = 读侧回退 cms_topic.description 截断',
  MODIFY COLUMN `accessibility_note` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '无障碍/天气/场地限制说明；NULL=未提供';

ALTER TABLE `content_landing_translation`
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci,
  MODIFY COLUMN `locale` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'BCP-47 语言标签，如 en / zh-CN / ja',
  MODIFY COLUMN `source_locale` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'zh-CN' COMMENT '本条译文对应的原文语言，用于判断原文已改需重译',
  MODIFY COLUMN `title` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '公开标题译文；NULL=缺译',
  MODIFY COLUMN `subtitle` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '公开副标题译文；NULL=缺译',
  MODIFY COLUMN `summary` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '公开摘要译文；NULL=缺译',
  MODIFY COLUMN `translation_state` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'APPROVED=可用；PENDING=待校对，读侧不当作已完成翻译';

ALTER TABLE `gift_entitlement`
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci,
  MODIFY COLUMN `public_id` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  MODIFY COLUMN `claim_request_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  MODIFY COLUMN `revoked_reason` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL;

ALTER TABLE `host_tip_config`
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci,
  MODIFY COLUMN `currency` char(3) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  MODIFY COLUMN `payment_channel` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '受支持的收款通道标识;为空 = 未启用',
  MODIFY COLUMN `del_flag` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT '0';

ALTER TABLE `host_tip_intent`
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci,
  MODIFY COLUMN `currency` char(3) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  MODIFY COLUMN `payment_channel` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '配置快照;为空 = 通道未启用',
  MODIFY COLUMN `external_trade_no` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '通道交易号;通道未启用时为空',
  MODIFY COLUMN `settlement_ref` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '既有结算/分账引用(集成契约,未启用时为空)',
  MODIFY COLUMN `idempotency_key` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  MODIFY COLUMN `note` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL;

ALTER TABLE `marketing_attribution`
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci,
  MODIFY COLUMN `campaign_key` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL;

ALTER TABLE `marketing_channel_order_verification`
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci,
  MODIFY COLUMN `channel_order_no` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '渠道订单号，必须可追溯',
  MODIFY COLUMN `external_reference` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  MODIFY COLUMN `event_id` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '渠道回调事件 id；与 provider_key 组成入站幂等键',
  MODIFY COLUMN `currency` varchar(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  MODIFY COLUMN `sku_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT '渠道 SKU 原文/映射快照；不含支付凭据',
  MODIFY COLUMN `reason_code` varchar(48) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  MODIFY COLUMN `request_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL;

ALTER TABLE `marketing_channel_redemption`
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci,
  MODIFY COLUMN `channel_order_no` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '渠道单号快照，用于对账',
  MODIFY COLUMN `registration_no` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '主单业务号快照',
  MODIFY COLUMN `registration_ids_json` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '全部注册行 id 快照（多作品包），用于原单回查',
  MODIFY COLUMN `entitlement_ids_json` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '既有 registration_entitlement.id 快照；仅 ③ 探索票',
  MODIFY COLUMN `grant_ref` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'M2-F grant 端口的不可变回执引用',
  MODIFY COLUMN `request_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  MODIFY COLUMN `revoke_reason` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL;

ALTER TABLE `marketing_channel_refund_verification`
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci,
  MODIFY COLUMN `channel_order_no` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  MODIFY COLUMN `channel_refund_no` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  MODIFY COLUMN `event_id` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  MODIFY COLUMN `currency` varchar(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  MODIFY COLUMN `reason_code` varchar(48) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  MODIFY COLUMN `request_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL;

ALTER TABLE `marketing_channel_voucher`
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci,
  MODIFY COLUMN `revoke_source` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'OPERATOR/CHANNEL/EXPIRY/REFUND',
  MODIFY COLUMN `revoke_reason` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '撤销申请原因，不等于已撤销',
  MODIFY COLUMN `revoke_request_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '撤销申请幂等键',
  MODIFY COLUMN `request_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  MODIFY COLUMN `grant_snapshot_json` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT '服务端发行冻结商品期限条款作品及金额分摊；历史空值禁止兑换';

ALTER TABLE `marketing_channel_voucher_account`
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci,
  MODIFY COLUMN `display_name` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  MODIFY COLUMN `contract_ref` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '合同/协议编号；空值不允许发码';

ALTER TABLE `marketing_offer`
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci,
  MODIFY COLUMN `offer_code` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '稳定业务码:客户端只回传它,不回传价格',
  MODIFY COLUMN `name` varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '展示名',
  MODIFY COLUMN `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '展示说明',
  MODIFY COLUMN `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  MODIFY COLUMN `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL;

ALTER TABLE `marketing_offer_item`
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

ALTER TABLE `marketing_order_adjustment`
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci,
  MODIFY COLUMN `offer_code` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  MODIFY COLUMN `rule_version` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '参与计算的规则版本串';

ALTER TABLE `marketing_product`
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci,
  MODIFY COLUMN `product_code` varchar(48) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '运营配置的稳定商品编码',
  MODIFY COLUMN `product_name` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  MODIFY COLUMN `sale_city` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '城市绑定；NULL=不按城市限售',
  MODIFY COLUMN `terms_version` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '条款版本快照';

ALTER TABLE `marketing_product_item`
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci,
  MODIFY COLUMN `topic_title` varchar(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '绑定时的标题快照，公开读面不 join 未发布正文';

ALTER TABLE `marketing_product_order`
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci,
  MODIFY COLUMN `order_no` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  MODIFY COLUMN `currency` varchar(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'CNY',
  MODIFY COLUMN `product_snapshot_json` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '下单时商品+items+分摊+条款快照',
  MODIFY COLUMN `primary_registration_no` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '父 registration_no，供 C07 退款回查',
  MODIFY COLUMN `registration_ids_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT '父/子 cms_registration.id 列表；分摊守恒在 payable_amount 上',
  MODIFY COLUMN `revoked_reason` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  MODIFY COLUMN `request_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL;

ALTER TABLE `marketing_product_order_line`
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

ALTER TABLE `marketing_promotion`
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci,
  MODIFY COLUMN `promo_code` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '稳定业务码',
  MODIFY COLUMN `name` varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  MODIFY COLUMN `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  MODIFY COLUMN `stack_group` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'DEFAULT' COMMENT '同组只取一个',
  MODIFY COLUMN `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  MODIFY COLUMN `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL;

ALTER TABLE `marketing_promotion_redemption`
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci,
  MODIFY COLUMN `promo_code` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '业务码冗余,配置改名后快照仍可读',
  MODIFY COLUMN `idempotency_key` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL;

ALTER TABLE `marketing_promotion_refund_fact`
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci,
  MODIFY COLUMN `out_refund_no` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '我方退款单号(对账口径)',
  MODIFY COLUMN `idempotency_key` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'refund-settled:{registrationId}:{refundApplicationId}';

ALTER TABLE `marketing_referral_credential`
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci,
  MODIFY COLUMN `public_id` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '公开标识(HTTP 可见,非秘密),与签名共同确定凭据身份',
  MODIFY COLUMN `campaign_key` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '推广活动标识,仅分组统计';

ALTER TABLE `marketing_revenue_allocation`
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci,
  MODIFY COLUMN `allocation_key` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '业务键快照，如 registration:555；不含规则版本',
  MODIFY COLUMN `rule_key` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  MODIFY COLUMN `campaign_key` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  MODIFY COLUMN `contract_ref` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '应计时冻结的受审合同标识',
  MODIFY COLUMN `currency` char(3) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  MODIFY COLUMN `fee_basis_ref` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '三项扣费的证据引用；无证据不得应计（不得默认 0）';

ALTER TABLE `marketing_revenue_allocation_line`
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci,
  MODIFY COLUMN `line_role` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'author / referrer / platform',
  MODIFY COLUMN `currency` char(3) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  MODIFY COLUMN `entry_key` varchar(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'accrue:<role> / reverse:<eventRef>:<role>',
  MODIFY COLUMN `event_ref` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '退款/chargeback 事件标识 refund:<refundId>；应计行为 NULL',
  MODIFY COLUMN `event_currency` char(3) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '该事件的币种，用于同 ID 异币种拒绝',
  MODIFY COLUMN `source_fact` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '触发事实，如 refund:123 / payment-fact:<hash>';

ALTER TABLE `marketing_revenue_posting_task`
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci,
  MODIFY COLUMN `lease_owner` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '领取者标识；进程崩溃后由租约过期回收',
  MODIFY COLUMN `reason_code` varchar(48) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '最近一次失败原因码，如 RULE_NOT_FOUND / REFUND_FACT_MISSING',
  MODIFY COLUMN `detail` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '脱敏说明，供对账定位';

ALTER TABLE `marketing_revenue_rule`
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci,
  MODIFY COLUMN `rule_key` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '稳定逻辑键，含适用域与场景：topic:<id>:s<N> / activity:<id>:s<N> / global:s<N>',
  MODIFY COLUMN `currency` char(3) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'ISO-4217；必须与已验证支付事实的币种一致',
  MODIFY COLUMN `contract_ref` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '受审交易合同/协议标识；ACTIVE 必填，DRAFT 可空',
  MODIFY COLUMN `note` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  MODIFY COLUMN `active_key` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci GENERATED ALWAYS AS ((case when (`status` = 1) then `rule_key` else NULL end)) STORED COMMENT '仅 ACTIVE 有值；唯一键保证同一 rule_key 同时只有一个生效版本';

ALTER TABLE `marketing_tier`
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci,
  MODIFY COLUMN `tier_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '业务码(后台检索/日志定位)',
  MODIFY COLUMN `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  MODIFY COLUMN `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL;

ALTER TABLE `play_help_run_quota`
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci,
  MODIFY COLUMN `play_instance_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '本局真实游玩实例；来自 topic_route_session.play_instance_id';

ALTER TABLE `video_bounty_accrual`
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci,
  MODIFY COLUMN `group_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '同一原视频分组键,封顶按组合计',
  MODIFY COLUMN `rule_version` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '计算时生效的规则版本;历史行永不被改写',
  MODIFY COLUMN `currency` char(3) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  MODIFY COLUMN `idempotency_key` varchar(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  MODIFY COLUMN `note` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL;

ALTER TABLE `video_bounty_campaign`
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci,
  MODIFY COLUMN `campaign_key` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '活动标识,与推广凭据 campaignKey 对齐',
  MODIFY COLUMN `title` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  MODIFY COLUMN `description` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  MODIFY COLUMN `currency` char(3) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'ISO-4217;只记录口径,不做汇率换算',
  MODIFY COLUMN `rule_version` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '规则版本;改费率/封顶/窗口必须换版本',
  MODIFY COLUMN `config_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT '平台白名单/去重策略等附加配置',
  MODIFY COLUMN `del_flag` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT '0';

ALTER TABLE `video_bounty_evidence`
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci,
  MODIFY COLUMN `source_ref` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '外部来源引用(报表编号/文件名摘要/截图 SHA-256);不接受空',
  MODIFY COLUMN `captured_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '录入人或导入任务标识',
  MODIFY COLUMN `note` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL;

ALTER TABLE `video_bounty_submission`
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci,
  MODIFY COLUMN `campaign_key` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '冗余,便于按活动过滤且防止活动键变更后失联',
  MODIFY COLUMN `platform` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'tiktok/reels/youtube',
  MODIFY COLUMN `source_url` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '作者提交的原始链接',
  MODIFY COLUMN `canonical_video_key` varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '平台+外部视频 id 规范化键,去重依据',
  MODIFY COLUMN `origin_group_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '同一原视频分组键;跨平台搬运必须同组',
  MODIFY COLUMN `reviewed_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '审核人标识,带 realm 前缀(如 sys:1);后台用户与会员是两套 id 空间,不能只存数字',
  MODIFY COLUMN `review_note` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  MODIFY COLUMN `del_flag` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT '0',
  MODIFY COLUMN `verified_group_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '运营核验后的原视频组键；NULL=未核验，不生成确定应计',
  MODIFY COLUMN `verified_source_ref` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '核验证据引用(工单/素材/外链编号)';

ALTER TABLE `workshop_apple_notification`
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci,
  MODIFY COLUMN `signed_payload` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  MODIFY COLUMN `reason` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL;

ALTER TABLE `workshop_apple_original`
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

ALTER TABLE `workshop_apple_transaction`
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

-- 回读 1：这些表里不再有隐式 0900_ai_ci 字符串列，表默认排序规则全部对齐。
-- runner: assert=scalar-true
SELECT (
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND collation_name = 'utf8mb4_0900_ai_ci'
      AND table_name IN ('cms_registration_seat_invitation', 'cms_registration_seat_invitation_request', 'cms_topic_content_revision', 'content_landing_metadata', 'content_landing_translation', 'gift_entitlement', 'host_tip_config', 'host_tip_intent', 'marketing_attribution', 'marketing_channel_order_verification', 'marketing_channel_redemption', 'marketing_channel_refund_verification', 'marketing_channel_voucher', 'marketing_channel_voucher_account', 'marketing_offer', 'marketing_offer_item', 'marketing_order_adjustment', 'marketing_product', 'marketing_product_item', 'marketing_product_order', 'marketing_product_order_line', 'marketing_promotion', 'marketing_promotion_redemption', 'marketing_promotion_refund_fact', 'marketing_referral_credential', 'marketing_revenue_allocation', 'marketing_revenue_allocation_line', 'marketing_revenue_posting_task', 'marketing_revenue_rule', 'marketing_tier', 'play_help_run_quota', 'video_bounty_accrual', 'video_bounty_campaign', 'video_bounty_evidence', 'video_bounty_submission', 'workshop_apple_notification', 'workshop_apple_original', 'workshop_apple_transaction')) = 0
  AND (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_collation = 'utf8mb4_general_ci'
      AND table_name IN ('cms_registration_seat_invitation', 'cms_registration_seat_invitation_request', 'cms_topic_content_revision', 'content_landing_metadata', 'content_landing_translation', 'gift_entitlement', 'host_tip_config', 'host_tip_intent', 'marketing_attribution', 'marketing_channel_order_verification', 'marketing_channel_redemption', 'marketing_channel_refund_verification', 'marketing_channel_voucher', 'marketing_channel_voucher_account', 'marketing_offer', 'marketing_offer_item', 'marketing_order_adjustment', 'marketing_product', 'marketing_product_item', 'marketing_product_order', 'marketing_product_order_line', 'marketing_promotion', 'marketing_promotion_redemption', 'marketing_promotion_refund_fact', 'marketing_referral_credential', 'marketing_revenue_allocation', 'marketing_revenue_allocation_line', 'marketing_revenue_posting_task', 'marketing_revenue_rule', 'marketing_tier', 'play_help_run_quota', 'video_bounty_accrual', 'video_bounty_campaign', 'video_bounty_evidence', 'video_bounty_submission', 'workshop_apple_notification', 'workshop_apple_original', 'workshop_apple_transaction')) = 38
) AS questo_collation_aligned;

-- 回读 2：原报错的 join 形状（礼品领取按 parent_order_no 展开）现在能解析执行。
-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM cms_registration r
          JOIN marketing_product_order o ON r.parent_order_no = o.order_no
         WHERE r.id < 0) = 0 AS questo_gift_join_resolves;
