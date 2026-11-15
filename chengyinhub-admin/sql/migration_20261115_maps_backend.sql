-- ================= migration_20261115_maps_backend.sql =================
-- T2 maps-backend (PERF-17): indexes for the map reads by bounding box. Indexes only: no column, no data change.
--
-- ROLLBACK:
--   ALTER TABLE roam_poi DROP INDEX idx_roam_poi_player_geo;
--   ALTER TABLE cms_activity DROP INDEX idx_cms_activity_club_start;
--   (Safe: the reads keep working without them, only slower.)
--
-- 1. roam_poi.idx_roam_poi_player_geo (status, lat, lng, application_type, audit_status)
--    Covers the inner query of /api/roam/pois (RoamMapper.selectPoisNearFiltered) and the cache tile load
--    (selectPoiBaseInBox): equality on status, range on lat, lng and the approval gate checked inside the index,
--    id from the primary key. MySQL 8.0.45 production: composite B-tree index on latitude/longitude was chosen
--    over a geohash column because every writer of roam_poi (admin, merchant applications, seed import,
--    city nodes) would have to keep a geohash column in step; the existing idx_geo (lat, lng) stays for other reads.
--    The sponsored-quest and deals box reads reach roam_poi by primary key / merchant_id and need nothing new.
-- 2. cms_activity.idx_cms_activity_club_start (club_id, start_date, id)
--    /api/club/nearby (ClubOpsMapper.selectLocatedClubs) finds each club's latest located event with
--    "where club_id = ? ... order by start_date desc, id desc limit 1"; without it every club scans cms_activity.
--
-- Online DDL: ADD INDEX on InnoDB is ALGORITHM=INPLACE, LOCK=NONE (reads and writes continue). Time it on TEST first.
-- Guarded by information_schema, so the file can run again.

SET @cy_sql := IF((SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'roam_poi' AND index_name = 'idx_roam_poi_player_geo') = 0,
  'ALTER TABLE roam_poi ADD INDEX idx_roam_poi_player_geo (status, lat, lng, application_type, audit_status), ALGORITHM=INPLACE, LOCK=NONE',
  'SELECT 1');
PREPARE cy_stmt FROM @cy_sql;
EXECUTE cy_stmt;
DEALLOCATE PREPARE cy_stmt;

SET @cy_sql := IF((SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'cms_activity' AND index_name = 'idx_cms_activity_club_start') = 0,
  'ALTER TABLE cms_activity ADD INDEX idx_cms_activity_club_start (club_id, start_date, id), ALGORITHM=INPLACE, LOCK=NONE',
  'SELECT 1');
PREPARE cy_stmt FROM @cy_sql;
EXECUTE cy_stmt;
DEALLOCATE PREPARE cy_stmt;

-- runner: assert=scalar-true
SELECT COUNT(DISTINCT index_name) = 2 FROM information_schema.statistics WHERE table_schema = DATABASE()
  AND ((table_name = 'roam_poi' AND index_name = 'idx_roam_poi_player_geo')
    OR (table_name = 'cms_activity' AND index_name = 'idx_cms_activity_club_start'));
