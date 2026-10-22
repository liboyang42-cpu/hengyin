-- Doc2 F1c / O-02(2026-10-22):member_visited_poi 旧行回填 poi_key(数据迁移,只写 poi_key 为 null 的行)。
-- 前置:migration_member_visited_poi_poi_key_20261022.sql(加列)。必须在打开
-- sys_config['roam.visited_poi.stable_identity'] 之前执行;不删任何行、不改 geo_key、不追回已发探索值。
-- 规则与 VisitedPoiIdentityResolver 同口径(球面距离同 GeoDistanceUtils:6371000m haversine):
--   ① 同一玩家更早的一行(id 更小)在 5m 内,或 80m 内且同名(非空、非「未知地点」)→ 本行是重复,poi_key 保持 null;
--   ② 非重复行 50m 内有上架地标(roam_poi.type=1,取最近、同距取小 id)→ 该玩家该地标最早一行写 poi:{id};
--   ③ 其余非重复行写 pt:{5 位小数纬度},{5 位小数经度}(非重复行两两相距 >5m,标签不会撞唯一键)。
-- 幂等:只处理 poi_key 为 null 的行;重跑时剩下的都是 ① 判出的重复行,不会再被写。
-- 落库前只读预检(重复预览)见 PR 描述。
-- ⚠️ 生产执行前必须走 chengyin-db-migrate 的只读预检 + owner 确认,本 PR 不在任何库上执行。
-- 回滚:UPDATE member_visited_poi SET poi_key = NULL WHERE poi_key IS NOT NULL;(仅在开关为 false 时)
DROP TEMPORARY TABLE IF EXISTS member_visited_poi_key_plan;
CREATE TEMPORARY TABLE member_visited_poi_key_plan (
  id BIGINT NOT NULL PRIMARY KEY,
  member_id BIGINT NOT NULL,
  dup TINYINT NOT NULL,
  landmark_id BIGINT NULL,
  point_key VARCHAR(32) NOT NULL
) ENGINE=InnoDB;

INSERT INTO member_visited_poi_key_plan (id, member_id, dup, landmark_id, point_key)
SELECT v.id, v.member_id,
       EXISTS (SELECT 1 FROM member_visited_poi e
               WHERE e.member_id = v.member_id AND e.id < v.id
                 AND e.lat IS NOT NULL AND e.lng IS NOT NULL
                 AND e.lat BETWEEN v.lat - 0.00075 AND v.lat + 0.00075
                 AND e.lng BETWEEN v.lng - 0.0016 AND v.lng + 0.0016
                 AND (2 * 6371000 * ASIN(SQRT(LEAST(1, POW(SIN(RADIANS(v.lat - e.lat) / 2), 2) + COS(RADIANS(e.lat)) * COS(RADIANS(v.lat)) * POW(SIN(RADIANS(v.lng - e.lng) / 2), 2)))) <= 5
                      OR (2 * 6371000 * ASIN(SQRT(LEAST(1, POW(SIN(RADIANS(v.lat - e.lat) / 2), 2) + COS(RADIANS(e.lat)) * COS(RADIANS(v.lat)) * POW(SIN(RADIANS(v.lng - e.lng) / 2), 2)))) <= 80
                          AND v.poi_name IS NOT NULL AND v.poi_name <> '' AND v.poi_name <> '未知地点'
                          AND e.poi_name = v.poi_name))),
       (SELECT p.id FROM roam_poi p
        WHERE p.type = 1 AND p.status = 1 AND p.lat IS NOT NULL AND p.lng IS NOT NULL
          AND p.lat BETWEEN v.lat - 0.0005 AND v.lat + 0.0005
          AND p.lng BETWEEN v.lng - 0.001 AND v.lng + 0.001
          AND 2 * 6371000 * ASIN(SQRT(LEAST(1, POW(SIN(RADIANS(p.lat - v.lat) / 2), 2) + COS(RADIANS(v.lat)) * COS(RADIANS(p.lat)) * POW(SIN(RADIANS(p.lng - v.lng) / 2), 2)))) <= 50
        ORDER BY 2 * 6371000 * ASIN(SQRT(LEAST(1, POW(SIN(RADIANS(p.lat - v.lat) / 2), 2) + COS(RADIANS(v.lat)) * COS(RADIANS(p.lat)) * POW(SIN(RADIANS(p.lng - v.lng) / 2), 2)))), p.id
        LIMIT 1),
       CONCAT('pt:', CAST(ROUND(v.lat, 5) AS DECIMAL(8,5)), ',', CAST(ROUND(v.lng, 5) AS DECIMAL(9,5)))
FROM member_visited_poi v
WHERE v.poi_key IS NULL AND v.lat IS NOT NULL AND v.lng IS NOT NULL;

-- 同一玩家多个非重复行落在同一地标上:只有最早一行拿 poi:{id},其余用各自的 pt 标签。
DROP TEMPORARY TABLE IF EXISTS member_visited_poi_key_rank;
CREATE TEMPORARY TABLE member_visited_poi_key_rank (
  id BIGINT NOT NULL PRIMARY KEY,
  rn INT NOT NULL
) ENGINE=InnoDB;

INSERT INTO member_visited_poi_key_rank (id, rn)
SELECT id, ROW_NUMBER() OVER (PARTITION BY member_id, landmark_id ORDER BY id)
FROM member_visited_poi_key_plan
WHERE dup = 0 AND landmark_id IS NOT NULL;

UPDATE member_visited_poi v
  JOIN member_visited_poi_key_plan b ON b.id = v.id
  LEFT JOIN member_visited_poi_key_rank r ON r.id = b.id
SET v.poi_key = IF(r.rn = 1, CONCAT('poi:', b.landmark_id), b.point_key)
WHERE v.poi_key IS NULL AND b.dup = 0;

-- 回读断言:计划里的非重复行全部拿到 poi_key,重复行全部保持 null。
-- runner: assert=scalar-true
SELECT COALESCE(SUM(CASE WHEN b.dup = 0 THEN v.poi_key IS NULL ELSE v.poi_key IS NOT NULL END), 0) = 0
       AS assertion_ok
FROM member_visited_poi_key_plan b
JOIN member_visited_poi v ON v.id = b.id;

DROP TEMPORARY TABLE member_visited_poi_key_rank;
DROP TEMPORARY TABLE member_visited_poi_key_plan;
