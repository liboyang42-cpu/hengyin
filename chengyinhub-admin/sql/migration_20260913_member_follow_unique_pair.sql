-- R10-07 收尾:用户关注关系 (member_id, follow_member_id) 唯一化
--
-- 背景:关注接口原先「查无则插」,并发首次关注(或丢响应后的重试与并发叠加)会留下同键重复行,
--       双方关注/粉丝计数虚高。应用层已改为:同一 follower 行锁(ums_member.id FOR UPDATE)内
--       完成查重 + 写,顺序/并发重试都幂等;本迁移补库级唯一约束作为第二道防线。
--
-- ⚠️ 本文件只落盘,本次修复不在生产执行;由总控走迁移流程(只读预检 + 确认)后再跑。
-- ⚠️ 不删除任何历史关系:存在同键重复物理行时,ALTER 会直接失败(不删数据),
--    必须先按第 1 步报告人工确认数据订正策略(见第 3 步,单独审批后再执行)。

-- 1) 只读预检:先看清重复规模与样本(不改数据)
SELECT member_id, follow_member_id, COUNT(*) AS c, MIN(id) AS keep_id,
       GROUP_CONCAT(id ORDER BY id) AS duplicate_ids
  FROM ums_member_follow
 GROUP BY member_id, follow_member_id
HAVING c > 1;

-- 2) 加唯一索引(有重复会失败且不改数据;索引已存在会报重名,可先查 information_schema)
ALTER TABLE ums_member_follow
  ADD UNIQUE INDEX uk_member_follow_pair (member_id, follow_member_id);

-- 3) 重复行订正(不在本迁移自动执行;仅当第 1 步有输出且人工审批通过后单独运行):
--    同一对 (member_id, follow_member_id) 的重复行属于同一段关注关系的重复物理行,
--    保留 MIN(id) 一条即可,不影响任何一对独立关系:
--    DELETE f1 FROM ums_member_follow f1
--    JOIN ums_member_follow f2
--      ON f1.member_id = f2.member_id
--     AND f1.follow_member_id = f2.follow_member_id
--     AND f1.id > f2.id;
--    再重跑第 2 步。
