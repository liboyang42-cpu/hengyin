-- 只读预览：migration_o14_content_city_code_backfill_20261022.sql 执行前先在副本上跑，人工抽检第 2、3 段每一行，第 4 段逐条定夺。
-- 口径与迁移完全相同：城市白名单、club.city 去空白去尾部「市」后整串相等、活动冲突/未识别跳过、
--   只看 city_code IS NULL 或「本回填写过且值未被改」的行。绝不读 address_name / address 做匹配(那两列只作为抽检参照输出)。
-- 不修改任何数据、不建临时表。前置：migration_o14_city_code_backfill_log_20261022.sql 已执行(表可为空)。
-- 城市白名单与迁移逐对一致(O14MigrationContractTest 校验)。
SELECT VERSION() AS mysql_version;

-- 1. 现状：已有值的行里，哪些是本回填写的(有 log 且值一致)，哪些是人工/其它来源(迁移永不覆盖)。
SELECT 'cms_topic' AS target_table,
       SUM(t.city_code IS NULL) AS null_rows,
       SUM(t.city_code IS NOT NULL AND l.city_code = t.city_code) AS owned_by_backfill,
       SUM(t.city_code IS NOT NULL AND NOT (l.city_code <=> t.city_code)) AS manual_or_other_never_touched
FROM cms_topic t
LEFT JOIN o14_city_code_backfill_log l ON l.target_table = 'cms_topic' AND l.row_id = t.id
UNION ALL
SELECT 'cms_activity',
       SUM(a.city_code IS NULL),
       SUM(a.city_code IS NOT NULL AND l.city_code = a.city_code),
       SUM(a.city_code IS NOT NULL AND NOT (l.city_code <=> a.city_code))
FROM cms_activity a
LEFT JOIN o14_city_code_backfill_log l ON l.target_table = 'cms_activity' AND l.row_id = a.id;

-- 2. 主题：执行后会变化的行(FILL=原为空；CORRECT_OWN/CLEAR_OWN=本回填以前写的值被新规则改写/清回空)。
WITH kw AS (
  SELECT '北京' AS keyword, '110100' AS city_code UNION ALL SELECT '天津','120100' UNION ALL
  SELECT '上海','310100' UNION ALL SELECT '重庆','500100' UNION ALL SELECT '广州','440100' UNION ALL
  SELECT '深圳','440300' UNION ALL SELECT '杭州','330100' UNION ALL SELECT '南京','320100' UNION ALL
  SELECT '苏州','320500' UNION ALL SELECT '成都','510100' UNION ALL SELECT '武汉','420100' UNION ALL
  SELECT '西安','610100' UNION ALL SELECT '长沙','430100' UNION ALL SELECT '厦门','350200' UNION ALL
  SELECT '青岛','370200'
),
tp AS (
  -- 主题：可写行(city_code 为空，或本回填写过且值未改)及新规则下的应得值
  SELECT t.id, t.name, t.address_name, t.club_id, c.city AS club_city,
         t.city_code AS current_city_code, k.city_code AS proposed_city_code,
         (c.city IS NOT NULL AND TRIM(c.city) <> '') AS club_city_present
  FROM cms_topic t
  LEFT JOIN o14_city_code_backfill_log l ON l.target_table = 'cms_topic' AND l.row_id = t.id
  LEFT JOIN club c ON c.id = t.club_id
  LEFT JOIN kw k ON k.keyword = TRIM(TRAILING '市' FROM TRIM(c.city)) COLLATE utf8mb4_general_ci
  WHERE t.city_code IS NULL OR l.city_code = t.city_code
)
SELECT tp.id, tp.name, tp.address_name, tp.club_id, tp.club_city, tp.current_city_code, tp.proposed_city_code,
       CASE WHEN tp.current_city_code IS NULL THEN 'FILL'
            WHEN tp.proposed_city_code IS NULL THEN 'CLEAR_OWN' ELSE 'CORRECT_OWN' END AS action
FROM tp
WHERE NOT (tp.current_city_code <=> tp.proposed_city_code)
ORDER BY tp.id;

