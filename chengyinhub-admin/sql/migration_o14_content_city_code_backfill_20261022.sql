-- O-14(C8-1 选 B):cms_topic / cms_activity.city_code 回填。
-- ⚠️ 只由 owner 在预检(PR 描述里的只读预览查询)确认后手工执行；本仓自动化从不执行它。
-- 前置：migration_o14_cms_topic_city_code_20261022.sql、migration_o14_cms_activity_city_code_20261022.sql。
-- 规则(保守、可重放)：
--   1. 只填 city_code IS NULL 的行，已有值一律不覆盖(人工修正优先)。
--   2. address_name 命中且【只命中一个】城市关键词才填；命中 0 个或多个(如「上海-苏州一日游」)都跳过，留给人工。
--   3. 活动自身无法判定时，继承父主题已有的 city_code(父主题须先回填，所以放在主题之后)。
--   4. 城市码为 adcode 城市级(直辖市取 xx0100)，与小程序 cityCode 口径一致。
-- 幂等：所有 UPDATE 都带 city_code IS NULL；重跑只会补上新命中的行，不改已有值。
-- 排序规则：address_name 是 utf8mb4_general_ci,临时表与 LIKE 都显式钉成同一排序规则，避免 1267 Illegal mix of collations。
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

UPDATE cms_topic t
JOIN (
  SELECT src.id, MIN(k.city_code) AS city_code
  FROM cms_topic src
  JOIN o14_city_keyword k
    ON src.address_name LIKE CONCAT('%', k.keyword, '%') COLLATE utf8mb4_general_ci
  WHERE src.city_code IS NULL AND src.address_name IS NOT NULL AND src.address_name <> ''
  GROUP BY src.id
  HAVING COUNT(DISTINCT k.city_code) = 1
) matched ON matched.id = t.id
SET t.city_code = matched.city_code
WHERE t.city_code IS NULL;

UPDATE cms_activity a
JOIN (
  SELECT src.id, MIN(k.city_code) AS city_code
  FROM cms_activity src
  JOIN o14_city_keyword k
    ON src.address_name LIKE CONCAT('%', k.keyword, '%') COLLATE utf8mb4_general_ci
  WHERE src.city_code IS NULL AND src.address_name IS NOT NULL AND src.address_name <> ''
  GROUP BY src.id
  HAVING COUNT(DISTINCT k.city_code) = 1
) matched ON matched.id = a.id
SET a.city_code = matched.city_code
WHERE a.city_code IS NULL;

UPDATE cms_activity a
JOIN cms_topic parent ON parent.id = a.topic_id
SET a.city_code = parent.city_code
WHERE a.city_code IS NULL AND a.topic_id IS NOT NULL AND a.topic_id <> 0
  AND parent.city_code IS NOT NULL;

DROP TEMPORARY TABLE o14_city_keyword;

-- 回填只会写入白名单城市码；出现别的值说明有人在本迁移之外写坏了列，停下来人工看。
-- runner: assert=scalar-true
SELECT NOT EXISTS (
  SELECT 1 FROM cms_topic WHERE city_code IS NOT NULL AND city_code NOT REGEXP '^[0-9]{6}$'
  UNION ALL
  SELECT 1 FROM cms_activity WHERE city_code IS NOT NULL AND city_code NOT REGEXP '^[0-9]{6}$'
) AS assertion_ok;
