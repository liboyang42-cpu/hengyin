-- 数据修复:会员 100096(智能科技体验馆)商家身份恢复
-- ★ 已于 2026-08-11 在生产库执行完毕,本文件为留痕 + 幂等重放安全。
--
-- 症状:该账号登录后落玩家首页、商家分流全失效、商家/玩家页面互踢白屏。
-- 根因(三处数据不一致,均为存量/手工数据,现行代码无写入口):
--   1) ums_member.role='club'(旧单值角色模型残留;capability 模型下 club_leader 应是能力位,
--      role 应为默认视角指针 'merchant',对照其余 9 个启用商家全为 'merchant')
--   2) ums_member.user_type=1(其余启用商家均为 2;/api/user/info 只在 userType=2 时挂商家实体)
--   3) mms_merchant(id=10).del_flag=1(被软删 → /api/user/info 判「无商家数据」硬回退 player,
--      与 /api/role/info 的 resolveRole 结果互相矛盾,前端两道闸互踢)
--   4) member_capability 缺 merchant 行(违反 migration_member_capability.sql 的
--      「从 mms_merchant status=1 回填」铁律)
-- club_leader 能力保留不动(能力模型:商家可兼主理人)。

UPDATE ums_member SET role='merchant', user_type=2 WHERE id=100096 AND role='club';

UPDATE mms_merchant SET del_flag=0 WHERE id=10 AND member_id=100096 AND del_flag=1;

INSERT IGNORE INTO member_capability (member_id, capability, status, granted_at, granted_by, create_time)
VALUES (100096, 'merchant', 1, NOW(), 0, NOW());

-- 验证(执行后应各返回 1 行且值如注释):
-- SELECT user_type, role FROM ums_member WHERE id=100096;          -- 2, merchant
-- SELECT del_flag FROM mms_merchant WHERE member_id=100096;        -- 0
-- SELECT capability FROM member_capability WHERE member_id=100096; -- club_leader + merchant
