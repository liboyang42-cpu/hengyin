-- 商家行业类型补充(2026-11-02 走查修复 M-16,业主:标签可以更多一些,有很多城瘾合适的商家)
-- 入驻申请与门店信息的「行业类型」胶囊按名称关键字认领 sys_category type=1 的记录(utils/merchant-industry.js)。
-- 现有 type=1 只有主题向的类别,书店、茶馆、手作工坊、古着中古、文创杂货、花店这类城瘾常见小店认领不到记录,胶囊不出现。
-- 做法:只新增,不改不删;同类关键字已有在用记录的跳过(例如已有「咖啡」就不再插「咖啡茶饮」)。可重复执行。
-- 注意:type=1 同时是主题类别与玩家路线偏好的选项来源,新行会一并出现在那两处(见 docs/delivery/walk-fix-20261102/l5-onboard/RESULT.md)。

INSERT INTO sys_category (type, parent_id, category_name, level, sort_id, icon, status, del_flag, create_time, update_time)
SELECT 1, 0, n.name, 0, n.sort_id, NULL, 1, 0, NOW(), NOW()
FROM (SELECT '咖啡茶饮' AS name, 21 AS sort_id, '咖啡' AS kw UNION ALL SELECT '茶馆', 22, '茶馆'
      UNION ALL SELECT '书店', 23, '书店' UNION ALL SELECT '手作工坊', 24, '手作'
      UNION ALL SELECT '古着中古', 25, '古着' UNION ALL SELECT '文创杂货', 26, '文创'
      UNION ALL SELECT '花店', 27, '花' UNION ALL SELECT '烘焙甜品', 28, '烘焙'
      UNION ALL SELECT '酒馆小酌', 29, '酒' UNION ALL SELECT '宠物友好', 30, '宠物') n
WHERE NOT EXISTS (SELECT 1 FROM sys_category c WHERE c.type = 1 AND c.parent_id = 0 AND c.del_flag = 0
                    AND c.status = 1 AND c.category_name LIKE CONCAT('%', n.kw, '%'));

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM sys_category WHERE type = 1 AND parent_id = 0 AND status = 1 AND del_flag = 0
          AND (category_name LIKE '%咖啡%' OR category_name LIKE '%茶馆%' OR category_name LIKE '%书店%'
               OR category_name LIKE '%手作%' OR category_name LIKE '%古着%' OR category_name LIKE '%文创%'
               OR category_name LIKE '%花%' OR category_name LIKE '%烘焙%' OR category_name LIKE '%酒%'
               OR category_name LIKE '%宠物%')) >= 10
   AS merchant_industry_categories_ready;
