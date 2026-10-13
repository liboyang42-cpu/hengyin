-- 生产回读(2026-08-30):本文件所写字段已提前落库；本文件补齐仓库迁移凭证与长期对账断言，不应再人工重跑。
-- ============================================================================
-- 上架 5 个官方单人剧情主题 + 标为推荐(990052–990056)
--
-- 这 5 个是库里唯一「买了就能自己开走」的内容:self_play=1、¥19.90、每个 5 个 GPS 点位、
-- 名额 100、product_role 已登记 PLATFORM 自营。在此之前它们是草稿,首页「推荐主题」
-- 因此只能从 4 条死胡同主题里挑(self_play=0 且可参加场次 0),推荐位形同虚设。
--
-- ⚠️⚠️ 实走闸:种子文件 migration_seed_official_solo_story_topics_20260823.sql 写明
--   「公开前须完成 25 点 GCJ-02 双机实走、内容审核与功能开关回读」。
--   **该前提尚未完成**。本次上架是用户 2026-08-27 在被明确告知该风险后作出的决定,
--   不是自动放行。风险:玩家可能走到打不了卡的点位。留档于此,出事可追。
--
-- 改三个字段才买得动(购买链路 10 道闸,缺一即抛):
--   user_status      0 → 1
--   lifecycle        0 → 3
--   fee_platform_rate NULL → 0.0300
-- 外加 is_recommend = 1,让首页推荐位能与底部全量 feed 区分开。
--
-- 幂等:WHERE 钉住 id + name + self_play=1;重跑是等值覆盖。纯 DML,不修改表结构。
-- ============================================================================

UPDATE cms_topic
   SET user_status = 1,
       lifecycle = 3,
       fee_platform_rate = 0.0300,
       is_recommend = 1,
       update_by = 'publish_solo_story_topics_20260827',
       update_time = NOW()
 WHERE id BETWEEN 990052 AND 990056
   AND del_flag = 0
   AND status = 1
   AND self_play = 1
   AND self_play_price = 19.90
   AND name IN ('城市藏宝猎人', '异常事务局：不存在的坐标', '时间污染：被删除的今天',
                '末日广播：最后一座中继站', '野生邻居观察局');

UPDATE cms_topic
   SET is_recommend = 0,
       update_by = 'publish_solo_story_topics_20260827',
       update_time = NOW()
 WHERE id = 1
   AND del_flag = 0
   AND is_recommend = 1
   AND self_play = 0;

-- runner: assert=scalar-true
SELECT (
    SELECT COUNT(*) FROM cms_topic
     WHERE id BETWEEN 990052 AND 990056
       AND del_flag = 0
       AND status = 1
       AND user_status = 1
       AND lifecycle = 3
       AND self_play = 1
       AND self_play_price = 19.90
       AND fee_platform_rate = 0.0300
       AND is_recommend = 1
) = 5
AND (
    SELECT COUNT(*) FROM cms_topic
     WHERE status = 1 AND user_status = 1 AND del_flag = 0
       AND is_template = 0 AND is_recommend = 1
) = 5 AS migration_assertion;
