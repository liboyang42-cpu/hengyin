-- ============================================================
-- 城瘾 身份诊断 + 单账号清理  (2026-07-05)
-- 目的:严格分离(1账户=1身份)。找出"同时是商户 + 俱乐部主理人"的账号,
--       并把用户本人账号清成"只保留商户、去掉俱乐部"。
-- 库:chengyinhubdb
-- 能力常量:CAP_MERCHANT='merchant'  CAP_CLUB_LEADER='club_leader'
-- ============================================================


-- ╔══════════════════════════════════════════════════════════╗
-- ║ 第一部分:诊断(只读,不改任何数据)                         ║
-- ╚══════════════════════════════════════════════════════════╝

-- 【1】双重身分账号总览:既有通过的商户档案(status=1)又有通过的主理人档案(status=1)
--     在结果里找到"你自己那一行",记下 member_id 用于第二部分清理。
SELECT
    um.id                                                                          AS member_id,
    um.nick_name                                                                   AS 昵称,
    um.role                                                                         AS role列,
    um.user_type                                                                    AS user_type,
    (SELECT COUNT(*) FROM club c
       WHERE c.member_id = um.id AND c.status = 1
         AND (c.del_flag = 0 OR c.del_flag IS NULL))                               AS 在营俱乐部数,
    (SELECT GROUP_CONCAT(mc.capability)
       FROM member_capability mc
       WHERE mc.member_id = um.id AND mc.status = 1)                               AS 能力行
FROM ums_member um
WHERE EXISTS (SELECT 1 FROM mms_merchant mm WHERE mm.member_id = um.id AND mm.status = 1)
  AND EXISTS (SELECT 1 FROM club_leader  cl WHERE cl.member_id = um.id AND cl.status = 1);

-- 【2】member_capability 里同时带两种能力的账号(即使某侧审核档案缺失也能抓到残留)
SELECT member_id,
       GROUP_CONCAT(capability)      AS caps,
       COUNT(DISTINCT capability)    AS n
FROM member_capability
WHERE status = 1 AND capability IN ('merchant','club_leader')
GROUP BY member_id
HAVING COUNT(DISTINCT capability) >= 2;

-- 【3】(可选) 用昵称/手机定位自己的 member_id —— 把 关键字 换成你的昵称片段
-- SELECT id, nick_name, phone, role, user_type FROM ums_member WHERE nick_name LIKE '%关键字%';


-- ╔══════════════════════════════════════════════════════════╗
-- ║ 第二部分:清理你本人账号 —— 保留商户,去掉俱乐部            ║
-- ║ ★先跑备份(DDL会隐式提交,故放事务外);再跑事务;核对后 COMMIT ║
-- ╚══════════════════════════════════════════════════════════╝

-- ↓↓↓ 改成你的 member_id(从【1】里找到自己那行)↓↓↓
SET @MID = 0;

-- 备份命中行(可回滚参考;DDL 先跑)
CREATE TABLE IF NOT EXISTS _bak_20260705_member      AS SELECT * FROM ums_member       WHERE id = @MID;
CREATE TABLE IF NOT EXISTS _bak_20260705_club_leader AS SELECT * FROM club_leader       WHERE member_id = @MID;
CREATE TABLE IF NOT EXISTS _bak_20260705_club        AS SELECT * FROM club              WHERE member_id = @MID;
CREATE TABLE IF NOT EXISTS _bak_20260705_capability  AS SELECT * FROM member_capability WHERE member_id = @MID;

START TRANSACTION;

-- 1) 收回 club_leader 能力行(hasCapability(CAP_CLUB_LEADER) 将变 false)
DELETE FROM member_capability WHERE member_id = @MID AND capability = 'club_leader';

-- 2) 主理人档案软删 + 置驳回态(ApiRoleController.isClubLeader 需 status=1 才 true → 变 false)
UPDATE club_leader SET del_flag = 1, status = 2 WHERE member_id = @MID;

-- 3) 名下俱乐部软删下架(/api/club/my 不再返回,首页不再显示"拥有俱乐部")
UPDATE club SET del_flag = 1, status = 2 WHERE member_id = @MID;

-- 4) 默认视角 role 归位为 merchant(首页据 role 分流 → 商户版工作台)
UPDATE ums_member SET role = 'merchant', user_type = 2 WHERE id = @MID;

-- ── 核对(应:role=merchant / 无 club_leader 能力行 / club 全 del_flag=1) ──
SELECT id, role, user_type FROM ums_member WHERE id = @MID;
SELECT capability, status FROM member_capability WHERE member_id = @MID;
SELECT id, status, del_flag FROM club WHERE member_id = @MID;

-- 确认无误:  COMMIT;
-- 想放弃  :  ROLLBACK;
COMMIT;
