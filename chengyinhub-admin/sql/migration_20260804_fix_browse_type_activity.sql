-- 2026-08-04 修正 tms_browse_record.browse_type 存量错码
--
-- 背景:全仓 browse_type 约定「活动=2、主题=1」(ApiActivityController 列表页两处
-- countBrowseByTypeAndTargets(2, activityIds)、ApiTopicController 两处传 1),但唯一的写入点
-- ApiActivityController.activityInfo 一直用 setBrowseType(1L) 写活动浏览。后果:
--   ① 活动详情读 browse_type=2 永远读不到自己的浏览;
--   ② 主题列表读 browse_type=1,读到的其实是活动的浏览记录,按 target_id 匹配 ——
--      活动 id 与主题 id 是两张表各自的自增值、空间重叠,所以主题浏览量是错配噪音。
-- 代码侧已在同一 PR 改为 setBrowseType(2L)。这份 SQL 处理存量。
--
-- ★为什么存量可以整体改:app 端从来只有活动详情这一个写入点(ApiTopicController 只读不写,
--   TmsBrowseRecordController 是后台 CRUD)。因此历史上所有 browse_type=1 的行都是活动浏览,
--   不需要靠 target_title 猜归属。
--
-- ⚠️ 执行前必须先跑下面的只读预检,确认「browse_type=1 的行确实都能在 cms_activity 里找到对应
--    活动」,再决定是否 UPDATE。预检结果不符合预期就停下来问,不要硬跑。

-- ============ 只读预检(先跑这三条,不改数据) ============
-- 1) 存量分布:期望看到 browse_type=1 有数、browse_type=2 为 0 或极少
-- SELECT browse_type, COUNT(*) AS rows_cnt, MIN(browse_time), MAX(browse_time)
-- FROM tms_browse_record GROUP BY browse_type;

-- 2) browse_type=1 的 target_id 有多少能对上活动、多少对不上
--    对不上的行说明假设不成立(可能有未知写入来源),此时不要执行 UPDATE
-- SELECT SUM(a.id IS NOT NULL) AS matched_activity, SUM(a.id IS NULL) AS unmatched
-- FROM tms_browse_record b LEFT JOIN cms_activity a ON a.id = b.target_id
-- WHERE b.browse_type = 1;

-- 3) 抽样看 target_title 是否与活动名一致(再确认一次归属)
-- SELECT b.target_id, b.target_title, a.name FROM tms_browse_record b
-- LEFT JOIN cms_activity a ON a.id = b.target_id WHERE b.browse_type = 1 LIMIT 20;

-- ============ 预检通过后执行 ============
-- ⚠️⚠️ 本文件禁止整体执行(source / 一把梭跑全文)。
-- 下面的 UPDATE 是全表破坏性写入,**故意保持注释态**:预检是注释、UPDATE 是裸语句的话,
-- 任何"按文件整体执行"的迁移路径都会跳过预检直接改全表 —— 那样"预检不符合预期就停"
-- 就只剩人的自觉,没有机制。要跑请先手工执行上面三条预检,确认 unmatched = 0,
-- 再手工解开下面这一行单独执行。
--
-- UPDATE tms_browse_record SET browse_type = 2 WHERE browse_type = 1;

-- ============ 执行后回读(确认对面状态真的变了) ============
-- SELECT browse_type, COUNT(*) FROM tms_browse_record GROUP BY browse_type;
-- 期望:browse_type=1 为 0 行,原先 1 的行数全部落到 2。
