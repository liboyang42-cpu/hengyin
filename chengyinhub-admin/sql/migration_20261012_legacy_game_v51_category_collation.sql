-- 七款 v5.1 旧种子解析了 type=1 的商家品类，却未写入 activity_categoryids。
-- 仅修复本批官方种子的空分类，不覆盖作者改动，不将商家品类写入 type=4 的 category_id。
-- 独立前向修复，旧20261007 migration字节及历史hash不变。
-- 前置：migration_seed_merchant_game_v51_20260827.sql；生产须核对旧迁移真实台账。
-- 旧迁移首次执行失败不能由此文件自动覆盖，需负责人只读对账后制定执行计划。
-- runner: requires=transaction
CREATE TEMPORARY TABLE legacy_game_v51_category_forward (
    title VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci PRIMARY KEY,
    category_name VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL
) ENGINE=InnoDB;
INSERT INTO legacy_game_v51_category_forward (title, category_name) VALUES
    ('闭眼味觉师', '美食烹饪'), ('沉默点单', '美食烹饪'), ('便利店调酒师', '美食烹饪'),
    ('治愈音乐角', '生活美学'), ('午夜电台', '娱乐达人'), ('低碳行动', '运动健身'), ('今日城市签', '生活美学');
CREATE TEMPORARY TABLE legacy_game_v51_category_forward_guard (id INT PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO legacy_game_v51_category_forward_guard (id) VALUES (1);
-- 每个目标品类须唯一存在，同名源记录必须属于本批种子；否则主键冲突整笔回滚。
INSERT INTO legacy_game_v51_category_forward_guard (id)
SELECT 1 WHERE EXISTS (
    SELECT f.title FROM legacy_game_v51_category_forward f
    LEFT JOIN sys_category c ON c.category_name=f.category_name AND c.type=1 AND c.status=1 AND c.del_flag=0
    WHERE EXISTS (SELECT 1 FROM cms_template_library l WHERE l.title=f.title
                  AND l.member_id=100000 AND l.del_flag=0)
    GROUP BY f.title HAVING COUNT(c.id)<>1
);
INSERT INTO legacy_game_v51_category_forward_guard (id)
SELECT 1 WHERE EXISTS (
    SELECT l.title FROM cms_template_library l JOIN legacy_game_v51_category_forward f ON f.title=l.title
    WHERE l.member_id=100000 AND l.del_flag=0
    GROUP BY l.title HAVING COUNT(*)<>1 OR COALESCE(MIN(l.create_by),'')<>'seed_merchant_game_v51_20260827'
);
UPDATE cms_template_library l
JOIN legacy_game_v51_category_forward f ON f.title=l.title
SET l.activity_categoryids=(SELECT CAST(c.id AS CHAR) FROM sys_category c
    WHERE c.category_name=f.category_name AND c.type=1 AND c.status=1 AND c.del_flag=0)
WHERE l.member_id=100000 AND l.del_flag=0 AND l.create_by='seed_merchant_game_v51_20260827'
  AND COALESCE(TRIM(l.activity_categoryids),'')='';
-- Transaction readback guard: any still-empty target collides with existing id=1 and rolls back all updates.
INSERT INTO legacy_game_v51_category_forward_guard (id)
SELECT 1 WHERE EXISTS (SELECT 1 FROM cms_template_library l
    JOIN legacy_game_v51_category_forward f ON f.title=l.title
    WHERE l.member_id=100000 AND l.del_flag=0 AND l.create_by='seed_merchant_game_v51_20260827'
      AND COALESCE(TRIM(l.activity_categoryids),'')='');
DROP TEMPORARY TABLE legacy_game_v51_category_forward_guard;
DROP TEMPORARY TABLE legacy_game_v51_category_forward;
