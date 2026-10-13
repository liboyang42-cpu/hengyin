-- 俱乐部加入策略：0=公开直进，1=需主理人审批。
-- 类别：③ 数据库迁移。合并前必须由总控走 chengyin-db-migrate，只读预检、用户确认、快照后手动落库。
-- 部署顺序：本 SQL 先落库，再合并/部署引用它的新 jar。
--
-- ★ MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，故使用普通 ADD COLUMN；执行前必须先跑下方预检。
-- ★ 代码兼容缺列：迁移未执行时能力探针返回 false，全部俱乐部继续按“公开直进”，编辑页隐藏策略开关。
--
-- 执行前预检（第一条必须返回 0 行；第二条必须返回 member_reserved_quota，确认 AFTER 锚点存在；
-- 第三条确认 status 仍是 TINYINT(1) NOT NULL DEFAULT 1；第四条确认复用状态位不存在异常值）：
-- SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
-- FROM information_schema.COLUMNS
-- WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'club' AND COLUMN_NAME = 'join_policy';
-- SELECT COLUMN_NAME
-- FROM information_schema.COLUMNS
-- WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'club' AND COLUMN_NAME = 'member_reserved_quota';
-- SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
-- FROM information_schema.COLUMNS
-- WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'club_member' AND COLUMN_NAME = 'status';
-- SELECT status, COUNT(*) AS row_count
-- FROM club_member
-- WHERE del_flag = 0
-- GROUP BY status
-- ORDER BY status;

ALTER TABLE club
  ADD COLUMN join_policy TINYINT(1) NOT NULL DEFAULT 0
  COMMENT '加入策略:0公开直进 1需主理人审批'
  AFTER member_reserved_quota;

ALTER TABLE club_member
  MODIFY COLUMN status TINYINT(1) NOT NULL DEFAULT 1
  COMMENT '成员状态:0待审批 1正常 2已拒绝';

-- 执行后回读（必须恰好 1 行，且 default=0、nullable=NO）：
-- SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
-- FROM information_schema.COLUMNS
-- WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'club' AND COLUMN_NAME = 'join_policy';
-- SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
-- FROM information_schema.COLUMNS
-- WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'club_member' AND COLUMN_NAME = 'status';
-- SELECT join_policy, COUNT(*) AS club_count FROM club WHERE del_flag = 0 GROUP BY join_policy;
--
-- 回滚 SQL（维护窗口内暂停入会写；会丢失私密配置并物理删除待审/拒绝记录）：
-- ★ 顺序：先导出并清理 0/2 状态、回读只剩 1，再改/删列，最后才允许回滚旧 jar；
--   不能先回滚 jar，否则旧 Mapper 会把待审/拒绝行当正式成员。
-- SELECT COUNT(*) AS private_club_count FROM club WHERE join_policy = 1 AND del_flag = 0;
-- SELECT * FROM club_member WHERE status IN (0, 2) ORDER BY club_id, id;
-- UPDATE club SET join_policy = 0 WHERE join_policy <> 0;
-- DELETE FROM club_member WHERE status IN (0, 2);
-- SELECT status, COUNT(*) AS row_count
-- FROM club_member WHERE status <> 1 GROUP BY status;
-- ★ 上条必须返回 0 行，才可继续：
-- ALTER TABLE club_member
--   MODIFY COLUMN status TINYINT(1) NOT NULL DEFAULT 1 COMMENT '1正常';
-- ALTER TABLE club DROP COLUMN join_policy;
