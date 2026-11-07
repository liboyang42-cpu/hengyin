-- 商家行业类型补充(2026-11-02 走查修复 M-16,业主:标签可以更多一些,有很多城瘾合适的商家)
-- 2026-11-03 业主(第四轮):商家行业不得出现在主题类别和玩家路线偏好里,分表 —— 用自己的 sys_category type。
--   type=1 = 主题类别(发布页)与玩家「路线偏好」的来源,保持现有行不变;
--   type=6 = 商家行业(只给商家入驻、门店信息、章节招募品类、商家地图筛选与匹配读,接口 /api/category/list type=MERCHANT)。
--   商家读侧按 type 1 + 6 取:已有商家 / 章节引用的 type=1 记录照常解析,不迁移任何 id。
-- 做法:只新增 type=6,不改不删现有主题类别;同类关键字在 type 1/6 已有在用记录的跳过(例如已有「咖啡」就不再插「咖啡茶饮」)。可重复执行。
-- 存量处理:本文件 11-02 初版曾把这十行插成 type=1(未合并,只在走查用一次性库跑过)。下面把那些 type=1 同名行
--   改成 type=6,但只改**没有被主题 / 俱乐部 / 活动引用**的行;被引用的保持 type=1(需业主定,见 RESULT 第四轮)。

UPDATE sys_category c
SET c.type = 6, c.update_time = NOW()
WHERE c.type = 1 AND c.parent_id = 0
  AND c.category_name IN ('咖啡茶饮', '茶馆', '书店', '手作工坊', '古着中古', '文创杂货', '花店', '烘焙甜品', '酒馆小酌', '宠物友好')
  AND NOT EXISTS (SELECT 1 FROM cms_topic t WHERE FIND_IN_SET(c.id, t.category_ids) > 0)
  AND NOT EXISTS (SELECT 1 FROM club k WHERE k.category_id = c.id)
  AND NOT EXISTS (SELECT 1 FROM cms_activity a WHERE FIND_IN_SET(c.id, a.category_ids) > 0);

INSERT INTO sys_category (type, parent_id, category_name, level, sort_id, icon, status, del_flag, create_time, update_time)
SELECT 6, 0, n.name, 0, n.sort_id, NULL, 1, 0, NOW(), NOW()
FROM (SELECT '咖啡茶饮' AS name, 21 AS sort_id, '咖啡' AS kw UNION ALL SELECT '茶馆', 22, '茶馆'
      UNION ALL SELECT '书店', 23, '书店' UNION ALL SELECT '手作工坊', 24, '手作'
      UNION ALL SELECT '古着中古', 25, '古着' UNION ALL SELECT '文创杂货', 26, '文创'
      UNION ALL SELECT '花店', 27, '花' UNION ALL SELECT '烘焙甜品', 28, '烘焙'
      UNION ALL SELECT '酒馆小酌', 29, '酒' UNION ALL SELECT '宠物友好', 30, '宠物') n
WHERE NOT EXISTS (SELECT 1 FROM sys_category c WHERE c.type IN (1, 6) AND c.parent_id = 0 AND c.del_flag = 0
                    AND c.status = 1 AND c.category_name LIKE CONCAT('%', n.kw, '%'));

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM sys_category WHERE type IN (1, 6) AND parent_id = 0 AND status = 1 AND del_flag = 0
          AND (category_name LIKE '%咖啡%' OR category_name LIKE '%茶馆%' OR category_name LIKE '%书店%'
               OR category_name LIKE '%手作%' OR category_name LIKE '%古着%' OR category_name LIKE '%文创%'
               OR category_name LIKE '%花%' OR category_name LIKE '%烘焙%' OR category_name LIKE '%酒%'
               OR category_name LIKE '%宠物%')) >= 10
   AS merchant_industry_categories_ready;
