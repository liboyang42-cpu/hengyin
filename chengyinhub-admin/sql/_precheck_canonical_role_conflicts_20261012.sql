-- READ_ONLY: run only against an explicitly authorized target; no role inference or repair.
-- IDs and non-sensitive role/state values only. Business profiles do not grant permissions.
SELECT id AS member_id, role AS canonical_role, user_type,
       CASE
           WHEN role IS NULL OR BINARY role NOT IN ('player','club','merchant') THEN 'INVALID_CANONICAL_ROLE'
           ELSE 'COMPATIBILITY_USER_TYPE_MISMATCH'
       END AS conflict
FROM ums_member
WHERE role IS NULL OR BINARY role NOT IN ('player','club','merchant')
   OR user_type IS NULL OR user_type <> CASE WHEN BINARY role='merchant' THEN 2 ELSE 1 END
ORDER BY id;

SELECT c.member_id, m.role AS canonical_role, c.capability, c.status,
       'ACTIVE_COMPATIBILITY_CAPABILITY_CONFLICT' AS conflict
FROM member_capability c LEFT JOIN ums_member m ON m.id=c.member_id
WHERE c.status=1 AND (
      (c.capability='club_leader' AND (m.role IS NULL OR BINARY m.role<>'club'))
   OR (c.capability='merchant' AND (m.role IS NULL OR BINARY m.role<>'merchant')))
ORDER BY c.member_id,c.capability;

SELECT m.id AS member_id, m.role AS canonical_role,
       (SELECT COUNT(*) FROM club c WHERE c.member_id=m.id AND c.status=1 AND c.del_flag=0) AS active_owned_clubs,
       (SELECT COUNT(*) FROM club_leader l WHERE l.member_id=m.id AND l.status=1 AND l.del_flag=0) AS approved_leader_profiles,
       (SELECT COUNT(*) FROM mms_merchant s WHERE s.member_id=m.id AND s.status=1 AND s.account_status=1 AND s.del_flag=0) AS active_merchant_profiles,
       'PROFILE_ROLE_DIFFERENCE_REVIEW_ONLY' AS conflict
FROM ums_member m
WHERE ((m.role IS NULL OR BINARY m.role<>'club') AND (
         EXISTS (SELECT 1 FROM club c WHERE c.member_id=m.id AND c.status=1 AND c.del_flag=0)
      OR EXISTS (SELECT 1 FROM club_leader l WHERE l.member_id=m.id AND l.status=1 AND l.del_flag=0)))
   OR ((m.role IS NULL OR BINARY m.role<>'merchant') AND
         EXISTS (SELECT 1 FROM mms_merchant s WHERE s.member_id=m.id AND s.status=1 AND s.account_status=1 AND s.del_flag=0))
ORDER BY m.id;
