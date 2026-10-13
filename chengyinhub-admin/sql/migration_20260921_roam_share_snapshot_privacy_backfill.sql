-- CYV-P1-001 / DEC-003:将已存匿名分享快照的轨迹和 POI 坐标统一量化到 3 位小数。
-- 本脚本不创建原值备份;单条 UPDATE 对 InnoDB 表原子生效,重放结果不变。
-- 生产执行前必须先只读回读实际行数和 JSON 形状,并单独获得 AUTH-001 授权。

-- runner: assert=scalar-true
SELECT COUNT(*) = 0
FROM roam_share_snapshot
WHERE JSON_VALID(snapshot_json) = 0;

-- runner: assert=scalar-true
SELECT COUNT(*) = 0
FROM roam_share_snapshot
WHERE NOT (JSON_TYPE(JSON_EXTRACT(snapshot_json, '$.track')) <=> 'ARRAY')
   OR NOT (JSON_TYPE(JSON_EXTRACT(snapshot_json, '$.pois')) <=> 'ARRAY');

-- ERROR ON EMPTY / ERROR ON ERROR 保证旧数据坐标缺失或非数字时在写入前停止。
-- runner: assert=scalar-true
SELECT COUNT(*) = 0
FROM roam_share_snapshot s
CROSS JOIN JSON_TABLE(
    s.snapshot_json,
    '$.track[*]' COLUMNS (
        lat DECIMAL(12, 8) PATH '$.lat' ERROR ON EMPTY ERROR ON ERROR,
        lng DECIMAL(12, 8) PATH '$.lng' ERROR ON EMPTY ERROR ON ERROR
    )
) track_point
WHERE ABS(track_point.lat) > 90 OR ABS(track_point.lng) > 180;

-- POI 坐标可整组缺省,但不允许只有经度或只有纬度。
-- runner: assert=scalar-true
SELECT COUNT(*) = 0
FROM roam_share_snapshot s
CROSS JOIN JSON_TABLE(
    s.snapshot_json,
    '$.pois[*]' COLUMNS (
        has_lat INT EXISTS PATH '$.lat',
        has_lng INT EXISTS PATH '$.lng',
        lat DECIMAL(12, 8) PATH '$.lat' NULL ON EMPTY ERROR ON ERROR,
        lng DECIMAL(12, 8) PATH '$.lng' NULL ON EMPTY ERROR ON ERROR
    )
) poi
WHERE poi.has_lat <> poi.has_lng
   OR (poi.has_lat = 1 AND (ABS(poi.lat) > 90 OR ABS(poi.lng) > 180));

UPDATE roam_share_snapshot s
SET s.snapshot_json = JSON_SET(
    s.snapshot_json,
    '$.track', COALESCE(
        (
            SELECT JSON_ARRAYAGG(
                       JSON_OBJECT('lat', ROUND(track_point.lat, 3), 'lng', ROUND(track_point.lng, 3))
                   ) OVER (
                       ORDER BY track_point.ord
                       ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING
                   )
            FROM JSON_TABLE(
                s.snapshot_json,
                '$.track[*]' COLUMNS (
                    ord FOR ORDINALITY,
                    lat DECIMAL(12, 8) PATH '$.lat' ERROR ON EMPTY ERROR ON ERROR,
                    lng DECIMAL(12, 8) PATH '$.lng' ERROR ON EMPTY ERROR ON ERROR
                )
            ) track_point
            ORDER BY track_point.ord DESC
            LIMIT 1
        ),
        JSON_ARRAY()
    ),
    '$.pois', COALESCE(
        (
            SELECT JSON_ARRAYAGG(
                       CASE
                           WHEN poi.has_lat = 1 THEN JSON_SET(
                               JSON_EXTRACT(s.snapshot_json, CONCAT('$.pois[', poi.ord - 1, ']')),
                               '$.lat', ROUND(poi.lat, 3),
                               '$.lng', ROUND(poi.lng, 3)
                           )
                           ELSE JSON_EXTRACT(s.snapshot_json, CONCAT('$.pois[', poi.ord - 1, ']'))
                       END
                   ) OVER (
                       ORDER BY poi.ord
                       ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING
                   )
            FROM JSON_TABLE(
                s.snapshot_json,
                '$.pois[*]' COLUMNS (
                    ord FOR ORDINALITY,
                    has_lat INT EXISTS PATH '$.lat',
                    lat DECIMAL(12, 8) PATH '$.lat' NULL ON EMPTY ERROR ON ERROR,
                    lng DECIMAL(12, 8) PATH '$.lng' NULL ON EMPTY ERROR ON ERROR
                )
            ) poi
            ORDER BY poi.ord DESC
            LIMIT 1
        ),
        JSON_ARRAY()
    )
)
WHERE JSON_VALID(s.snapshot_json) = 1;

-- 执行后必须从业务表自身证明每个轨迹坐标都已收敛到 3 位;毒化任意一点会使脚本红。
-- runner: assert=scalar-true
SELECT COUNT(*) = 0
FROM roam_share_snapshot s
CROSS JOIN JSON_TABLE(
    s.snapshot_json,
    '$.track[*]' COLUMNS (
        lat DECIMAL(12, 8) PATH '$.lat' ERROR ON EMPTY ERROR ON ERROR,
        lng DECIMAL(12, 8) PATH '$.lng' ERROR ON EMPTY ERROR ON ERROR
    )
) track_point
WHERE track_point.lat <> ROUND(track_point.lat, 3)
   OR track_point.lng <> ROUND(track_point.lng, 3);

-- runner: assert=scalar-true
SELECT COUNT(*) = 0
FROM roam_share_snapshot s
CROSS JOIN JSON_TABLE(
    s.snapshot_json,
    '$.pois[*]' COLUMNS (
        has_lat INT EXISTS PATH '$.lat',
        lat DECIMAL(12, 8) PATH '$.lat' NULL ON EMPTY ERROR ON ERROR,
        lng DECIMAL(12, 8) PATH '$.lng' NULL ON EMPTY ERROR ON ERROR
    )
) poi
WHERE poi.has_lat = 1
  AND (poi.lat <> ROUND(poi.lat, 3) OR poi.lng <> ROUND(poi.lng, 3));
