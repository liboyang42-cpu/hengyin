-- 多俱乐部(C1):去掉 club.member_id 唯一约束,改普通索引,允许一个会员建多个俱乐部
-- 执行方式:连生产库 source 本文件(走 chengyin-db-migrate skill,只读预检后确认再执行)
-- 背景:原 migration_club.sql 的 UNIQUE KEY uk_club_member (member_id) 锁死"一会员一俱乐部";
--       C1 本期就做多俱乐部,需放开该唯一约束。
-- ⚠️ 风险:DROP UNIQUE 放开数据完整性约束,生产库执行前必须只读预检确认存量无依赖该唯一性。
-- ⚠️ 部署顺序:必须与新 jar(selectClubsByMemberId 列表化 + /api/club/my owned[])同批部署,
--             否则去唯一后旧 "limit 1" 查询会静默只取一行,用户的第 2..N 个俱乐部不可见。

ALTER TABLE club
    DROP INDEX uk_club_member,
    ADD INDEX idx_club_member (member_id);
