-- ============================================================================
-- 模板页货架上架 6 个自由探索主题模板(2026-10-28,配合小程序模板页货架改版 / 业主定稿原型 v21)
--
-- 现状(2026-10-27 线上公开接口实读):/api/template/topic-template/list 返回 0 条。
-- 货架口径 publicTopicTemplateEligibility = del_flag=0 AND is_template=1 AND status=1 AND user_status=1,
-- 而这 6 个主题种子落库时都是 user_status=0:
--   · migration_seed_shanghai_solo_topics_20260822.sql:风味巡游 / 不惑之年 / 预制人生 / 高温末日逃生 / 上海像一部旧电影
--   · migration_seed_topic_template_shelf_20260726.sql:生活不掉线
-- 本文件只把这 6 条的 user_status 0 → 1,让它们出现在货架上。templateStatus 仍是 EXPERIMENTAL,
-- 货架照常标「实验模板」;不改内容、不改 VERIFIED、不碰 990052–990056 那 5 个付费城市剧情(它们是
-- is_template=0 的在售商品，改成模板会把它们从首页推荐里拿掉，另行决定)。
--
-- 只读预检(执行前先跑，期望 6 行、user_status 全为 0、is_template 全为 1):
--   SELECT id, name, is_template, status, user_status, template_status, del_flag
--     FROM cms_topic
--    WHERE del_flag = 0 AND name IN ('风味巡游','不惑之年','预制人生','高温末日逃生','上海像一部旧电影','生活不掉线');
-- 若同名出现多行或 is_template<>1,先停下核对，不要执行。
--
-- 幂等：WHERE 钉住名称 + is_template=1 + status=1 + user_status=0;重跑不改任何行。纯 DML,不改表结构。
-- 回滚：同样 WHERE(把 user_status=0 换成 1)把 user_status 改回 0。
-- 按 chengyin-db-migrate 流程只读预检 + 用户确认后由人工执行。
-- ============================================================================

UPDATE cms_topic
   SET user_status = 1,
       update_by = 'template_shelf_list_free_explore_20261028',
       update_time = NOW()
 WHERE del_flag = 0
   AND is_template = 1
   AND status = 1
   AND user_status = 0
   AND name IN ('风味巡游', '不惑之年', '预制人生', '高温末日逃生', '上海像一部旧电影', '生活不掉线');

-- runner: assert=scalar-true
SELECT COUNT(*) = 0
  FROM cms_topic
 WHERE del_flag = 0
   AND is_template = 1
   AND status = 1
   AND user_status = 0
   AND name IN ('风味巡游', '不惑之年', '预制人生', '高温末日逃生', '上海像一部旧电影', '生活不掉线');
