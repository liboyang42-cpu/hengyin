-- runner: requires=transaction
-- ============================================================================
-- 《生活不掉线》三章受控品类补齐
--
-- 背景:migration_seed_topic_template_shelf_20260726.sql 的章节 category_id 用
-- 按名 REGEXP 匹配 sys_category(type=1 受控品类),当时库里只有 运动健身/科技数码/
-- 美食烹饪/娱乐达人 四类 ⇒ 衣物/家里/出行三章 category_id 落成 NULL,
-- ChapterRecruitmentRules 闸下整段招商在详情页静默不下发(不报错、零告警)。
-- 07-26 种子预检注明:「缺哪个就先去后台配哪个品类,别改闸」——本文件即补品类。
--
-- 三个新品类名刻意落在 07-26 种子的 REGEXP 模式里(洗|衣 / 家政|维修 / 出行),
-- 后续若重放老种子,按名匹配也能命中同一批品类:
--   衣物洗护 → 衣物不掉线 / 家政维修 → 家里不掉线 / 出行服务 → 出行不掉线
-- icon 沿用现有四类的占位图(后台可换);sort_id 顺排 5/6/7。
--
-- ⚠️ type=1 受控品类是平台级的:新增后商家入驻/资料页的品类选项同步多这三项,
--    这正是这三章将来能招到对应商家的前提,不是副作用。
--
-- ★ 幂等:品类幂等键=(type=1, category_name);章节 UPDATE 仅在 category_id IS NULL 时生效。
-- ★ 纯 DML,InnoDB,单表锚点/单 JOIN 形状,必须带 --transaction 执行。
-- ============================================================================


-- ======================= 只读预检(执行前人工跑) =======================
--   SELECT id, category_name FROM sys_category WHERE type=1 AND del_flag=0;   -- 应无这三类
--   SELECT ch.name, ch.category_id FROM cms_topic_chapter ch
--     JOIN cms_topic t ON t.id=ch.topic_id
--    WHERE t.name='生活不掉线' ORDER BY ch.sort_id;                            -- 后三章应为 NULL


-- ======================= 一、三个受控品类 =======================
INSERT INTO sys_category (parent_id, category_name, type, level, sort_id, icon,
                          status, del_flag, create_by, create_time)
SELECT 0, '衣物洗护', 1, 0, 5,
       'https://xianghuioss.oss-cn-shanghai.aliyuncs.com/chenyin/c1.png',
       1, 0, 'seed_life_backup_categories_20260820', NOW()
  FROM cms_topic t
 WHERE t.name = '生活不掉线' AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM sys_category c
        WHERE c.type = 1 AND c.category_name = '衣物洗护' AND c.del_flag = 0);

INSERT INTO sys_category (parent_id, category_name, type, level, sort_id, icon,
                          status, del_flag, create_by, create_time)
SELECT 0, '家政维修', 1, 0, 6,
       'https://xianghuioss.oss-cn-shanghai.aliyuncs.com/chenyin/c1.png',
       1, 0, 'seed_life_backup_categories_20260820', NOW()
  FROM cms_topic t
 WHERE t.name = '生活不掉线' AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM sys_category c
        WHERE c.type = 1 AND c.category_name = '家政维修' AND c.del_flag = 0);

INSERT INTO sys_category (parent_id, category_name, type, level, sort_id, icon,
                          status, del_flag, create_by, create_time)
SELECT 0, '出行服务', 1, 0, 7,
       'https://xianghuioss.oss-cn-shanghai.aliyuncs.com/chenyin/c1.png',
       1, 0, 'seed_life_backup_categories_20260820', NOW()
  FROM cms_topic t
 WHERE t.name = '生活不掉线' AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM sys_category c
        WHERE c.type = 1 AND c.category_name = '出行服务' AND c.del_flag = 0);


-- ======================= 二、三章 category_id 补齐 =======================
UPDATE cms_topic_chapter ch
  JOIN cms_topic t ON t.id = ch.topic_id
   SET ch.category_id = (
       SELECT c.id FROM sys_category c
        WHERE c.type = 1 AND c.category_name = '衣物洗护' AND c.del_flag = 0
        ORDER BY c.id LIMIT 1)
 WHERE t.name = '生活不掉线' AND t.del_flag = 0
   AND ch.name = '衣物不掉线' AND ch.category_id IS NULL;

UPDATE cms_topic_chapter ch
  JOIN cms_topic t ON t.id = ch.topic_id
   SET ch.category_id = (
       SELECT c.id FROM sys_category c
        WHERE c.type = 1 AND c.category_name = '家政维修' AND c.del_flag = 0
        ORDER BY c.id LIMIT 1)
 WHERE t.name = '生活不掉线' AND t.del_flag = 0
   AND ch.name = '家里不掉线' AND ch.category_id IS NULL;

UPDATE cms_topic_chapter ch
  JOIN cms_topic t ON t.id = ch.topic_id
   SET ch.category_id = (
       SELECT c.id FROM sys_category c
        WHERE c.type = 1 AND c.category_name = '出行服务' AND c.del_flag = 0
        ORDER BY c.id LIMIT 1)
 WHERE t.name = '生活不掉线' AND t.del_flag = 0
   AND ch.name = '出行不掉线' AND ch.category_id IS NULL;


-- ======================= 落库后回读验证(「回执 ≠ 观测」) =======================
-- 1) 四章 category_id 全非空,且指向启用品类:
--   SELECT ch.name, ch.category_id, c.category_name, c.status
--     FROM cms_topic_chapter ch
--     JOIN cms_topic t ON t.id=ch.topic_id
--     LEFT JOIN sys_category c ON c.id=ch.category_id
--    WHERE t.name='生活不掉线' ORDER BY ch.sort_id;      -- 期望 4 行全有品类、status=1
-- 2) 幂等:重复执行后 SELECT COUNT(*) FROM sys_category
--      WHERE create_by='seed_life_backup_categories_20260820' 仍为 3。


-- ======================= 回滚 =======================
-- UPDATE cms_topic_chapter ch JOIN cms_topic t ON t.id=ch.topic_id
--    SET ch.category_id=NULL
--  WHERE t.name='生活不掉线' AND ch.name IN ('衣物不掉线','家里不掉线','出行不掉线');
-- DELETE FROM sys_category WHERE create_by='seed_life_backup_categories_20260820';
--   ⚠️ 若已有商家挂到这三个品类,先迁移商家再删品类。
