-- MS 加座 route3（fulfillment_kind=3）：
-- 1) 原 PK 约束 ck_marketing_order_fulfillment 只允许 (1,2)，route3 落不了单，这里放行 3；
-- 2) 加座按原单回查/计数（容量与末席竞争）需要 (original_order_id, fulfillment_kind, status) 索引。
-- 不新增表：不加平行席位账本、不加钱包、不改金额列、不动原单已付事实。
-- Source-only ordered migration；不执行生产 DDL，落库时点由 root 决定（chengyin-db-migrate 只读预检 + 用户确认）。

-- runner: assert=scalar-true
SELECT CAST(IF(DATABASE() IS NOT NULL
  AND CAST(SUBSTRING_INDEX(VERSION(),'.',1) AS UNSIGNED)=8
  AND (CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(VERSION(),'.',2),'.',-1) AS UNSIGNED)>0
    OR CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(VERSION(),'-',1),'.',-1) AS UNSIGNED)>=16)
  AND (FIND_IN_SET('STRICT_TRANS_TABLES',@@sql_mode)>0 OR FIND_IN_SET('STRICT_ALL_TABLES',@@sql_mode)>0)
  AND (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
    AND table_name IN ('marketing_product','marketing_product_item','marketing_product_order',
      'marketing_product_order_line','gift_entitlement'))=5
  AND (SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema=DATABASE()
    AND table_name='marketing_product_order' AND constraint_type='CHECK'
    AND constraint_name='ck_marketing_order_fulfillment')=1
  AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='marketing_product_order' AND index_name='idx_marketing_order_extra_seat')=0
  AND (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
    AND table_name IN ('marketing_extra_seat','marketing_extra_seat_order','marketing_extra_seat_claim',
      'marketing_seat_entitlement'))=0,
  1,JSON_EXTRACT('ASSERT_FAILED_marketing_extra_seat_preflight','$')) AS UNSIGNED) AS assertion_ok;

ALTER TABLE marketing_product_order
    DROP CHECK ck_marketing_order_fulfillment;

ALTER TABLE marketing_product_order
    ADD CONSTRAINT ck_marketing_order_fulfillment CHECK (fulfillment_kind IN (1,2,3));

ALTER TABLE marketing_product_order
    ADD KEY idx_marketing_order_extra_seat (original_order_id, fulfillment_kind, status);

-- runner: assert=scalar-true
SELECT CAST(IF(
  (SELECT COUNT(*) FROM information_schema.check_constraints cc
    JOIN information_schema.table_constraints tc
      ON tc.constraint_schema=cc.constraint_schema AND tc.constraint_name=cc.constraint_name
    WHERE cc.constraint_schema=DATABASE()
      AND tc.table_name='marketing_product_order'
      AND cc.constraint_name='ck_marketing_order_fulfillment'
      AND REPLACE(REPLACE(LOWER(cc.check_clause),'`',''),' ','') LIKE '%in(1,2,3)%')=1
  AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='marketing_product_order' AND index_name='idx_marketing_order_extra_seat'
    AND seq_in_index=1 AND column_name='original_order_id')=1
  AND (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
    AND table_name IN ('marketing_extra_seat','marketing_extra_seat_order','marketing_extra_seat_claim',
      'marketing_seat_entitlement'))=0
  AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='marketing_product_order'
    AND column_name IN ('wallet_balance','seat_ledger_id','extra_seat_count'))=0,
  1,JSON_EXTRACT('ASSERT_FAILED_marketing_extra_seat_readback','$')) AS UNSIGNED) AS assertion_ok;
