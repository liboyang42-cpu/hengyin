-- ROUND10 r1 F6(2026-11-04):migration_20261102_merchant_industry_categories.sql 的断言把 type 1 和 6 合在一起、
-- 用宽 LIKE('%花%' '%酒%' '%咖啡%')数到 >= 10 就算过 —— 主题类别里本来就有这些字的库,一行商家行业都没插也能过。
-- 本文件只读不写,在它之后跑,收紧为:
--   ① 至少有一行启用、未删的顶层 type=6(商家行业)—— 商家行业不能是空的;
--   ② 十个关键字逐个都要有着落:要么有 type=6 行,要么有 type=1 行(原文件的设计:同类 type=1 在用就跳过,
--      例如已有「咖啡」就不插「咖啡茶饮」,商家读侧按 type 1+6 取,照样看得到)。
--      逐个判,不再让十行「花」凑出 10。
-- 不改任何数据;可重复执行。

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM sys_category
        WHERE type = 6 AND parent_id = 0 AND status = 1 AND del_flag = 0) >= 1
   AND (SELECT COUNT(*) FROM (
          SELECT '咖啡' AS kw UNION ALL SELECT '茶馆' UNION ALL SELECT '书店' UNION ALL SELECT '手作'
          UNION ALL SELECT '古着' UNION ALL SELECT '文创' UNION ALL SELECT '花' UNION ALL SELECT '烘焙'
          UNION ALL SELECT '酒' UNION ALL SELECT '宠物') k
        WHERE EXISTS (SELECT 1 FROM sys_category c
                      WHERE c.type IN (1, 6) AND c.parent_id = 0 AND c.status = 1 AND c.del_flag = 0
                        AND c.category_name LIKE CONCAT('%', k.kw, '%'))) = 10
   AS merchant_industry_categories_per_keyword;
