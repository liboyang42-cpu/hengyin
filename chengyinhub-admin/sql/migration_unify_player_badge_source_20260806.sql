-- 统一成长/俱乐部已获徽章数据源到 player_badge。
-- 合并前必须走 chengyin-db-migrate：只读预检后执行；新 jar 不再读写 member_badge。
-- member_badge 保留作可回查旧表，本迁移不 DROP，回滚时可安全切回旧 jar。

-- 预检：确认两表存在，并记录待搬运数与潜在唯一键冲突数。
-- SELECT COUNT(*) FROM member_badge;
-- SELECT COUNT(*) FROM player_badge;
-- SELECT COUNT(*) FROM member_badge mb JOIN player_badge pb
--   ON pb.member_id = mb.member_id AND pb.badge_code = mb.badge_code
--  AND pb.source_id = IFNULL(mb.source_id, 0);

INSERT INTO player_badge
    (member_id, badge_code, badge_name, icon_url, source_type, source_id, obtain_time, del_flag, create_time)
SELECT mb.member_id,
       mb.badge_code,
       gb.badge_name,
       gb.icon_url,
       CASE
           WHEN mb.source_type = 'club' THEN 6
           WHEN mb.source_type REGEXP '^[0-9]+$' THEN CAST(mb.source_type AS UNSIGNED)
           ELSE 0
       END,
       IFNULL(mb.source_id, 0),
       mb.unlock_time,
       0,
       IFNULL(mb.create_time, mb.unlock_time)
  FROM member_badge mb
  LEFT JOIN growth_badge gb ON gb.badge_code = mb.badge_code
ON DUPLICATE KEY UPDATE
    badge_name = COALESCE(VALUES(badge_name), player_badge.badge_name),
    icon_url = COALESCE(VALUES(icon_url), player_badge.icon_url),
    source_type = VALUES(source_type),
    obtain_time = COALESCE(player_badge.obtain_time, VALUES(obtain_time)),
    del_flag = 0;

-- 回读：应为 0；不为 0 说明有旧徽章未搬入 player_badge，禁止部署新 jar。
SELECT COUNT(*) AS unmigrated_badges
  FROM member_badge mb
 WHERE NOT EXISTS (
       SELECT 1
         FROM player_badge pb
        WHERE pb.member_id = mb.member_id
          AND pb.badge_code = mb.badge_code
          AND pb.source_id = IFNULL(mb.source_id, 0)
          AND pb.del_flag = 0
 );
