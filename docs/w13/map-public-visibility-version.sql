-- CANDIDATE ONLY. Not applied. Not a migration-runner input. Do not execute.
-- roam_poi in sql/base_schema_20260818.sql has create_time and no update_time or version.
-- mms_merchant.update_time and cms_activity.update_time already exist; the read uses those
-- columns and does not add a column for store or activity.
-- Do not assign a constant default. A null visibility_version must not be published as a made-up version.

ALTER TABLE roam_poi
  ADD COLUMN visibility_version BIGINT NULL
  COMMENT '公开地图逐项可见版本。禁止默认 1。空值不得进入公开条目。';

UPDATE roam_poi
   SET visibility_version = UNIX_TIMESTAMP(create_time)
 WHERE visibility_version IS NULL
   AND create_time IS NOT NULL;

-- Rows that still have no create_time stay NULL. The read refuses them.

DROP TRIGGER IF EXISTS roam_poi_visibility_version_bi;
DROP TRIGGER IF EXISTS roam_poi_visibility_version_bu;

CREATE TRIGGER roam_poi_visibility_version_bi
BEFORE INSERT ON roam_poi
FOR EACH ROW
SET NEW.visibility_version = IFNULL(NEW.visibility_version, UNIX_TIMESTAMP(NOW()));

CREATE TRIGGER roam_poi_visibility_version_bu
BEFORE UPDATE ON roam_poi
FOR EACH ROW
SET NEW.visibility_version = IF(
  NEW.status <=> OLD.status
  AND NEW.audit_status <=> OLD.audit_status
  AND NEW.application_type <=> OLD.application_type
  AND NEW.lat <=> OLD.lat
  AND NEW.lng <=> OLD.lng
  AND NEW.name <=> OLD.name
  AND NEW.cover_img <=> OLD.cover_img
  AND NEW.address <=> OLD.address
  AND NEW.type <=> OLD.type
  AND NEW.merchant_id <=> OLD.merchant_id,
  OLD.visibility_version,
  GREATEST(IFNULL(OLD.visibility_version, UNIX_TIMESTAMP(NOW())), UNIX_TIMESTAMP(NOW())) + 1
);
