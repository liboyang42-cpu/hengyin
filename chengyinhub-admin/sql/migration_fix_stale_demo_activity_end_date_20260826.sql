-- runner: requires=transaction
-- ============================================================================
-- 修正演示活动 #1 的 end_date —— 它是唯一漏过「已过期不展示」闸的存量行
--
-- 病灶：2025-11 那批 8 条演示活动里，7 条 end_date=2026-03-28 12:00:00，
--       唯独 id=1 写成 2026-12-31 23:59:59。公开 Mapper 的硬闸是
--       `isNew=1 ⇒ end_date > now()`，于是这一条至今仍出现在玩家首页，
--       而它的 4 张票 end_time 全是 2026-01-30 —— 点进去只能得到
--       「该票种已截止报名」，是一张点不动的死卡。
--
-- 判据：它自己挂的 cms_topic(id=1) end_date 就是 2026-03-28 12:00:00，
--       12-31 与主题矛盾，按主题口径回正，不是为了藏而藏。
--
-- 安全性：该活动 cms_registration 记录数为 0（owner_type=2, owner_id=1），
--         没有任何人的订单、权益或退款依赖这个时间；WHERE 里带 NOT EXISTS
--         做 fail-closed，真有报名就一行都不改。
--
-- 可逆：改回 2026-12-31 23:59:59 即恢复原状，不删数据、不改状态位。
-- 纯 DML，不创建/修改表结构。
-- ============================================================================

UPDATE cms_activity
   SET end_date = '2026-03-28 12:00:00',
       update_by = 'fix_stale_demo_activity_end_date_20260826',
       update_time = NOW()
 WHERE id = 1
   AND name = '上海滨江10KM骑行挑战赛'
   AND end_date = '2026-12-31 23:59:59'
   AND NOT EXISTS (SELECT 1 FROM cms_registration r
                    WHERE r.owner_type = 2 AND r.owner_id = 1);
