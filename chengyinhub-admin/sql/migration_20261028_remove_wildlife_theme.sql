-- ============================================================================
-- 下架并移除测试主题「野生邻居观察局」(业主 2026-10-28:是测试主题，从数据库删掉)
--
-- 它是 migration_seed_official_solo_story_topics_20260823.sql 种下、
-- migration_publish_solo_story_topics_20260827.sql 上架的 5 个官方单人剧情之一(id 在 990052–990056)。
-- 先跑 _precheck_remove_wildlife_theme_20261028.sql:主题恰好 1 行、报名 / 购买 / 开玩 / 结算全部为 0 才可执行。
--
-- 处置：按仓库既有软删口径 del_flag=2(可逆，改回 0 即恢复),同时 user_status 1→0、is_recommend→0,
-- 让它立刻从首页推荐、主题列表、购买链路里消失。章节 / 节点 / 玩法行随主题不可达，不逐表删除。
-- 不动其余 4 个城市剧情。
--
-- 幂等:WHERE 钉住 id 区间 + 名称 + del_flag=0;重跑命中 0 行。纯 DML,不改表结构。
-- 回滚:UPDATE cms_topic SET del_flag = 0, user_status = 1, is_recommend = 1
--        WHERE id BETWEEN 990052 AND 990056 AND name = '野生邻居观察局' AND del_flag = 2;
-- 按 chengyin-db-migrate 流程只读预检 + 用户确认后由人工执行。
-- ============================================================================

UPDATE cms_topic
   SET del_flag = 2,
       user_status = 0,
       is_recommend = 0,
       update_by = 'remove_wildlife_theme_20261028',
       update_time = NOW()
 WHERE id BETWEEN 990052 AND 990056
   AND name = '野生邻居观察局'
   AND del_flag = 0;

-- runner: assert=scalar-true
SELECT COUNT(*) = 0
  FROM cms_topic
 WHERE id BETWEEN 990052 AND 990056
   AND name = '野生邻居观察局'
   AND del_flag = 0;
