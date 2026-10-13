-- P1-6 M块:商家违规倒扣 —— 注册后台菜单与权限(sys_menu)
--
-- 【类别】② 注册数据(要在库上跑)。不是 DDL,但不跑 = 页面对非超管不存在、接口 403
--   ——超管有 *:*:* 恒能点通,验收必须用非超管账号,拿超管点一遍「能用」是骗自己。
--
-- 【为什么发起/复核拆两个专属权限点,不共用一个 edit】
--   倒扣是资金核心操作(直接改批次金额或扣会员余额),且业务铁律要求「发起→复核确认」
--   两步、复核人≠发起人。若共用一个权限点,同一个账号既能发起又能复核 = 双人复核形同虚设。
--   这条已经过 escalation 拍板确认(2026-07-25),与 P1-6 P块冻结功能的权限设计同一原则。
--
-- 【幂等】按 perms 唯一性 NOT EXISTS 守卫,可重复执行。
--
-- 【落库时机】必须晚于本目录下 migration_merchant_clawback_20260725.sql(DDL 要先于 jar),
--   且必须晚于 jar + chengyinhub-ui 都上线之后,否则运营点到一个后端还不认/前端还没有的按钮。
--
-- 【可逆】
--   DELETE FROM sys_menu WHERE perms IN
--    ('business:merchantclawback:list','business:merchantclawback:apply','business:merchantclawback:confirm');
--
-- 【风险】低。只往 sys_menu 插 1 个 C 菜单 + 2 个 F 按钮,不动既有菜单、不动目录结构。
--
-- ⚠️ 新权限点默认不预置给任何角色:插入后没有任何角色天然持有,需要超管在
--   「系统管理→角色管理」里手动勾选授予。失败方向安全——没配 = 没人能发起/复核。


-- ======================= 第一步:只读预检 =======================
--
-- P1【必看】是否已注册过:
--   SELECT menu_id, menu_name, menu_type, perms FROM sys_menu
--    WHERE perms LIKE 'business:merchantclawback:%';
--   · 0 行 → 正常,全执行。
--   · 有行 → 已跑过,下面的 NOT EXISTS 会跳过,无害。
--
-- P2【父级解析】结算管理所在的父目录必须找得到(与 ③应付批次 挂同一层级,都是钱的事):
--   SELECT menu_id, menu_name, parent_id, component FROM sys_menu
--    WHERE component = 'business/payablebatch/index';
--   · 0 行 → 改用 SELECT parent_id FROM sys_menu WHERE component='business/settlement/index'
--     确认父目录,下面 @parent 解析不到会静默插 0 行(回执 ≠ 观测,A1 回读断言必跑)。


-- ======================= 第二步:注册 =======================

-- ① 父目录:优先挂在「③应付批次」同级(component 定位,不硬编码 menu_id)。
SET @parent := (SELECT parent_id FROM sys_menu WHERE component = 'business/payablebatch/index' LIMIT 1);

-- ② C 菜单
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT 'M块商家违规倒扣', @parent, 10, 'merchantclawback', 'business/merchantclawback/index', 1, 0, 'C', '0', '0', 'business:merchantclawback:list', 'money', 'admin', NOW(),
       '商家违规经运营核实投诉后,追回已打款的结算款。默认冲抵下一个未打款批次,商家无未来批次时兜底直接扣会员余额。发起-复核两步,复核人须≠发起人'
FROM dual
WHERE @parent IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:merchantclawback:list');

SET @menu := (SELECT menu_id FROM sys_menu WHERE perms = 'business:merchantclawback:list' LIMIT 1);

-- ③ F 按钮:发起(独立权限点)
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '发起倒扣', @menu, 1, '', '', 1, 0, 'F', '0', '0', 'business:merchantclawback:apply', '#', 'admin', NOW(),
       '发起倒扣申请,金额由运营人工核实投诉后填写,系统只自动判定结算路径,不计算金额'
FROM dual
WHERE @menu IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:merchantclawback:apply');

-- ④ F 按钮:复核确认/驳回(独立权限点,与发起权限分离 —— 双人复核的硬约束)
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '复核倒扣', @menu, 2, '', '', 1, 0, 'F', '0', '0', 'business:merchantclawback:confirm', '#', 'admin', NOW(),
       '复核通过并执行扣款/冲抵,或驳回。复核人必须≠发起人,由后端 MakerCheckerGuard 强制'
FROM dual
WHERE @menu IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:merchantclawback:confirm');


-- ======================= 回读断言(必跑,别只看 "Query OK") =======================

-- A1:必须看到 3 行(1 个 C + 2 个 F)。0 行 = @parent 解析不到 ⇒ 整份静默什么都没做。
SELECT menu_id, menu_name, parent_id, menu_type, perms FROM sys_menu
 WHERE perms LIKE 'business:merchantclawback:%'
 ORDER BY menu_type DESC;

-- A2:两个 F 按钮的 parent 必须是上面那个 C 菜单本身(不是父目录)。
SELECT f.menu_name AS button, f.parent_id, c.menu_name AS parent_menu, c.perms AS parent_perms
  FROM sys_menu f JOIN sys_menu c ON f.parent_id = c.menu_id
 WHERE f.perms IN ('business:merchantclawback:apply', 'business:merchantclawback:confirm');

-- A3【验收提醒,不是 SQL】:注册完必须用非超管账号,在角色管理里手动勾选这三个权限
--    并单独测试「未授权账号点发起/复核应 403/不可见」,再测试「授权后可用」;
--    且必须验证「发起人自己去复核应被拒」(双人复核负控)。
