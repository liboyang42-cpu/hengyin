-- K2 discovery (2026-11-15, MKT-09): a shop-visit post may link a public merchant as well as a roam place.
--   community_post_reference.reference_type gains 'MERCHANT'. The CHECK constraint is replaced only when it does not
--   already allow MERCHANT, so this file can run again. No row is changed; existing references keep their values.
--   The reverse lookup (posts linked to one shop) uses the existing idx_community_post_reference_target.
--
-- Rollback (only after deleting or unlinking MERCHANT references, otherwise the ADD fails):
--   DELETE FROM community_post_reference WHERE reference_type='MERCHANT';
--   ALTER TABLE community_post_reference DROP CHECK chk_community_post_reference_type,
--     ADD CONSTRAINT chk_community_post_reference_type CHECK (reference_type IN ('ACTIVITY','TOPIC','ROUTE','CLUB','POI'));
-- Deploy order: run this before the jar that accepts MERCHANT; the old jar never writes MERCHANT, so either order is safe
-- for existing posts.

SET @discovery_ref_exists := (
    SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE constraint_schema = DATABASE() AND table_name = 'community_post_reference'
      AND constraint_name = 'chk_community_post_reference_type' AND constraint_type = 'CHECK'
);
SET @discovery_ref_has_merchant := (
    SELECT COUNT(*) FROM information_schema.check_constraints
    WHERE constraint_schema = DATABASE() AND constraint_name = 'chk_community_post_reference_type'
      AND UPPER(check_clause) LIKE '%MERCHANT%'
);
SET @discovery_ref_sql := IF(
    @discovery_ref_exists = 1 AND @discovery_ref_has_merchant = 0,
    'ALTER TABLE community_post_reference DROP CHECK chk_community_post_reference_type, ADD CONSTRAINT chk_community_post_reference_type CHECK (reference_type IN (''ACTIVITY'',''TOPIC'',''ROUTE'',''CLUB'',''POI'',''MERCHANT''))',
    'SELECT ''chk_community_post_reference_type already allows MERCHANT or is absent, skipped''');
PREPARE discovery_ref_stmt FROM @discovery_ref_sql;
EXECUTE discovery_ref_stmt;
DEALLOCATE PREPARE discovery_ref_stmt;
