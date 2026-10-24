-- Source-only ordered migration. No historical inference, DML/backfill, DROP or production execution.
-- Dependencies: migration_club_role_membership_20260823.sql;
-- migration_commerce_order_fields_and_verification_review_20260805.sql;
-- migration_20261024_wx_verified_payment_fact.sql (original unsigned fact PK).
-- Preflight ALL columns/indexes/schema-global FK+CHECK names BEFORE either complete ALTER.
-- runner: assert=scalar-true
SELECT CAST(IF(DATABASE() IS NOT NULL
  AND CAST(SUBSTRING_INDEX(VERSION(),'.',1) AS UNSIGNED)=8
  AND (CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(VERSION(),'.',2),'.',-1) AS UNSIGNED)>0
    OR CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(VERSION(),'-',1),'.',-1) AS UNSIGNED)>=16)
  AND (FIND_IN_SET('STRICT_TRANS_TABLES',@@sql_mode)>0 OR FIND_IN_SET('STRICT_ALL_TABLES',@@sql_mode)>0)
  AND (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
    AND table_name IN ('club_membership_invoice','oms_order','wx_verified_payment_fact') AND UPPER(engine)='INNODB')=3
  AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='wx_verified_payment_fact' AND column_name='id' AND column_type='bigint unsigned')=1
  AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name IN ('club_membership_invoice','oms_order')
    AND column_name IN ('verified_payment_fact_id','verified_payment_fact_hash'))=0
  AND (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()
    AND constraint_name IN ('ck_clm_payment_proof','ck_commerce_payment_proof','fk_clm_payment_proof','fk_commerce_payment_proof'))=0
  AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND ((table_name='club_membership_invoice' AND index_name='uk_clm_payment_proof')
      OR (table_name='oms_order' AND index_name='uk_commerce_payment_proof')))=0,
  1,JSON_EXTRACT('ASSERT_FAILED_domain_payment_proof_preflight','$')) AS UNSIGNED) AS assertion_ok;

ALTER TABLE club_membership_invoice
  ADD COLUMN verified_payment_fact_id BIGINT UNSIGNED NULL,
  ADD COLUMN verified_payment_fact_hash VARBINARY(64) NULL,
  ADD UNIQUE KEY uk_clm_payment_proof (verified_payment_fact_id),
  ADD CONSTRAINT fk_clm_payment_proof FOREIGN KEY (verified_payment_fact_id) REFERENCES wx_verified_payment_fact(id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT ck_clm_payment_proof CHECK (CASE WHEN
    (verified_payment_fact_id IS NULL AND verified_payment_fact_hash IS NULL)
    OR (verified_payment_fact_id>0 AND verified_payment_fact_hash IS NOT NULL
      AND OCTET_LENGTH(verified_payment_fact_hash)=64
      AND REGEXP_LIKE(CONVERT(verified_payment_fact_hash USING ascii),'^[0-9a-f]{64}$','c')) THEN 1 ELSE 0 END=1);

ALTER TABLE oms_order
  ADD COLUMN verified_payment_fact_id BIGINT UNSIGNED NULL,
  ADD COLUMN verified_payment_fact_hash VARBINARY(64) NULL,
  ADD UNIQUE KEY uk_commerce_payment_proof (verified_payment_fact_id),
  ADD CONSTRAINT fk_commerce_payment_proof FOREIGN KEY (verified_payment_fact_id) REFERENCES wx_verified_payment_fact(id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  ADD CONSTRAINT ck_commerce_payment_proof CHECK (CASE WHEN
    (verified_payment_fact_id IS NULL AND verified_payment_fact_hash IS NULL)
    OR (verified_payment_fact_id>0 AND verified_payment_fact_hash IS NOT NULL
      AND OCTET_LENGTH(verified_payment_fact_hash)=64
      AND REGEXP_LIKE(CONVERT(verified_payment_fact_hash USING ascii),'^[0-9a-f]{64}$','c')) THEN 1 ELSE 0 END=1);

-- Two ALTERs are not transactionally atomic. If the first commits and second fails: keep all data,
-- inventory SHOW CREATE/columns/indexes/FK/CHECKs; never rerun the whole preflight or silently IF NOT EXISTS.
-- Only the exact outstanding second statement may resume after independent review. No DROP/clear rollback.
-- runner: assert=scalar-true
SELECT CAST(IF(
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name IN ('club_membership_invoice','oms_order') AND column_name='verified_payment_fact_id'
    AND column_type='bigint unsigned' AND is_nullable='YES')=2
  AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name IN ('club_membership_invoice','oms_order') AND column_name='verified_payment_fact_hash'
    AND data_type='varbinary' AND character_maximum_length=64 AND is_nullable='YES')=2
  AND (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()
    AND constraint_name IN ('ck_clm_payment_proof','ck_commerce_payment_proof') AND enforced='YES')=2
  AND (SELECT COUNT(*) FROM information_schema.key_column_usage WHERE constraint_schema=DATABASE()
    AND constraint_name IN ('fk_clm_payment_proof','fk_commerce_payment_proof')
    AND column_name='verified_payment_fact_id' AND referenced_table_name='wx_verified_payment_fact' AND referenced_column_name='id')=2
  AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND index_name IN ('uk_clm_payment_proof','uk_commerce_payment_proof')
    AND column_name='verified_payment_fact_id' AND non_unique=0)=2,
  1,JSON_EXTRACT('ASSERT_FAILED_domain_payment_proof_readback','$')) AS UNSIGNED) AS assertion_ok;