-- 3. 活动：执行后会变化的行。
WITH kw AS (
  SELECT '北京' AS keyword, '110100' AS city_code UNION ALL SELECT '天津','120100' UNION ALL
  SELECT '上海','310100' UNION ALL SELECT '重庆','500100' UNION ALL SELECT '广州','440100' UNION ALL
  SELECT '深圳','440300' UNION ALL SELECT '杭州','330100' UNION ALL SELECT '南京','320100' UNION ALL
  SELECT '苏州','320500' UNION ALL SELECT '成都','510100' UNION ALL SELECT '武汉','420100' UNION ALL
  SELECT '西安','610100' UNION ALL SELECT '长沙','430100' UNION ALL SELECT '厦门','350200' UNION ALL
  SELECT '青岛','370200'
),
x AS (
  -- 活动：父主题取【主题段执行后】的值(可写主题取新规则应得值，否则取现值)，与迁移执行顺序一致
  SELECT a.id, a.name, a.address_name, a.topic_id, a.club_id, c.city AS club_city,
         a.city_code AS current_city_code, k.city_code AS club_code,
         CASE WHEN parent.id IS NULL THEN NULL
              WHEN parent.city_code IS NULL OR pl.city_code = parent.city_code THEN pk.city_code
              ELSE parent.city_code END AS parent_code,
         (c.id IS NULL OR c.city IS NULL OR TRIM(c.city) = '') AS club_city_blank,
         (a.longitude IS NOT NULL AND a.longitude <> '' AND a.latitude IS NOT NULL AND a.latitude <> '') AS has_coordinates
  FROM cms_activity a
  LEFT JOIN o14_city_code_backfill_log l ON l.target_table = 'cms_activity' AND l.row_id = a.id
  LEFT JOIN club c ON c.id = a.club_id
  LEFT JOIN kw k ON k.keyword = TRIM(TRAILING '市' FROM TRIM(c.city)) COLLATE utf8mb4_general_ci
  LEFT JOIN cms_topic parent ON parent.id = a.topic_id AND a.topic_id <> 0
  LEFT JOIN o14_city_code_backfill_log pl ON pl.target_table = 'cms_topic' AND pl.row_id = parent.id
  LEFT JOIN club pc ON pc.id = parent.club_id
  LEFT JOIN kw pk ON pk.keyword = TRIM(TRAILING '市' FROM TRIM(pc.city)) COLLATE utf8mb4_general_ci
  WHERE a.city_code IS NULL OR l.city_code = a.city_code
),
d AS (
  SELECT x.*,
         CASE WHEN x.club_code IS NOT NULL AND (x.parent_code IS NULL OR x.parent_code = x.club_code) THEN x.club_code
              WHEN x.club_city_blank = 1 THEN x.parent_code END AS proposed_city_code,
         CASE WHEN x.club_code IS NOT NULL AND (x.parent_code IS NULL OR x.parent_code = x.club_code) THEN 'o14_v2:club_city'
              WHEN x.club_city_blank = 1 AND x.parent_code IS NOT NULL THEN 'o14_v2:parent_topic' END AS proposed_source
  FROM x
)
SELECT d.id, d.name, d.address_name, d.topic_id, d.club_id, d.club_city, d.club_code, d.parent_code,
       d.current_city_code, d.proposed_city_code, d.proposed_source,
       CASE WHEN d.current_city_code IS NULL THEN 'FILL'
            WHEN d.proposed_city_code IS NULL THEN 'CLEAR_OWN' ELSE 'CORRECT_OWN' END AS action
FROM d
WHERE NOT (d.current_city_code <=> d.proposed_city_code)
ORDER BY d.id;

