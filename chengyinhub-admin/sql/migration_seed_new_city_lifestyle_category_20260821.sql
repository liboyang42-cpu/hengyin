-- ============================================================================
-- 「生活美学」受控品类补齐(刚到这座城·G5 生活感安装 前置)
--
-- 背景:migration_seed_new_city_setup_plan_20260820.sql 的 G5 章按
-- REGEXP '花|书|咖啡|家居|生活' 匹配 sys_category(type=1),2026-08-21 预检
-- 线上 8 个品类零命中 ⇒ G5 会落 category_id NULL,ChapterRecruitmentRules
-- 闸下招商入口直接拒(同《生活不掉线》07-26 老坑,先例见
-- migration_seed_life_backup_categories_20260820.sql)。
--
-- 品类名「生活美学」刻意含「生活」,落在 G5 REGEXP 里;且不命中 G2/G4/G6 的
-- 任何模式,不会窜章。icon 沿用现有占位图;sort_id 顺排 9。
-- ⚠️ 必须先于主题种子执行(种子建章时按名解析品类)。
-- ⚠️ type=1 受控品类是平台级的:商家入驻/资料页品类选项会同步多这一项,
--    这正是 G5 将来能招到花店/书店/咖啡/家居好物商家的前提,不是副作用。
--
-- ★ 幂等键=(type=1, category_name='生活美学', del_flag=0)。单条幂等 INSERT,
--   语句级原子,普通模式执行(断言 SELECT 不在 --transaction 白名单内)。
-- ============================================================================

INSERT INTO sys_category (parent_id, category_name, type, level, sort_id, icon,
                          status, del_flag, create_by, create_time)
SELECT 0, '生活美学', 1, 0, 9,
       'https://xianghuioss.oss-cn-shanghai.aliyuncs.com/chenyin/c1.png',
       1, 0, 'seed_new_city_lifestyle_category_20260821', NOW()
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_category c
        WHERE c.type = 1 AND c.category_name = '生活美学' AND c.del_flag = 0);

-- 回读合同:品类存在且启用;G5 模式恰好命中它一条;G2/G4/G6 模式都不命中它。
-- runner: assert=scalar-true
SELECT (
    (SELECT COUNT(*) FROM sys_category c
      WHERE c.type=1 AND c.category_name='生活美学' AND c.del_flag=0 AND c.status=1) = 1
    AND
    (SELECT COUNT(*) FROM sys_category c
      WHERE c.del_flag=0 AND c.status=1 AND c.type=1
        AND c.category_name REGEXP '花|书|咖啡|家居|生活') = 1
    AND
    (SELECT COUNT(*) FROM sys_category c
      WHERE c.del_flag=0 AND c.status=1 AND c.type=1 AND c.category_name='生活美学'
        AND (c.category_name REGEXP '餐|食|饭|烘焙|轻食'
          OR c.category_name REGEXP '收纳|家居|家政|整理'
          OR c.category_name REGEXP '咖啡|书店|酒吧|空间|运动')) = 0
) AS migration_assertion;

-- 回滚:
-- DELETE FROM sys_category WHERE create_by='seed_new_city_lifestyle_category_20260821';
--   ⚠️ 若已有商家/章节挂到该品类,先迁移再删。
