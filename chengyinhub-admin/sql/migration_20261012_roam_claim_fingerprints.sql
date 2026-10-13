-- No raw location is copied into the guard. The application sets a key-derived sentinel once.
CREATE TABLE IF NOT EXISTS roam_claim_key_guard (
  id TINYINT NOT NULL PRIMARY KEY,
  key_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  create_time DATETIME NOT NULL
) ENGINE=InnoDB;

SET @fog_tile_fp_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='roam_fog_tile' AND column_name='claim_fingerprint');
SET @fog_tile_fp_ddl = IF(@fog_tile_fp_exists=0, 'ALTER TABLE roam_fog_tile ADD COLUMN claim_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL', 'SELECT 1');
PREPARE retention_statement FROM @fog_tile_fp_ddl;
EXECUTE retention_statement;
DEALLOCATE PREPARE retention_statement;
SET @fog_tile_nullable = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='roam_fog_tile' AND column_name='geohash7' AND is_nullable='YES');
SET @fog_tile_null_ddl = IF(@fog_tile_nullable=0, 'ALTER TABLE roam_fog_tile MODIFY COLUMN geohash7 VARCHAR(12) NULL', 'SELECT 1');
PREPARE retention_statement FROM @fog_tile_null_ddl;
EXECUTE retention_statement;
DEALLOCATE PREPARE retention_statement;
-- runner: assert=scalar-true
SELECT COUNT(*)=1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='roam_fog_tile' AND column_name='claim_fingerprint' AND data_type='char' AND character_maximum_length=64 AND is_nullable='YES' AND collation_name='ascii_bin';
-- runner: assert=scalar-true
SELECT COUNT(*)=1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='roam_fog_tile' AND column_name='geohash7' AND is_nullable='YES';
SET @fog_tile_index_exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='roam_fog_tile' AND index_name='uk_member_claim');
SET @fog_tile_index_ddl = IF(@fog_tile_index_exists=0, 'ALTER TABLE roam_fog_tile ADD UNIQUE KEY uk_member_claim(member_id,claim_fingerprint)', 'SELECT 1');
PREPARE retention_statement FROM @fog_tile_index_ddl;
EXECUTE retention_statement;
DEALLOCATE PREPARE retention_statement;
-- runner: assert=scalar-true
SELECT COUNT(*)=2 AND SUM(non_unique=0 AND ((seq_in_index=1 AND column_name='member_id') OR (seq_in_index=2 AND column_name='claim_fingerprint')))=2 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='roam_fog_tile' AND index_name='uk_member_claim';

SET @poi_found_fp_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='roam_poi_found' AND column_name='claim_fingerprint');
SET @poi_found_fp_ddl = IF(@poi_found_fp_exists=0, 'ALTER TABLE roam_poi_found ADD COLUMN claim_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL', 'SELECT 1');
PREPARE retention_statement FROM @poi_found_fp_ddl;
EXECUTE retention_statement;
DEALLOCATE PREPARE retention_statement;
SET @poi_found_nullable = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='roam_poi_found' AND column_name='poi_id' AND is_nullable='YES');
SET @poi_found_null_ddl = IF(@poi_found_nullable=0, 'ALTER TABLE roam_poi_found MODIFY COLUMN poi_id BIGINT NULL', 'SELECT 1');
PREPARE retention_statement FROM @poi_found_null_ddl;
EXECUTE retention_statement;
DEALLOCATE PREPARE retention_statement;
-- runner: assert=scalar-true
SELECT COUNT(*)=1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='roam_poi_found' AND column_name='claim_fingerprint' AND data_type='char' AND character_maximum_length=64 AND is_nullable='YES' AND collation_name='ascii_bin';
-- runner: assert=scalar-true
SELECT COUNT(*)=1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='roam_poi_found' AND column_name='poi_id' AND is_nullable='YES';
SET @poi_found_index_exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='roam_poi_found' AND index_name='uk_member_claim');
SET @poi_found_index_ddl = IF(@poi_found_index_exists=0, 'ALTER TABLE roam_poi_found ADD UNIQUE KEY uk_member_claim(member_id,claim_fingerprint)', 'SELECT 1');
PREPARE retention_statement FROM @poi_found_index_ddl;
EXECUTE retention_statement;
DEALLOCATE PREPARE retention_statement;
-- runner: assert=scalar-true
SELECT COUNT(*)=2 AND SUM(non_unique=0 AND ((seq_in_index=1 AND column_name='member_id') OR (seq_in_index=2 AND column_name='claim_fingerprint')))=2 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='roam_poi_found' AND index_name='uk_member_claim';

