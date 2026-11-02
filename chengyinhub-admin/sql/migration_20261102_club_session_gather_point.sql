-- C-03(2026-11-02):俱乐部开场从未把票种集合点写到场次经纬度,集合签到恒为「集合点未配置」。
-- 代码修复后新场次在开场时抄写;本脚本只回填【尚未结束、未取消、俱乐部带领、经纬度为空】的存量场次,
-- 取该场次自己的场次票(owner_type=2,由主题票复制而来)里 id 最小、且两坐标齐全的一张。
-- 只填 NULL/空串,不覆盖任何已有坐标;已结束场次不动(签到窗口已关闭,改了也无用)。可重复执行。
-- 【前置】cms_activity.latitude/longitude 与 oms_ticket.gather_lat/gather_lng 已存在(migration_c3_team_fields)。
UPDATE cms_activity a
JOIN (
    SELECT t.owner_id AS activity_id, MIN(t.id) AS ticket_id
    FROM oms_ticket t
    WHERE t.owner_type = 2 AND t.del_flag = 0
      AND t.gather_lat IS NOT NULL AND t.gather_lng IS NOT NULL
    GROUP BY t.owner_id
) pick ON pick.activity_id = a.id
JOIN oms_ticket src ON src.id = pick.ticket_id
SET a.latitude = CAST(src.gather_lat AS CHAR),
    a.longitude = CAST(src.gather_lng AS CHAR)
WHERE a.club_id IS NOT NULL AND a.club_id > 0
  AND a.del_flag = 0 AND a.cancel_time IS NULL
  AND (a.end_date IS NULL OR a.end_date > NOW())
  AND (a.latitude IS NULL OR a.latitude = '')
  AND (a.longitude IS NULL OR a.longitude = '');
