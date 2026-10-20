-- Inactive FREE/INDIVIDUAL registry and grants. No ingestion, route, deployment or payment activation.
-- Immutable reviewed publication bytes are separate from mutable listing/safety policy.
-- No production writer is provided until issuer/reviewer authority and immutable ingestion are integrated.
CREATE TABLE IF NOT EXISTS w18_free_publication (
  publication_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  ownership_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  seller_member_id BIGINT NOT NULL,
  source_template_id BIGINT NOT NULL,
  document_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  snapshot_json MEDIUMTEXT NOT NULL,
  content MEDIUMBLOB NOT NULL,
  terms BLOB NOT NULL,
  grant_valid_until DATETIME(6) NOT NULL,
  PRIMARY KEY(publication_id),
  UNIQUE KEY uk_w18_free_publication_identity(ownership_key),
  UNIQUE KEY uk_w18_free_publication_digest(publication_id,document_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS w18_free_publication_policy (
  publication_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  state VARCHAR(24) NOT NULL,
  PRIMARY KEY(publication_id),
  CONSTRAINT fk_w18_free_policy_publication FOREIGN KEY(publication_id) REFERENCES w18_free_publication(publication_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS w18_free_claim (
  claim_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  buyer_member_id BIGINT NOT NULL,
  request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  publication_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  document_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  state VARCHAR(16) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  valid_until DATETIME(6) NOT NULL,
  PRIMARY KEY(claim_id),
  UNIQUE KEY uk_w18_free_claim_request(buyer_member_id,request_key),
  UNIQUE KEY uk_w18_free_claim_owned(buyer_member_id,publication_id),
  CONSTRAINT fk_w18_free_claim_publication FOREIGN KEY(publication_id,document_hash)
    REFERENCES w18_free_publication(publication_id,document_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
-- Existing-schema invariants: CREATE IF NOT EXISTS never repairs drift.
-- Every scalar must be checked by the repository migration runner; 0/NULL is failure.
-- Explicit metadata comparisons avoid canonical CHECK text/hash assumptions and preserve literals.
-- runner: assert=scalar-true
SELECT ((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('w18_free_publication','w18_free_publication_policy','w18_free_claim') AND engine='InnoDB'
  AND table_collation='utf8mb4_bin' AND table_type='BASE TABLE')=3) AS migration_assertion;

-- runner: assert=scalar-true
SELECT ((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name IN ('w18_free_publication','w18_free_publication_policy','w18_free_claim'))=19
  AND (SELECT COUNT(*) FROM (
    SELECT 'w18_free_publication' AS table_name, 'publication_id' AS column_name, 'varchar' AS data_type, 128 AS char_length, 'ascii_bin' AS collation_name, 1 AS ordinal_position
    UNION ALL
    SELECT 'w18_free_publication', 'ownership_key', 'char', 64, 'ascii_bin', 2
    UNION ALL
    SELECT 'w18_free_publication', 'seller_member_id', 'bigint', NULL, NULL, 3
    UNION ALL
    SELECT 'w18_free_publication', 'source_template_id', 'bigint', NULL, NULL, 4
    UNION ALL
    SELECT 'w18_free_publication', 'document_hash', 'char', 64, 'ascii_bin', 5
    UNION ALL
    SELECT 'w18_free_publication', 'snapshot_json', 'mediumtext', NULL, 'utf8mb4_bin', 6
    UNION ALL
    SELECT 'w18_free_publication', 'content', 'mediumblob', NULL, NULL, 7
    UNION ALL
    SELECT 'w18_free_publication', 'terms', 'blob', NULL, NULL, 8
    UNION ALL
    SELECT 'w18_free_publication', 'grant_valid_until', 'datetime', NULL, NULL, 9
    UNION ALL
    SELECT 'w18_free_publication_policy', 'publication_id', 'varchar', 128, 'ascii_bin', 1
    UNION ALL
    SELECT 'w18_free_publication_policy', 'state', 'varchar', 24, 'utf8mb4_bin', 2
    UNION ALL
    SELECT 'w18_free_claim', 'claim_id', 'varchar', 36, 'ascii_bin', 1
    UNION ALL
    SELECT 'w18_free_claim', 'buyer_member_id', 'bigint', NULL, NULL, 2
    UNION ALL
    SELECT 'w18_free_claim', 'request_key', 'varchar', 128, 'ascii_bin', 3
    UNION ALL
    SELECT 'w18_free_claim', 'publication_id', 'varchar', 128, 'ascii_bin', 4
    UNION ALL
    SELECT 'w18_free_claim', 'document_hash', 'char', 64, 'ascii_bin', 5
    UNION ALL
    SELECT 'w18_free_claim', 'state', 'varchar', 16, 'utf8mb4_bin', 6
    UNION ALL
    SELECT 'w18_free_claim', 'created_at', 'datetime', NULL, NULL, 7
    UNION ALL
    SELECT 'w18_free_claim', 'valid_until', 'datetime', NULL, NULL, 8
  ) expected JOIN information_schema.columns actual
    ON actual.table_schema=DATABASE() AND actual.table_name=expected.table_name AND actual.column_name=expected.column_name
    AND actual.data_type=expected.data_type AND actual.ordinal_position=expected.ordinal_position
    AND actual.is_nullable='NO' AND actual.column_default IS NULL AND actual.extra='' AND actual.generation_expression=''
    AND (actual.collation_name <=> expected.collation_name)
    AND (expected.char_length IS NULL OR actual.character_maximum_length=expected.char_length)
    AND (expected.data_type!='datetime' OR actual.datetime_precision=6)
    AND (expected.data_type!='bigint' OR actual.column_type='bigint'))=19) AS migration_assertion;

-- runner: assert=scalar-true
SELECT ((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name IN ('w18_free_publication','w18_free_publication_policy','w18_free_claim') AND non_unique=0)=10
  AND (SELECT COUNT(*) FROM (
    SELECT 'w18_free_publication' AS table_name, 'PRIMARY' AS index_name, 'publication_id' AS column_name, 1 AS seq_in_index
    UNION ALL
    SELECT 'w18_free_publication', 'uk_w18_free_publication_identity', 'ownership_key', 1
    UNION ALL
    SELECT 'w18_free_publication', 'uk_w18_free_publication_digest', 'publication_id', 1
    UNION ALL
    SELECT 'w18_free_publication', 'uk_w18_free_publication_digest', 'document_hash', 2
    UNION ALL
    SELECT 'w18_free_publication_policy', 'PRIMARY', 'publication_id', 1
    UNION ALL
    SELECT 'w18_free_claim', 'PRIMARY', 'claim_id', 1
    UNION ALL
    SELECT 'w18_free_claim', 'uk_w18_free_claim_request', 'buyer_member_id', 1
    UNION ALL
    SELECT 'w18_free_claim', 'uk_w18_free_claim_request', 'request_key', 2
    UNION ALL
    SELECT 'w18_free_claim', 'uk_w18_free_claim_owned', 'buyer_member_id', 1
    UNION ALL
    SELECT 'w18_free_claim', 'uk_w18_free_claim_owned', 'publication_id', 2
  ) expected JOIN information_schema.statistics actual
    ON actual.table_schema=DATABASE() AND actual.table_name=expected.table_name AND actual.index_name=expected.index_name
    AND actual.column_name=expected.column_name AND actual.seq_in_index=expected.seq_in_index
    AND actual.non_unique=0 AND actual.sub_part IS NULL AND actual.collation='A'
    AND actual.index_type='BTREE' AND actual.is_visible='YES' AND actual.expression IS NULL)=10) AS migration_assertion;

-- runner: assert=scalar-true
SELECT ((SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE()
    AND table_name IN ('w18_free_publication_policy','w18_free_claim'))=2
  AND (SELECT COUNT(*) FROM information_schema.key_column_usage WHERE table_schema=DATABASE() AND table_name IN ('w18_free_publication','w18_free_publication_policy','w18_free_claim') AND referenced_table_name IS NOT NULL)=3
  AND (SELECT COUNT(*) FROM (
    SELECT 'w18_free_publication_policy' AS table_name, 'fk_w18_free_policy_publication' AS constraint_name,
      'publication_id' AS column_name, 1 AS ordinal_position
    UNION ALL SELECT 'w18_free_claim','fk_w18_free_claim_publication','publication_id',1
    UNION ALL SELECT 'w18_free_claim','fk_w18_free_claim_publication','document_hash',2
  ) expected JOIN information_schema.key_column_usage actual
    ON actual.constraint_schema=DATABASE() AND actual.table_schema=DATABASE()
    AND actual.table_name=expected.table_name AND actual.constraint_name=expected.constraint_name
    AND actual.column_name=expected.column_name AND actual.ordinal_position=expected.ordinal_position
    AND actual.position_in_unique_constraint=expected.ordinal_position
    AND actual.referenced_table_schema=DATABASE() AND actual.referenced_table_name='w18_free_publication'
    AND actual.referenced_column_name=expected.column_name
  JOIN information_schema.referential_constraints rules
    ON rules.constraint_schema=actual.constraint_schema AND rules.constraint_name=actual.constraint_name
    AND rules.table_name=actual.table_name AND rules.match_option='NONE'
    AND rules.delete_rule IN ('RESTRICT','NO ACTION') AND rules.update_rule IN ('RESTRICT','NO ACTION'))=3) AS migration_assertion;
