-- Forward-only missing-post delta, not a replay of the September backfill.
-- Requires a verified backup and an exclusive maintenance window: stop all old/new writers.
-- Execute only with the versioned ci/run_migration.py --transaction (MySQL 8/InnoDB).
-- Existing posts and their children are never updated. Unsupported associations abort.
-- runner: requires=transaction

CREATE TEMPORARY TABLE community_legacy_delta_assert (
  assertion_ok TINYINT NOT NULL CHECK (assertion_ok=1)
);

CREATE TEMPORARY TABLE community_legacy_projection_delta AS
SELECT s.* FROM ums_creative_square s
LEFT JOIN community_post p ON p.id=s.id
WHERE s.member_id IS NOT NULL AND p.id IS NULL;

-- Keep the original identity, including collisions on the author's request key.
INSERT INTO community_legacy_delta_assert (assertion_ok)
SELECT NOT EXISTS (
  SELECT 1 FROM ums_creative_square s JOIN community_post p
    ON p.id=s.id OR (p.author_id=s.member_id AND p.client_request_id=CONCAT('legacy-square-',s.id))
  WHERE s.member_id IS NOT NULL AND NOT (
    p.id=s.id AND p.author_id=s.member_id
    AND BINARY p.client_request_id=BINARY CONCAT('legacy-square-',s.id))
);

-- This reviewed delta supports images and TOPIC references only. Never lose other children.
INSERT INTO community_legacy_delta_assert (assertion_ok)
SELECT NOT EXISTS (
  SELECT 1 FROM community_legacy_projection_delta s
  LEFT JOIN ums_comment c ON c.owner_type=3 AND c.owner_id=s.id
  LEFT JOIN ums_creative_square_like l ON l.data_id=s.id AND l.like_type=1 AND l.member_id IS NOT NULL
  WHERE s.del_flag IS NULL OR s.del_flag NOT IN (0,1,2) OR s.status IS NULL
    OR s.data_type IS NULL OR s.data_type NOT IN (0,2)
    OR (s.data_type=2 AND (s.data_id IS NULL OR s.data_id<=0))
    OR (s.data_type=0 AND s.data_id>0)
    OR NULLIF(TRIM(s.file_url),'') IS NOT NULL
    OR NULLIF(TRIM(s.route_preview_img),'') IS NOT NULL
    OR c.id IS NOT NULL OR l.id IS NOT NULL
);

-- An orphan target row must not acquire a new owner merely because a post was absent.
INSERT INTO community_legacy_delta_assert (assertion_ok)
SELECT NOT EXISTS (
  SELECT 1 FROM community_legacy_projection_delta s
  LEFT JOIN community_post_media m ON m.post_id=s.id
  LEFT JOIN community_post_reference r ON r.post_id=s.id
  LEFT JOIN community_post_comment c ON c.post_id=s.id
  LEFT JOIN community_post_member_action a ON a.post_id=s.id
  LEFT JOIN community_search_document d ON d.document_type='POST' AND d.document_id=s.id
  WHERE m.id IS NOT NULL OR r.id IS NOT NULL OR c.id IS NOT NULL OR a.id IS NOT NULL OR d.id IS NOT NULL
);

CREATE TEMPORARY TABLE community_legacy_delta_images AS
SELECT s.id post_id,s.member_id,media.ordinality,media.url
FROM community_legacy_projection_delta s, JSON_TABLE(
  CONCAT('[',CHAR(34),
    REPLACE(REPLACE(REPLACE(TRIM(s.pics),CHAR(92),CONCAT(CHAR(92),CHAR(92))),CHAR(34),CONCAT(CHAR(92),CHAR(34))),
      ';',CONCAT(CHAR(34),',',CHAR(34))),CHAR(34),']'),
  '$[*]' COLUMNS(ordinality FOR ORDINALITY,url TEXT PATH '$' ERROR ON ERROR)
) media
WHERE s.pics IS NOT NULL AND TRIM(s.pics)<>'' AND TRIM(media.url)<>'';

INSERT INTO community_legacy_delta_assert (assertion_ok)
SELECT NOT EXISTS (SELECT 1 FROM community_legacy_delta_images WHERE CHAR_LENGTH(url)>500);

INSERT INTO community_post
  (id,author_id,community_id,client_request_id,post_type,title,body,audience,comment_policy,
   reply_approval_enabled,slow_mode_seconds,lifecycle,location_precision,city_code,poi_name,
   approximate_geohash,recommendation_eligible,search_eligible,interaction_enabled,disclosure_type,
   safety_labels_json,guideline_version_id,like_count,comment_count,bookmark_count,share_count,
   published_at,edited_at,deleted_at,version,create_time,update_time)
SELECT s.id,s.member_id,NULL,CONCAT('legacy-square-',s.id),'MOMENT',NULL,
       LEFT(COALESCE(NULLIF(TRIM(s.contents),''),'[历史帖文]'),5000),'PUBLIC','EVERYONE',0,0,
       CASE WHEN s.del_flag=2 THEN 'DELETED' WHEN s.del_flag<>0 THEN 'REMOVED'
            WHEN s.status=1 THEN 'PUBLISHED' WHEN s.status=0 THEN 'CHECKING' ELSE 'REMOVED' END,
       'NONE',NULL,NULL,NULL,
       CASE WHEN s.status=1 AND s.del_flag=0 THEN 1 ELSE 0 END,
       CASE WHEN s.status=1 AND s.del_flag=0 THEN 1 ELSE 0 END,
       CASE WHEN s.status=1 AND s.del_flag=0 THEN 1 ELSE 0 END,'NONE',JSON_ARRAY('LEGACY_MIGRATED'),
       (SELECT id FROM community_guideline_version WHERE version_code='CITY_EXPLORER_1'),0,0,0,0,
       CASE WHEN s.status=1 AND s.del_flag=0 THEN COALESCE(s.create_time,NOW()) ELSE NULL END,
       s.update_time,CASE WHEN s.del_flag=2 THEN COALESCE(s.update_time,s.create_time,NOW()) ELSE NULL END,
       0,COALESCE(s.create_time,NOW()),COALESCE(s.update_time,s.create_time,NOW())