-- 4. 歧义 / 判不出(迁移一律不写，留给人工)：
--    CLUB_CITY_UNRECOGNIZED = 俱乐部城市非空但不在白名单(如「上海浦东」「苏州/上海」)；
--    CLUB_PARENT_CONFLICT   = 活动俱乐部城市与父主题 city_code 不一致；
--    NO_STRUCTURED_SOURCE   = 没有俱乐部城市、也没有可继承的父主题(has_coordinates=1 的可另行用逆地理编码人工补)。
WITH kw AS (
  SELECT '北京' AS keyword, '110100' AS city_code UNION ALL SELECT '天津','120100' UNION ALL
  SELECT '上海','310100' UNION ALL SELECT '重庆','500100' UNION ALL SELECT '广州','440100' UNION ALL
  SELECT '深圳','440300' UNION ALL SELECT '杭州','330100' UNION ALL SELECT '南京','320100' UNION ALL
  SELECT '苏州','320500' UNION ALL SELECT '成都','510100' UNION ALL SELECT '武汉','420100' UNION ALL
  SELECT '西安','610100' UNION ALL SELECT '长沙','430100' UNION ALL SELECT '厦门','350200' UNION ALL
  SELECT '青岛','370200'
),
tp AS (
  -- 主题：可写行(city_code 为空，或本回填写过且值未改)及新规则下的应得值
  SELECT t.id, t.name, t.address_name, t.club_id, c.city AS club_city,
         t.city_code AS current_city_code, k.city_code AS proposed_city_code,
         (c.city IS NOT NULL AND TRIM(c.city) <> '') AS club_city_present
  FROM cms_topic t
  LEFT JOIN o14_city_code_backfill_log l ON l.target_table = 'cms_topic' AND l.row_id = t.id
  LEFT JOIN club c ON c.id = t.club_id
  LEFT JOIN kw k ON k.keyword = TRIM(TRAILING '市' FROM TRIM(c.city)) COLLATE utf8mb4_general_ci
  WHERE t.city_code IS NULL OR l.city_code = t.city_code
),
x AS (
  -- 活动：父主题取【主题段执行后】的值(可写主题取新规则应得值，否则取现值)，与迁移执行顺序一致
  SELECT a.id, a.name, a.address_name, a.topic_id, a.club_id, c.city AS club_city,
         a.city_code AS current_city_code, k.city_code AS club_code,
         CASE WHEN parent.id IS NULL THEN NULL
              WHEN parent.city_code IS NULL OR pl.city_code = parent.city_code THEN pk.city_code
              ELSE parent.city_code END AS parent_code,
         (c.id IS NULL OR c.city IS NULL OR TRIM(c.city) = '') AS club_city_blank,
         (a.longitude IS NOT NULL AND a.longitude <> '' AND a.latitude IS NOT NULL AND a.latitude <> '') AS has_coordinates
  FROM cms_activity a
  LEFT JOIN o14_city_code_backfill_log l ON l.target_table = 'cms_activity' AND l.row_id = a.id
  LEFT JOIN club c ON c.id = a.club_id
  LEFT JOIN kw k ON k.keyword = TRIM(TRAILING '市' FROM TRIM(c.city)) COLLATE utf8mb4_general_ci
  LEFT JOIN cms_topic parent ON parent.id = a.topic_id AND a.topic_id <> 0
  LEFT JOIN o14_city_code_backfill_log pl ON pl.target_table = 'cms_topic' AND pl.row_id = parent.id
  LEFT JOIN club pc ON pc.id = parent.club_id
  LEFT JOIN kw pk ON pk.keyword = TRIM(TRAILING '市' FROM TRIM(pc.city)) COLLATE utf8mb4_general_ci
  WHERE a.city_code IS NULL OR l.city_code = a.city_code
),
d AS (
  SELECT x.*,
         CASE WHEN x.club_code IS NOT NULL AND (x.parent_code IS NULL OR x.parent_code = x.club_code) THEN x.club_code
              WHEN x.club_city_blank = 1 THEN x.parent_code END AS proposed_city_code,
         CASE WHEN x.club_code IS NOT NULL AND (x.parent_code IS NULL OR x.parent_code = x.club_code) THEN 'o14_v2:club_city'
              WHEN x.club_city_blank = 1 AND x.parent_code IS NOT NULL THEN 'o14_v2:parent_topic' END AS proposed_source
  FROM x
)
SELECT 'cms_topic' AS target_table, tp.id, tp.name, tp.address_name, tp.club_city, NULL AS parent_code,
       NULL AS has_coordinates,
       CASE WHEN tp.club_city_present = 1 THEN 'CLUB_CITY_UNRECOGNIZED' ELSE 'NO_STRUCTURED_SOURCE' END AS reason
FROM tp
WHERE tp.current_city_code IS NULL AND tp.proposed_city_code IS NULL
UNION ALL
SELECT 'cms_activity', d.id, d.name, d.address_name, d.club_city, d.parent_code, d.has_coordinates,
       CASE WHEN d.club_city_blank = 0 AND d.club_code IS NULL THEN 'CLUB_CITY_UNRECOGNIZED'
            WHEN d.club_code IS NOT NULL THEN 'CLUB_PARENT_CONFLICT'
            ELSE 'NO_STRUCTURED_SOURCE' END
FROM d
WHERE d.current_city_code IS NULL AND d.proposed_city_code IS NULL
ORDER BY target_table, id;
