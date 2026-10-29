-- SOURCE-ONLY proposal. No DDL executed; integrator owns approval, registry and isolated preflight.
-- Prerequisite: migration_20261024_wx_verified_payment_fact.sql.
-- Preflight: SHOW CREATE TABLE wx_verified_payment_fact; SHOW INDEX FROM wx_verified_payment_fact;
-- confirm ix_wx_fact_business absent, then EXPLAIN the business_out_trade_no guard query.
-- All merchant evidence for the same business must be visible: a config change cannot hide prior success.
ALTER TABLE wx_verified_payment_fact
    ADD INDEX ix_wx_fact_business (business_out_trade_no,mchid);
-- Code remains correct without this additive index; it introduces no economic field or identity release.
-- Rollback may retain the index. Never delete facts/receipts/intent identities to roll back application code.
