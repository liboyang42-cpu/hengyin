-- PRE_DEPLOY; depends on roam_shop_visit and migration_20261012_roam_claim_fingerprints.sql.
-- No raw member ID / coordinate / public fingerprint. HMAC uses the existing guarded PII key.
-- Existing facts are unioned on bounded shop-card reads and atomically registered before raw erasure.
CREATE TABLE IF NOT EXISTS roam_shop_visitor_claim (
 source_type TINYINT NOT NULL,
 source_id BIGINT NOT NULL,
 visitor_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 PRIMARY KEY(source_type,source_id,visitor_fingerprint)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Nonpublic anonymous cumulative shop visitors';
CREATE TABLE IF NOT EXISTS roam_shop_visitor_total (
 source_type TINYINT NOT NULL,
 source_id BIGINT NOT NULL,
 total BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(source_type,source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Anonymous cumulative distinct visitor count';
