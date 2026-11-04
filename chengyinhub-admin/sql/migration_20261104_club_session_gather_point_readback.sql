-- ROUND10 r1 F5(2026-11-04):migration_20261102_club_session_gather_point.sql 只有一条 UPDATE,没有读回,
-- 「什么都没匹配上」与「正确回填」看起来一样。本文件只读不写,在它之后跑,断言回填后不再有该填未填的场次:
--   未结束、未取消、未删除的俱乐部场次,两坐标都空,却有一张带齐集合坐标的未删除场次票 —— 必须为 0。
-- 谓词与回填文件逐字一致(两坐标都为空才算「该填」;只有一个坐标的行回填文件本就不碰)。
-- 不改任何数据;可重复执行。

-- runner: assert=scalar-true
SELECT COUNT(*) = 0 AS club_session_gather_point_none_missing
FROM cms_activity a
WHERE a.club_id IS NOT NULL AND a.club_id > 0
  AND a.del_flag = 0 AND a.cancel_time IS NULL
  AND (a.end_date IS NULL OR a.end_date > NOW())
  AND (a.latitude IS NULL OR a.latitude = '')
  AND (a.longitude IS NULL OR a.longitude = '')
  AND EXISTS (
      SELECT 1 FROM oms_ticket t
      WHERE t.owner_type = 2 AND t.owner_id = a.id AND t.del_flag = 0
        AND t.gather_lat IS NOT NULL AND t.gather_lng IS NOT NULL);
