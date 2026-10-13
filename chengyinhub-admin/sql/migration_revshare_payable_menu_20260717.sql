-- ③ 计酬档应付月结批次:注册后台菜单与权限(sys_menu)
--
-- 【类别】③ 注册数据(要在库上跑)。不是 DDL,但**不跑 = 这个页面对非超管根本不存在**
--   —— 菜单不出现、接口 403。超管有 *:*:* 恒能看见,所以**验收必须用非超管账号**,
--   拿超管点一遍「能用」是骗自己(本项目栽过)。
--
-- 【⚠️ 与 migration_revshare_payable_20260717.sql 的落库时机相反,别一起跑】
--   · 那份是 DDL(建 merchant_payable / merchant_payable_batch)⇒ **必须在 jar 之前**,
--     否则新代码查询报 1146。
--   · 本份是菜单 ⇒ **必须在 jar + chengyinhub-ui 都上线之后**,否则运营点到一个
--     后端还不认、前端还没有的按钮。
--   ⚠️ chengyinhub-ui 是自动部署的(.github/workflows/deploy-ui.yml,与 jar 的 deploy.yml 平行)。
--      但 workflow 存在 ≠ 它跑通了(2026-07-16~17 就因生产机没装 rsync 连红两天)。
--      跑本份前扫一眼:gh run list --workflow=deploy-ui.yml
--
-- 【幂等】按 perms 去重(照抄 migration_topic_copy_perm_20260717.sql 的范式)。
--
-- 【可逆】
--   DELETE FROM sys_menu WHERE perms IN ('business:payablebatch:list','business:payablebatch:edit');
--
-- 【风险】低。只往 sys_menu 插 1 个 C 菜单 + 2 个 F 按钮,不动既有菜单、不动目录结构。
--   ⚠️ 今天 ③ 一张票都没卖过 ⇒ 这个页面会是空的,那是对的,不是坏了。


-- ======================= 第一步:只读预检 =======================
--
-- P1【必看】是否已注册过:
--   SELECT menu_id, menu_name, menu_type, perms FROM sys_menu
--    WHERE perms LIKE 'business:payablebatch:%';
--   · 0 行 → 正常,全执行。
--   · 有行 → 已跑过,下面的 NOT EXISTS 会跳过,无害。
--
-- P2【父级解析】结算管理所在的父目录必须找得到:
--   SELECT menu_id, menu_name, parent_id, component FROM sys_menu
--    WHERE component = 'business/settlement/index';
--   · 0 行 → **停**。说明结算菜单不叫这个 component,下面的 @parent 会解析成 NULL,
--     整份静默插 0 行(NOT EXISTS + @parent IS NOT NULL 双条件)⇒ 你会以为跑成功了,
--     其实什么都没发生。这正是「回执 ≠ 观测」——所以下面 A1 回读断言必跑。


-- ======================= 第二步:注册 =======================

-- ① 父目录动态解析(勿硬编码 menu_id)。
--    按 component 找最稳:菜单名可能被运营改过,component 是代码里的路径,改不动。
--    把「应付批次」挂在结算管理的同级 —— 它俩都是钱的事,运营找得到。
SET @parent := (SELECT parent_id FROM sys_menu WHERE component = 'business/settlement/index' LIMIT 1);

-- ② C 菜单
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '③应付批次', @parent, 9, 'payablebatch', 'business/payablebatch/index', 1, 0, 'C', '0', '0', 'business:payablebatch:list', 'money', 'admin', NOW(),
       '③计酬档平台应付月结批次(自然月,次月结上月)。平台按核销给商家付人头费=采购成本,走对公打款+商家开票,不是分账'
FROM dual
WHERE @parent IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:payablebatch:list');

SET @menu := (SELECT menu_id FROM sys_menu WHERE perms = 'business:payablebatch:list' LIMIT 1);

-- ③ F 按钮:生成批次 / 对账确认 / 记录打款 / 记录开票 / 记录红冲 —— 共用一个 edit 权限。
--    ⚠️ 不给每个动作单独立权限点:多一个没人配的权限点等于那个按钮没人能用
--    (与 P0-35 内容槽改判「权限借用 topic:edit」同一条口径)。
--    ⚠️ 字符串必须与 MerchantPayableBatchController 的 @PreAuthorize 逐字一致,对不上 = 点了 403。
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '应付批次操作', @menu, 1, '', '', 1, 0, 'F', '0', '0', 'business:payablebatch:edit', '#', 'admin', NOW(),
       '生成月结批次/对账确认/记录打款/记录开票/记录红冲。⚠️记的都是事实,不是转账动作——打款能力在系统1(PR#78已收口)'
FROM dual
WHERE @menu IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:payablebatch:edit');


-- ======================= 回读断言(必跑,别只看 "Query OK") =======================

-- A1:**必须看到 2 行**(1 个 C + 1 个 F)。0 行 = @parent 解析不到 ⇒ 整份静默什么都没做。
SELECT menu_id, menu_name, parent_id, menu_type, perms FROM sys_menu
 WHERE perms LIKE 'business:payablebatch:%'
 ORDER BY menu_type DESC;

-- A2:F 按钮的 parent 必须是上面那个 C 菜单本身(不是父目录)。
--     挂错层 = 权限树里勾不到它 = 非超管永远拿不到这个权限。
SELECT f.menu_name AS button, f.parent_id, c.menu_name AS parent_menu, c.perms AS parent_perms
  FROM sys_menu f JOIN sys_menu c ON f.parent_id = c.menu_id
 WHERE f.perms = 'business:payablebatch:edit';

-- A3【验收提醒,不是 SQL】:注册完必须**用非超管账号**去角色里勾上这两个权限、再登进去点一遍。
--    超管的 *:*:* 恒能看见一切 ⇒ 用超管验收等于没验收。
