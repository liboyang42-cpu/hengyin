-- ADDITIVE SOURCE ONLY: no production DDL/backfill is authorized or executed.
-- Preflight: MySQL8/InnoDB; SHOW CREATE TABLE cms_topic; the table/index/constraint names below must be absent.
-- No amount/receiver/default-policy inference. Original financial records stay unchanged.
CREATE TABLE coop_settlement_manual_case (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 case_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 topic_id BIGINT NOT NULL,
 reason VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 negative_amount DECIMAL(15,2) NOT NULL,
 source_snapshot LONGTEXT NOT NULL,
 state VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(id),
 UNIQUE KEY uk_coop_manual_case_identity(case_key),
 KEY ix_coop_manual_case_topic(topic_id,state,id),
 CONSTRAINT ck_coop_manual_case_payload CHECK (case_key REGEXP '^[0-9a-f]{64}$'
   AND JSON_VALID(source_snapshot) AND reason='NEGATIVE_ORGANIZER_MARGIN' AND state='MANUAL_REQUIRED' AND negative_amount<0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Deliberately no FK: this is immutable post-rollback control evidence, never a balance/head or fabricated registration.
-- Rollback: stop new settlement writes; retain this table and all case identities. No DROP/DELETE/automatic resolution.
-- No public resolution worker/dispatch is enabled. Human approval of a later resolution requires its own explicit contract.
