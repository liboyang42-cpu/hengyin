-- O-14(C8-1 选 B):cms_topic / cms_activity.city_code 回填(v2，按 RV3 B-519-1 重写)。
-- ⚠️ 只由 owner 先在副本跑只读预览 _precheck_o14_content_city_code_backfill_20261022.sql、人工抽检确认后手工执行；本仓自动化从不执行它。
-- 前置：migration_o14_cms_topic_city_code_20261022.sql、migration_o14_cms_activity_city_code_20261022.sql、
--       migration_o14_city_code_backfill_log_20261022.sql。
-- 来源(只用结构化字段；绝不对 address_name / address 等自由文本做子串匹配)：
--   1. 所属俱乐部的「所在城市」字段 club.city，去首尾空白、去尾部「市」后与城市白名单【整串相等】才算命中。
--      (v1 按地址任意位置含城市名匹配：南京西路(上海)→南京、北京路(广州)→北京，已废弃。)
--   2. 活动：自身俱乐部城市命中且与父主题 city_code 不冲突则用它；自身没有俱乐部城市时继承父主题 city_code。
--      俱乐部城市与父主题冲突、或俱乐部城市非空但不在白名单，都算歧义，不写，进预览的歧义清单。
--   坐标：仓内只有在线逆地理编码(MapGeocodeServiceImpl)，没有能在 SQL 里用的坐标→城市映射，本迁移不用坐标。
-- 可写行(其余一律不动)：city_code IS NULL，或 o14_city_code_backfill_log 里记着「本回填写的、且值至今未被改过」的行。
--   每写一行都在 log 记 (表, id, 值, 来源)。规则修正后重跑，只会改写或清回 NULL 本回填自己写过且未被人工改动的行。
-- 城市码为 adcode 城市级(直辖市取 xx0100)，与小程序 cityCode 口径一致。
-- 幂等：同一规则重跑 city_code 零变更。
-- 排序规则：业务列是 utf8mb4_general_ci，club.city 是 utf8mb4_0900_ai_ci；比较处显式钉成 utf8mb4_general_ci，避免 1267。
DROP TEMPORARY TABLE IF EXISTS o14_city_keyword;
CREATE TEMPORARY TABLE o14_city_keyword(
  keyword VARCHAR(16) NOT NULL PRIMARY KEY,
  city_code VARCHAR(32) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

INSERT INTO o14_city_keyword(keyword, city_code) VALUES
  ('北京','110100'),('天津','120100'),('上海','310100'),('重庆','500100'),
  ('广州','440100'),('深圳','440300'),('杭州','330100'),('南京','320100'),
  ('苏州','320500'),('成都','510100'),('武汉','420100'),('西安','610100'),
  ('长沙','430100'),('厦门','350200'),('青岛','370200');

-- 本回填写过、但值已被人工改掉的行：从此归人工，撤销本回填的认领，以后不论改成什么都不再碰。
DELETE l FROM o14_city_code_backfill_log l
JOIN cms_topic t ON l.target_table = 'cms_topic' AND l.row_id = t.id
WHERE NOT (t.city_code <=> l.city_code);

DELETE l FROM o14_city_code_backfill_log l
JOIN cms_activity a ON l.target_table = 'cms_activity' AND l.row_id = a.id
WHERE NOT (a.city_code <=> l.city_code);

-- ---------- 主题 ----------
DROP TEMPORARY TABLE IF EXISTS o14_topic_plan;
CREATE TEMPORARY TABLE o14_topic_plan(
  id BIGINT NOT NULL PRIMARY KEY,
  city_code VARCHAR(32) NULL,
  source VARCHAR(64) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- 本次可写的主题及其应得值(NULL = 判不出；若是本回填以前写的就清回 NULL)。
INSERT INTO o14_topic_plan(id, city_code, source)
SELECT t.id, k.city_code, IF(k.city_code IS NULL, NULL, 'o14_v2:club_city')
FROM cms_topic t
LEFT JOIN o14_city_code_backfill_log l ON l.target_table = 'cms_topic' AND l.row_id = t.id
LEFT JOIN club c ON c.id = t.club_id
LEFT JOIN o14_city_keyword k
  ON k.keyword = TRIM(TRAILING '市' FROM TRIM(c.city)) COLLATE utf8mb4_general_ci
WHERE t.city_code IS NULL OR l.city_code = t.city_code;

UPDATE cms_topic t
JOIN o14_topic_plan p ON p.id = t.id
LEFT JOIN o14_city_code_backfill_log l ON l.target_table = 'cms_topic' AND l.row_id = t.id
SET t.city_code = p.city_code
WHERE (t.city_code IS NULL OR l.city_code = t.city_code)
  AND NOT (t.city_code <=> p.city_code);

DELETE l FROM o14_city_code_backfill_log l
JOIN o14_topic_plan p ON l.row_id = p.id
WHERE l.target_table = 'cms_topic';

INSERT INTO o14_city_code_backfill_log(target_table, row_id, city_code, source, write_time)
SELECT 'cms_topic', p.id, p.city_code, p.source, NOW()
FROM o14_topic_plan p
WHERE p.city_code IS NOT NULL;

-- ---------- 活动(放在主题之后：父主题须先回填) ----------
DROP TEMPORARY TABLE IF EXISTS o14_activity_plan;
CREATE TEMPORARY TABLE o14_activity_plan(
  id BIGINT NOT NULL PRIMARY KEY,
  city_code VARCHAR(32) NULL,
  source VARCHAR(64) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

INSERT INTO o14_activity_plan(id, city_code, source)
SELECT d.id,
       CASE WHEN d.club_code IS NOT NULL AND (d.parent_code IS NULL OR d.parent_code = d.club_code) THEN d.club_code
            WHEN d.club_city_blank = 1 THEN d.parent_code END,
       CASE WHEN d.club_code IS NOT NULL AND (d.parent_code IS NULL OR d.parent_code = d.club_code) THEN 'o14_v2:club_city'
            WHEN d.club_city_blank = 1 AND d.parent_code IS NOT NULL THEN 'o14_v2:parent_topic' END
FROM (
  SELECT a.id,
         k.city_code AS club_code,
         parent.city_code AS parent_code,
         (c.id IS NULL OR c.city IS NULL OR TRIM(c.city) = '') AS club_city_blank
  FROM cms_activity a
  LEFT JOIN o14_city_code_backfill_log l ON l.target_table = 'cms_activity' AND l.row_id = a.id
  LEFT JOIN club c ON c.id = a.club_id
  LEFT JOIN o14_city_keyword k
    ON k.keyword = TRIM(TRAILING '市' FROM TRIM(c.city)) COLLATE utf8mb4_general_ci
  LEFT JOIN cms_topic parent ON parent.id = a.topic_id AND a.topic_id <> 0
  WHERE a.city_code IS NULL OR l.city_code = a.city_code
) d;

UPDATE cms_activity a
JOIN o14_activity_plan p ON p.id = a.id
LEFT JOIN o14_city_code_backfill_log l ON l.target_table = 'cms_activity' AND l.row_id = a.id
SET a.city_code = p.city_code
WHERE (a.city_code IS NULL OR l.city_code = a.city_code)
  AND NOT (a.city_code <=> p.city_code);

DELETE l FROM o14_city_code_backfill_log l
JOIN o14_activity_plan p ON l.row_id = p.id
WHERE l.target_table = 'cms_activity';

INSERT INTO o14_city_code_backfill_log(target_table, row_id, city_code, source, write_time)
SELECT 'cms_activity', p.id, p.city_code, p.source, NOW()
FROM o14_activity_plan p
WHERE p.city_code IS NOT NULL;

DROP TEMPORARY TABLE o14_activity_plan;
DROP TEMPORARY TABLE o14_topic_plan;
DROP TEMPORARY TABLE o14_city_keyword;

-- 回填只会写入白名单城市码、只用两种来源；出现别的值说明有人在本迁移之外写坏了列，停下来人工看。
-- runner: assert=scalar-true
SELECT NOT EXISTS (
  SELECT 1 FROM cms_topic WHERE city_code IS NOT NULL AND city_code NOT REGEXP '^[0-9]{6}$'
  UNION ALL
  SELECT 1 FROM cms_activity WHERE city_code IS NOT NULL AND city_code NOT REGEXP '^[0-9]{6}$'
  UNION ALL
  SELECT 1 FROM o14_city_code_backfill_log WHERE source NOT IN ('o14_v2:club_city','o14_v2:parent_topic')
) AS assertion_ok;
