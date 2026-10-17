-- Forward-only ROUTE repair. Never edit/replay the old backfill to repair collisions.
-- Before execution: retain a complete backup, its SHA and an independent restored readback.
-- The versioned runner rejects legacy identity collisions before all DDL/DML.
-- No posts, assets, comments or actions are deleted or reassigned.
-- runner: requires=transaction

CREATE TEMPORARY TABLE community_legacy_route_assert (
  assertion_ok TINYINT NOT NULL CHECK (assertion_ok=1)
);

INSERT INTO community_legacy_route_assert (assertion_ok)
SELECT NOT EXISTS (
  SELECT 1 FROM community_post_reference ref
  WHERE ref.reference_type='ROUTE' AND JSON_EXTRACT(ref.snapshot_json,'$.legacy')=TRUE
    AND NOT EXISTS (
      SELECT 1 FROM ums_creative_square source JOIN community_post post ON post.id=source.id
      WHERE source.data_type=3 AND source.data_id=ref.reference_id AND ref.post_id=post.id
        AND source.member_id=post.author_id AND post.client_request_id=CONCAT('legacy-square-',source.id)
    )
);

CREATE TEMPORARY TABLE community_legacy_route_repair AS
SELECT ref.id,
       CASE WHEN route.id IS NOT NULL THEN JSON_OBJECT(
         'legacy',TRUE,'reference_id',route.id,'reference_type','ROUTE','title','城市漫游记录',
         'subtitle',CONCAT(COALESCE(route.distance_m,0),' 米 · ',COALESCE(route.pois_found,0),' 个地点'),
         'distance_m',route.distance_m,'tiles_revealed',route.tiles_revealed,
         'pois_found',route.pois_found,'finished_at',route.finished_at)
         ELSE ref.snapshot_json END AS repaired_snapshot,
       CASE WHEN route.id IS NULL THEN COALESCE(ref.invalidated_at,NOW())
            ELSE ref.invalidated_at END AS repaired_invalidated_at
FROM community_post_reference ref
JOIN ums_creative_square source ON source.id=ref.post_id
JOIN community_post post ON post.id=source.id AND post.author_id=source.member_id
  AND post.client_request_id=CONCAT('legacy-square-',source.id)
LEFT JOIN roam_session route ON route.id=source.data_id AND route.member_id=post.author_id AND route.status=1
WHERE ref.reference_type='ROUTE' AND source.data_type=3 AND ref.reference_id=source.data_id
  AND JSON_EXTRACT(ref.snapshot_json,'$.legacy')=TRUE;

UPDATE community_post_reference ref JOIN community_legacy_route_repair repair ON ref.id=repair.id
SET ref.snapshot_json=repair.repaired_snapshot, ref.invalidated_at=repair.repaired_invalidated_at
WHERE NOT (ref.snapshot_json <=> repair.repaired_snapshot)
   OR NOT (ref.invalidated_at <=> repair.repaired_invalidated_at);

INSERT INTO community_legacy_route_assert (assertion_ok)
SELECT NOT EXISTS (
  SELECT 1 FROM community_post_reference ref JOIN community_legacy_route_repair repair ON ref.id=repair.id
  WHERE NOT (ref.snapshot_json <=> repair.repaired_snapshot)
     OR NOT (ref.invalidated_at <=> repair.repaired_invalidated_at)
);

DROP TEMPORARY TABLE community_legacy_route_repair;
DROP TEMPORARY TABLE community_legacy_route_assert;
