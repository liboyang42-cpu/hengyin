-- 主题类别换成城市主题该有的分类(2026-11-01 上线前生产走查,业主:都要修)
-- 现象:发布主题选「主题类别」看到的是 运动健身 / 科技数码 / 衣物洗护 / 家政维修 / 出行服务 …(sys_category type=1 的通用种子)。
-- type=1 同时被三处使用:主题类别(发布页)、玩家资料「路线偏好」、商家装修「行业类型」(按名称关键字认领记录)。
-- 做法(不删任何记录,已引用的 id 全部保留):
--   改名:1 运动健身→城市运动、2 科技数码→艺术展览、3 美食烹饪→美食探店、4 娱乐达人→娱乐休闲;25 生活美学保留(商家「生活美学」认领它)。
--   新增:城市漫步、历史人文、解谜冒险、亲子同行、摄影打卡、户外徒步(接替出行服务,商家「户外出行」认领它)、夜游。
--   隐藏(status=0):21 衣物洗护、22 家政维修、23 出行服务 —— 不是主题类别;现有主题/商家没有引用(只读预检 2026-11-01)。
-- 图标按名称映射(utils/category-icon.js),新名称都落在已有图标上。可重复执行。

UPDATE sys_category SET category_name = '城市运动', sort_id = 8, update_time = NOW() WHERE id = 1 AND type = 1 AND category_name = '运动健身';
UPDATE sys_category SET category_name = '艺术展览', sort_id = 7, update_time = NOW() WHERE id = 2 AND type = 1 AND category_name = '科技数码';
UPDATE sys_category SET category_name = '美食探店', sort_id = 3, update_time = NOW() WHERE id = 3 AND type = 1 AND category_name = '美食烹饪';
UPDATE sys_category SET category_name = '娱乐休闲', sort_id = 11, update_time = NOW() WHERE id = 4 AND type = 1 AND category_name = '娱乐达人';
UPDATE sys_category SET sort_id = 12, update_time = NOW() WHERE id = 25 AND type = 1 AND category_name = '生活美学';
UPDATE sys_category SET status = 0, update_time = NOW() WHERE type = 1 AND id IN (21, 22, 23) AND category_name IN ('衣物洗护', '家政维修', '出行服务');

INSERT INTO sys_category (type, parent_id, category_name, level, sort_id, icon, status, del_flag, create_time, update_time)
SELECT 1, 0, n.name, 0, n.sort_id, NULL, 1, 0, NOW(), NOW()
FROM (SELECT '城市漫步' AS name, 1 AS sort_id UNION ALL SELECT '历史人文', 2 UNION ALL SELECT '解谜冒险', 4
      UNION ALL SELECT '夜游', 5 UNION ALL SELECT '摄影打卡', 6 UNION ALL SELECT '户外徒步', 9 UNION ALL SELECT '亲子同行', 10) n
WHERE NOT EXISTS (SELECT 1 FROM sys_category c WHERE c.type = 1 AND c.parent_id = 0 AND c.category_name = n.name AND c.del_flag = 0);

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM sys_category WHERE type = 1 AND parent_id = 0 AND status = 1 AND del_flag = 0
          AND category_name IN ('衣物洗护', '家政维修', '出行服务', '科技数码')) = 0
   AND (SELECT COUNT(*) FROM sys_category WHERE type = 1 AND parent_id = 0 AND status = 1 AND del_flag = 0
          AND category_name IN ('城市漫步', '历史人文', '美食探店', '解谜冒险', '夜游', '摄影打卡', '艺术展览', '城市运动', '户外徒步', '亲子同行', '娱乐休闲', '生活美学')) = 12
   AS theme_categories_ready;
