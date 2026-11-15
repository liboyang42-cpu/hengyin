-- ================= migration_20261115_privacy_erasure.sql =================
SELECT 'migration_20261115_privacy_erasure.sql' AS running;
-- R13 privacy-erasure (2026-11-15): one-off catch-up for rows that the new erasure code would have removed if it had
-- been running when they were written. Schema is not changed.
--
-- Why: migration_20261115_fog_map.sql backfilled kept fog cells (roam_member_fog_cell) for EVERY member with tiles in
-- the last 30 days, including members whose latest roam location consent is a withdrawal and accounts already
-- deleted (ums_member.del_flag = 2). From this build on, withdrawal and deletion remove those rows in the app; this
-- file removes the ones that already exist. The roadmap tables of the same day (install link, push device, location
-- trust) get the same treatment for deleted accounts.
--
-- ROLLBACK: none possible and none needed. Rows removed here are personal data the member asked to remove; there is
-- no structural change to undo. Take the usual backup before the batch if a restore point is wanted.
-- Idempotent: every statement only matches rows that still need it; re-running changes nothing.
-- Depends on: 20261115_fog_map, 20261115_measurement, 20261115_links_push, 20261115_location_trust,
--             compliance_consent_deregister_20260711 (consent_record).

-- 1) Kept fog cells of members whose latest roam location consent is a withdrawal.
DELETE c FROM roam_member_fog_cell c
JOIN (
  SELECT r.member_id
  FROM consent_record r
  JOIN (
    SELECT member_id, MAX(id) AS latest_id
    FROM consent_record
    WHERE doc_type = 'privacy_policy' AND scene = 'roam_location' AND scope_type IS NULL AND scope_id IS NULL
    GROUP BY member_id
  ) latest ON latest.latest_id = r.id
  WHERE r.event_type = 'REVOKE'
) revoked ON revoked.member_id = c.member_id;

-- 2) Kept fog cells of deleted accounts.
DELETE c FROM roam_member_fog_cell c
JOIN ums_member m ON m.id = c.member_id
WHERE m.del_flag = 2;

-- 3) Install-id links and push devices of deleted accounts.
DELETE l FROM app_install_link l
JOIN ums_member m ON m.id = l.member_id
WHERE m.del_flag = 2;

DELETE d FROM push_device d
JOIN ums_member m ON m.id = d.member_id
WHERE m.del_flag = 2;

-- 4) Location trust trail of deleted accounts; claim evidence keeps its photo hashes but loses the member link.
DELETE f FROM location_trust_fix f
JOIN ums_member m ON m.id = f.member_id
WHERE m.del_flag = 2;

UPDATE location_claim_evidence e
JOIN ums_member m ON m.id = e.member_id
SET e.member_id = 0, e.photo_lat = NULL, e.photo_lng = NULL, e.fix_id = NULL, e.consumed_ref = NULL
WHERE m.del_flag = 2;