SET @shop_visit_fp_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='roam_shop_visit' AND column_name='claim_fingerprint');
SET @shop_visit_fp_ddl = IF(@shop_visit_fp_exists=0, 'ALTER TABLE roam_shop_visit ADD COLUMN claim_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL', 'SELECT 1');
PREPARE retention_statement FROM @shop_visit_fp_ddl;
EXECUTE retention_statement;
DEALLOCATE PREPARE retention_statement;
SET @shop_visit_nullable = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='roam_shop_visit' AND column_name='source_id' AND is_nullable='YES');
SET @shop_visit_null_ddl = IF(@shop_visit_nullable=0, 'ALTER TABLE roam_shop_visit MODIFY COLUMN source_id BIGINT NULL', 'SELECT 1');
PREPARE retention_statement FROM @shop_visit_null_ddl;
EXECUTE retention_statement;
DEALLOCATE PREPARE retention_statement;
-- runner: assert=scalar-true
SELECT COUNT(*)=1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='roam_shop_visit' AND column_name='claim_fingerprint' AND data_type='char' AND character_maximum_length=64 AND is_nullable='YES' AND collation_name='ascii_bin';
-- runner: assert=scalar-true
SELECT COUNT(*)=1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='roam_shop_visit' AND column_name='source_id' AND is_nullable='YES';

SET @reward_log_fp_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='roam_poi_reward_log' AND column_name='claim_fingerprint');
SET @reward_log_fp_ddl = IF(@reward_log_fp_exists=0, 'ALTER TABLE roam_poi_reward_log ADD COLUMN claim_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL', 'SELECT 1');
PREPARE retention_statement FROM @reward_log_fp_ddl;
EXECUTE retention_statement;
DEALLOCATE PREPARE retention_statement;
SET @reward_log_nullable = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='roam_poi_reward_log' AND column_name='poi_id' AND is_nullable='YES');
SET @reward_log_null_ddl = IF(@reward_log_nullable=0, 'ALTER TABLE roam_poi_reward_log MODIFY COLUMN poi_id BIGINT NULL', 'SELECT 1');
PREPARE retention_statement FROM @reward_log_null_ddl;
EXECUTE retention_statement;
DEALLOCATE PREPARE retention_statement;
-- runner: assert=scalar-true
SELECT COUNT(*)=1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='roam_poi_reward_log' AND column_name='claim_fingerprint' AND data_type='char' AND character_maximum_length=64 AND is_nullable='YES' AND collation_name='ascii_bin';
-- runner: assert=scalar-true
SELECT COUNT(*)=1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='roam_poi_reward_log' AND column_name='poi_id' AND is_nullable='YES';
SET @reward_log_index_exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='roam_poi_reward_log' AND index_name='uk_member_claim');
SET @reward_log_index_ddl = IF(@reward_log_index_exists=0, 'ALTER TABLE roam_poi_reward_log ADD UNIQUE KEY uk_member_claim(member_id,claim_fingerprint)', 'SELECT 1');
PREPARE retention_statement FROM @reward_log_index_ddl;
EXECUTE retention_statement;
DEALLOCATE PREPARE retention_statement;
-- runner: assert=scalar-true
SELECT COUNT(*)=2 AND SUM(non_unique=0 AND ((seq_in_index=1 AND column_name='member_id') OR (seq_in_index=2 AND column_name='claim_fingerprint')))=2 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='roam_poi_reward_log' AND index_name='uk_member_claim';

