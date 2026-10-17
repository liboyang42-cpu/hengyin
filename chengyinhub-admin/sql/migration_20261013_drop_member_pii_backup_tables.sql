-- ============================================================================
-- F-L5S-04：删除带会员/商家/主理人 PII 的历史手工备份表
--
-- 背景:
--   sql/base_schema_20260818.sql(生产 schema-only 快照)里有 2026-07/08 手工整改时留下的备份表，
--   其中以下 6 张含手机号、openid/unionid/appleId、姓名、微信号、地址或口令列：
--     _bak_20260705_ums_member / _bak_20260708_ums_member   (phone, password, name, wechat, wx_open_id, union_id, apple_id, work_address)
--     _bak_20260708_mms_merchant                             (name, wechat, phone, address)
--     _bak_20260705_club_leader                              (leader_name, phone)
--     _bak_20260705_club / club_bak_20260809                 (leader_name, phone, address)
--   账号注销(AccountDeregistrationServiceImpl.executeOne)不会也不应该去改这些备份表，
--   因此已注销用户的个人信息会一直残留在这里。备份对应的整改早已完成，没有代码读取这些表。
--
-- 范围:
--   只 DROP 上面 6 张表；不碰不含直接个人信息的财务/菜单/关系备份
--   (settlement_*_bak / merchant_payable_bak / coop_*_bak / ums_member_balance_detail_bak /
--    club_member_bak / sys_menu_bak / *_member_capability 等)，是否清理由业务另行决定。
--   DROP TABLE 会隐式提交，不能声明 runner: requires=transaction；IF EXISTS 保证可重复执行。
--
-- ⚠️ 生产落库前必须:
--   1. 走 chengyin-db-migrate 只读预检，并经用户确认（这是对生产库的 DDL）。
--   2. 如需保留，先按公司留存制度把这些表导出到受控离线介质，再执行本文件。
--   只写文件，开发阶段不连接、不写生产库。
-- ============================================================================

-- ======================== 人工落库前只读预检 ========================
-- SELECT table_name, table_rows
--   FROM information_schema.tables
--  WHERE table_schema = DATABASE()
--    AND table_name IN ('_bak_20260705_ums_member', '_bak_20260708_ums_member',
--                       '_bak_20260708_mms_merchant', '_bak_20260705_club_leader',
--                       '_bak_20260705_club', 'club_bak_20260809');
-- 同时确认没有视图/存储过程引用这些表：
-- SELECT table_name FROM information_schema.views
--  WHERE table_schema = DATABASE()
--    AND (view_definition LIKE '%_bak_20260705_%' OR view_definition LIKE '%_bak_20260708_%'
--         OR view_definition LIKE '%club_bak_20260809%');
-- 期望 0 行；有引用即 NO-GO。

DROP TABLE IF EXISTS `_bak_20260705_ums_member`;
DROP TABLE IF EXISTS `_bak_20260708_ums_member`;
DROP TABLE IF EXISTS `_bak_20260708_mms_merchant`;
DROP TABLE IF EXISTS `_bak_20260705_club_leader`;
DROP TABLE IF EXISTS `_bak_20260705_club`;
DROP TABLE IF EXISTS `club_bak_20260809`;

-- runner: assert=scalar-true
SELECT COUNT(*) = 0
  FROM information_schema.tables
 WHERE table_schema = DATABASE()
   AND table_name IN ('_bak_20260705_ums_member', '_bak_20260708_ums_member',
                      '_bak_20260708_mms_merchant', '_bak_20260705_club_leader',
                      '_bak_20260705_club', 'club_bak_20260809');
