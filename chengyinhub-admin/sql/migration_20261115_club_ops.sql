-- ================= migration_20261115_club_ops.sql =================
-- club-ops (R8 roadmap, CLB-06): optional pinned location for a club, used by POST /api/club/nearby
-- and the clubs layer on the roam map.
--
-- ROLLBACK:
--   ALTER TABLE club DROP COLUMN location_lat, DROP COLUMN location_lng;
--   (Safe: only /api/club/nearby reads them; without them it falls back to each club's latest located event.)
--
-- Two nullable columns, guarded by information_schema, so the file can run again. No backfill:
-- clubs without a pinned point keep working through their events' coordinates. Ops may fill the pair
-- (GCJ-02) from the club address with the map geocoder; nothing here calls an outside service.
-- No money, inventory, settlement or payment tables are touched.

SET @cy_has_club_lat := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'club' AND column_name = 'location_lat');
SET @cy_sql := IF(@cy_has_club_lat = 0,
  'ALTER TABLE club ADD COLUMN location_lat decimal(10,7) DEFAULT NULL COMMENT ''pinned latitude from the club address (GCJ-02)''',
  'SELECT 1');
PREPARE cy_stmt FROM @cy_sql;
EXECUTE cy_stmt;
DEALLOCATE PREPARE cy_stmt;

SET @cy_has_club_lng := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'club' AND column_name = 'location_lng');
SET @cy_sql := IF(@cy_has_club_lng = 0,
  'ALTER TABLE club ADD COLUMN location_lng decimal(10,7) DEFAULT NULL COMMENT ''pinned longitude from the club address (GCJ-02)''',
  'SELECT 1');
PREPARE cy_stmt FROM @cy_sql;
EXECUTE cy_stmt;
DEALLOCATE PREPARE cy_stmt;
