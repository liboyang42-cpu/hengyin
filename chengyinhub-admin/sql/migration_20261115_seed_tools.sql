-- Q3 seed-tools (GAM-09 / POS-09): official place bulk import keeps category and opening hours.
-- Additive and idempotent: two nullable columns on roam_poi, nothing else changes.
-- Imported places land as status=0 / audit_status=3 / application_type=3 (hidden import drafts, outside
-- the pending queue and blocked from the plain "上架" switch); the existing official editor moves them to
-- audit_status=0 once a game is attached, and only the existing approve step makes them visible.
--
-- Rollback (only if no import has been used yet; dropping loses imported category / opening hours):
--   ALTER TABLE roam_poi DROP COLUMN opening_hours;
--   ALTER TABLE roam_poi DROP COLUMN category;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='roam_poi' AND column_name='category')=0,
  'ALTER TABLE roam_poi ADD COLUMN category VARCHAR(40) NULL COMMENT ''官方地点分类(批量导入)''', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='roam_poi' AND column_name='opening_hours')=0,
  'ALTER TABLE roam_poi ADD COLUMN opening_hours VARCHAR(120) NULL COMMENT ''开放时间(批量导入)''', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