FROM ums_creative_square current_source JOIN community_legacy_projection_delta s ON s.id=current_source.id;

INSERT INTO community_post_media
  (owner_member_id,upload_request_id,post_id,media_type,object_key,derived_object_key,mime_type,
   byte_size,sort_order,content_hash,upload_state,review_state,retention_state,committed_at,create_time,update_time)
SELECT image.member_id,CONCAT('legacy-square-media-',image.post_id,'-',image.ordinality),image.post_id,
       'IMAGE',image.url,image.url,'image/jpeg',1,image.ordinality-1,SHA2(image.url,256),'ATTACHED',
       CASE WHEN p.lifecycle='PUBLISHED' AND p.search_eligible=1 THEN 'PASS' ELSE 'PENDING' END,
       CASE WHEN p.lifecycle='DELETED' THEN 'ORPHANED' ELSE 'ACTIVE' END,
       p.create_time,p.create_time,p.update_time
FROM community_legacy_delta_images image JOIN community_post p ON p.id=image.post_id;

INSERT INTO community_post_reference
  (post_id,reference_type,reference_id,snapshot_json,privacy_snapshot,create_time)
SELECT s.id,'TOPIC',s.data_id,JSON_OBJECT('legacy',TRUE),'PUBLIC_SAFE',COALESCE(s.create_time,NOW())
FROM community_legacy_projection_delta s WHERE s.data_type=2 AND s.data_id>0;

INSERT INTO community_search_document
  (document_type,document_id,owner_member_id,title,search_text,city_code,topic_codes_json,
   visibility_state,source_version,indexed_at)
SELECT 'POST',p.id,p.author_id,p.title,CONCAT(COALESCE(p.title,''),' ',p.body),p.city_code,JSON_ARRAY(),
       CASE WHEN p.lifecycle='DELETED' THEN 'DELETED'
            WHEN p.lifecycle='PUBLISHED' AND p.search_eligible=1 THEN 'VISIBLE' ELSE 'HIDDEN' END,p.version,NOW()
FROM community_post p JOIN community_legacy_projection_delta delta ON delta.id=p.id;

-- Recheck the copied source facts before committing; a maintenance fence is still required.
INSERT INTO community_legacy_delta_assert (assertion_ok)
SELECT NOT EXISTS (
  SELECT 1 FROM community_legacy_projection_delta delta
  LEFT JOIN ums_creative_square s ON s.id=delta.id
  WHERE s.id IS NULL OR NOT (s.member_id<=>delta.member_id) OR NOT (s.contents<=>delta.contents)
    OR NOT (s.pics<=>delta.pics) OR NOT (s.file_url<=>delta.file_url)
    OR NOT (s.data_id<=>delta.data_id) OR NOT (s.data_type<=>delta.data_type)
    OR NOT (s.route_preview_img<=>delta.route_preview_img)
    OR NOT (s.status<=>delta.status) OR NOT (s.del_flag<=>delta.del_flag)
    OR NOT (s.create_time<=>delta.create_time) OR NOT (s.update_time<=>delta.update_time)
);

-- Both coverage and identity matter: equal totals alone can conceal offsetting omissions.
INSERT INTO community_legacy_delta_assert (assertion_ok)
SELECT NOT EXISTS (
  SELECT 1 FROM ums_creative_square s LEFT JOIN community_post p ON p.id=s.id
  WHERE s.member_id IS NOT NULL AND (p.id IS NULL OR NOT (p.author_id<=>s.member_id)
    OR NOT (BINARY p.client_request_id<=>BINARY CONCAT('legacy-square-',s.id)))
);

INSERT INTO community_legacy_delta_assert (assertion_ok)
SELECT
  (SELECT COUNT(*) FROM ums_creative_square WHERE member_id IS NOT NULL)
    = (SELECT COUNT(*) FROM community_post WHERE client_request_id LIKE 'legacy-square-%')
  AND (SELECT COUNT(*) FROM ums_creative_square WHERE member_id IS NOT NULL AND COALESCE(del_flag,0)=0 AND status=1)
    = (SELECT COUNT(*) FROM community_post WHERE client_request_id LIKE 'legacy-square-%' AND lifecycle='PUBLISHED');

INSERT INTO community_legacy_delta_assert (assertion_ok)
SELECT NOT EXISTS (
  SELECT 1 FROM community_legacy_projection_delta s JOIN community_post p ON p.id=s.id
  LEFT JOIN community_search_document d ON d.document_type='POST' AND d.document_id=p.id
  WHERE d.id IS NULL OR NOT (d.owner_member_id<=>p.author_id)
    OR NOT (d.visibility_state<=>CASE WHEN p.lifecycle='DELETED' THEN 'DELETED'
      WHEN p.lifecycle='PUBLISHED' AND p.search_eligible=1 THEN 'VISIBLE' ELSE 'HIDDEN' END)
    OR p.like_count<>0 OR p.comment_count<>0 OR p.bookmark_count<>0
);

DROP TEMPORARY TABLE community_legacy_delta_images;
DROP TEMPORARY TABLE community_legacy_projection_delta;
DROP TEMPORARY TABLE community_legacy_delta_assert;
