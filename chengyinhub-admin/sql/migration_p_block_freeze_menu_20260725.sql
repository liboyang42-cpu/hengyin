-- P1-6 P块:冻结功能权限点注册(sys_menu)
--
-- 【类别】② 注册数据(要在库上跑)。不是 DDL,但不跑 = 冻结按钮对所有非超管账号都是 403
--   ——超管有 *:*:* 恒能点通,验收必须用非超管账号,拿超管点一遍「能用」是骗自己。
--
-- 【为什么两个专属权限点,不复用已有的 :manage / :edit】
--   冻结是比日常处理更敏感的动作(暂停整条自动化/人工流程,一旦滥用可能被用来
--   长期卡住某笔资金的正常处理)。给它单独的权限点,可以脱离"日常提现处理"
--   "日常批次打款"权限单独收放——这条已经过 escalation 拍板确认(2026-07-25)。
--
-- 【幂等】按 perms 唯一性 NOT EXISTS 守卫,可重复执行。
--
-- 【落库时机】必须晚于 migration_p_block_freeze_20260725.sql(DDL 要先于 jar,
--   菜单要晚于 jar+chengyinhub-ui 都上线,否则运营点到后端还不认/前端还没有的按钮)。
--
-- 【可逆】
--   DELETE FROM sys_menu WHERE perms IN ('business:cashwithdraw:freeze','business:payablebatch:freeze');
--
-- 【风险】低。只往 sys_menu 插 2 个 F 按钮,不动既有菜单、不动目录结构。
--
-- ⚠️ 新权限点默认不预置给任何角色:插入后没有任何角色天然持有,需要超管在
--   「系统管理→角色管理」里手动勾选授予。失败方向安全——没配 = 没人能冻结。


-- ======================= 第一步:只读预检 =======================
--
-- P1【必看】是否已注册过:
--   SELECT menu_id, menu_name, menu_type, perms FROM sys_menu WHERE perms IN
--    ('business:cashwithdraw:freeze','business:payablebatch:freeze');
--   · 0 行 → 正常,全执行。
--   · 有行 → 已跑过,下面的 NOT EXISTS 会跳过,无害。
--
-- P2【父级解析】两个父菜单必须找得到:
--   SELECT menu_id, menu_name, perms FROM sys_menu WHERE perms IN
--    ('business:cashwithdraw:list','business:payablebatch:list');
--   · 应各得 1 行。0 行 → **停**,说明对应 C 菜单还没注册,下面 @cw_menu/@pb_menu
--     会解析成 NULL,插入条件里的 IS NOT NULL 会让本份静默插 0 行(回执 ≠ 观测)。


-- ======================= 第二步:注册 =======================

-- ① 提现冻结:挂在「提现出金管理」(business:cashwithdraw:list)之下。
SET @cw_menu := (SELECT menu_id FROM sys_menu WHERE perms = 'business:cashwithdraw:list' LIMIT 1);

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '提现冻结', @cw_menu, 3, '', '', 1, 0, 'F', '0', '0', 'business:cashwithdraw:freeze', '#', 'admin', NOW(),
       '冻结/解冻某笔提现单——暂停该单的定时查询与人工补单处理,不移动任何资金。独立权限点,不与日常提现处理捆绑,默认不预置给任何角色'
FROM DUAL
WHERE @cw_menu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:cashwithdraw:freeze');

-- ② 批次冻结:挂在「③应付批次」(business:payablebatch:list)之下。
SET @pb_menu := (SELECT menu_id FROM sys_menu WHERE perms = 'business:payablebatch:list' LIMIT 1);

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '批次冻结', @pb_menu, 2, '', '', 1, 0, 'F', '0', '0', 'business:payablebatch:freeze', '#', 'admin', NOW(),
       '冻结/解冻某个应付批次——暂停该批次的对账确认与记录打款,不移动任何资金。独立权限点,不与日常批次操作捆绑,默认不预置给任何角色'
FROM DUAL
WHERE @pb_menu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:payablebatch:freeze');


-- ======================= 回读断言(必跑,别只看 "Query OK") =======================

-- A1:必须看到 2 行。0 行 = 父菜单解析不到 ⇒ 整份静默什么都没做。
SELECT menu_id, menu_name, parent_id, menu_type, perms FROM sys_menu
 WHERE perms IN ('business:cashwithdraw:freeze', 'business:payablebatch:freeze')
 ORDER BY perms;

-- A2【验收提醒,不是 SQL】:注册完必须用非超管账号,在角色管理里手动勾选这两个权限
--    并单独测试"未授权账号点冻结按钮应 403/不可见",再测试"授权后可用"。
