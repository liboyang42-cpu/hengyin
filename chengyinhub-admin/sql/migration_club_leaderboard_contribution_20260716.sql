-- 俱乐部排行榜:排序键从「全局积分」换成「本团贡献」—— 配套索引
-- 来源:《排行榜本团贡献度量_设计》/ 流2 · 俱乐部排行榜(2026-07-16)
--
-- 【背景】
--   改造前 ClubMemberMapper.xml 的 selectClubLeaderboard 按 ums_member.point(全平台历史积分)排,
--   与「这个俱乐部」零关系。改造后按本团贡献排,新查询要扫两张此前没人按这个维度查过的表:
--
--     player_badge          —— 找「谁通关了本团的哪些团」(badge_code='TOPIC_CLEAR', source_id=主题ID)
--     player_node_progress  —— 找「每人每团的实测用时」(MAX-MIN checkin_time)
--
--   两张表现有的索引都【帮不上这个查询】:
--     player_badge.uk_member_badge(member_id, badge_code, source_id) —— 最左列是 member_id,
--       而新查询是「先按 badge_code 圈 TOPIC_CLEAR、再拿 source_id 去 join cms_topic」,
--       不带 member_id 条件 ⇒ 最左前缀不匹配 ⇒ 全表扫。
--     player_node_progress.uk_member_activity_node(member_id, activity_id, node_id) —— 同理,
--       新查询按 topic_id 圈本团的团,topic_id 根本不在这个索引里 ⇒ 全表扫。
--
--   cms_topic 不用动:idx_topic_club(club_id) 已存在(bak_cms_topic_schema_20260709.sql:51),
--   新查询的 t.club_id = ? 直接走它。设计文档让加这条索引,是【文档写错了】,现码已有。
--
-- 【本迁移做什么】
--   两条 ADD INDEX,纯读性能。不加列、不改列、不写数据、不改任何行为。
--
-- 【风险】
--   低。加索引不改语义 —— 榜单结果加不加它都一样,只差快慢。
--   ⚠️ 唯一的真风险是【锁表时长】:MySQL 8 加索引默认 ALGORITHM=INPLACE 允许并发读写,
--      但表大时仍有短暂元数据锁。两张表都是玩法侧流水,量级见预检 P3。
--
-- 【幂等】
--   两条 ADD INDEX **不幂等**(索引已存在会报 1061)—— MySQL 8 没有 ADD INDEX IF NOT EXISTS
--   (那是 MariaDB 专属,在 MySQL 8 上直接 1064 语法错)。执行前必须跑预检 P1,已存在的划掉。
--
-- 【可逆】
--   ALTER TABLE player_badge DROP INDEX idx_badge_code_source;
--   ALTER TABLE player_node_progress DROP INDEX idx_node_progress_topic_member;
--   删索引不影响正确性,只是查询变慢 ⇒ 回退与 jar 版本无先后要求。
--
-- 【部署顺序】
--   本迁移与新 jar **无强先后**:新 SQL 不依赖这两条索引,没有它也能跑出正确结果(只是慢)。
--   这点与「加列」类迁移不同 —— 加列不先落库,jar 读新列直接 SQL 报错。
--   仍建议先落库,免得部署后第一波查询裸奔全表扫。
--
-- ⚠️⚠️ 生产库 MySQL 8。禁止直接执行。必须走 chengyin-db-migrate:先只读预检 → 用户确认 → 才执行。


-- ======================= 【执行方式】 =======================
-- · 两条按顺序逐条执行,失败即停。
-- · **中途失败无害,不要回滚**:加索引不改数据、不改语义,修好失败那条继续即可。
-- · MySQL DDL 隐式提交、不可回滚 —— 这正是「失败即停 + 继续而非回滚」的原因。


-- ======================= 第一步:只读预检 =======================
--
-- P1【必看 · 决定跳过哪条 ALTER】两条索引是否已存在:
--   SELECT TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX, COLUMN_NAME
--     FROM information_schema.STATISTICS
--    WHERE TABLE_SCHEMA = DATABASE()
--      AND INDEX_NAME IN ('idx_badge_code_source', 'idx_node_progress_topic_member')
--    ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX;
--   · 0 行 → 两条 ALTER 全执行。
--   · 有命中 → 把已存在的那条划掉(撞已存在索引 = 1061)。
--
-- P2【必看 · 决定这次改造有没有数据可排】本团贡献的两个数据源到底有没有数据:
--   SELECT COUNT(*) AS topic_clear_badges FROM player_badge
--    WHERE badge_code = 'TOPIC_CLEAR' AND del_flag = 0;
--   SELECT COUNT(*) AS node_checkins FROM player_node_progress WHERE del_flag = 0;
--   ⚠️ **若 topic_clear_badges = 0 → 上线后所有俱乐部的贡献榜会【全员 0 分】**,
--      这不是 bug,是「还没有人通关过任何团」的真实反映(改造前按全局积分排,
--      哪怕没人玩过本团也能排出名次 —— 那正是要修掉的假象)。
--      先确认这是你要的,再决定是否与前端同批上线。
--
-- P3【锁表评估】两张表的行数:
--   SELECT COUNT(*) FROM player_badge;
--   SELECT COUNT(*) FROM player_node_progress;
--   · 万级以内 → 秒级完成,随时可跑。
--   · 十万级以上 → 择低峰跑。
--
-- P4【口径自查 · 只读】拿真数据预览一下新榜单会长什么样(以某个 clubId 为例,把 ? 换成真实ID):
--   SELECT pb.member_id, COUNT(DISTINCT t.id) AS clear_count,
--          SUM(IFNULL(t.total_mileage, 0)) AS mileage
--     FROM player_badge pb
--     JOIN cms_topic t ON t.id = pb.source_id
--    WHERE pb.badge_code = 'TOPIC_CLEAR' AND pb.del_flag = 0
--      AND t.club_id = ? AND t.del_flag = 0
--    GROUP BY pb.member_id ORDER BY clear_count DESC;
--   ⚠️ 若 mileage 大面积为 0/NULL → cms_topic.total_mileage 没填,里程维度与配速榜会退化
--      (配速 = 用时÷里程,零里程时 Service 置 null 并在配速榜沉底,不会除零崩)。


-- ======================= 第二步:加索引 =======================

-- 支撑「找出本团被通关的团」:先按 badge_code 圈 TOPIC_CLEAR,再用 source_id join cms_topic.id。
-- 列序 (badge_code, source_id) 而非反过来:badge_code 是等值条件(最左),source_id 是 join 键。
ALTER TABLE player_badge
    ADD INDEX idx_badge_code_source (badge_code, source_id);

-- 支撑「每人每团实测用时」:按 topic_id 圈本团的团,再按 member_id 分组取 MAX-MIN(checkin_time)。
-- 列序 (topic_id, member_id):topic_id 是过滤键(最左),member_id 是分组键。
ALTER TABLE player_node_progress
    ADD INDEX idx_node_progress_topic_member (topic_id, member_id);
