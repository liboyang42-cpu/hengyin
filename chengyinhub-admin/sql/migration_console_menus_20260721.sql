-- 控制台补全批次:注册后台菜单与权限(sys_menu)—— POI 治理 / 核销证据 / 带队场次
--
-- 【类别】③ 注册数据(要在库上跑)。不是 DDL,但**不跑 = 三个页面对非超管根本不存在**
--   —— 菜单不出现、接口 403。超管有 *:*:* 恒能看见,**验收必须用非超管账号**。
--
-- 【背景】《后台主题运营控制台_领域设计与工作台方案_20260718》§4.4/§4.5/§4.6 +
--   《后台控制台补全_资质履约率_POI治理_核销穿透_场次视图_施工设计_20260721》:
--   roam_poi / 核销证据(scan+团核销)/ 带队场次此前后台零入口。
--
-- 【落库时机】**必须在 jar + chengyinhub-ui 都上线之后**(纯菜单注册,与 DDL「先于 jar」相反)。
--   跑本份前扫一眼 ui 部署:gh run list --workflow=deploy-ui.yml
--
-- 【幂等】按 perms 去重(NOT EXISTS),照抄 migration_revshare_payable_menu_20260717.sql 范式。
--
-- 【可逆】
--   DELETE FROM sys_menu WHERE perms IN ('business:roamPoi:list','business:roamPoi:edit',
--     'business:verifyEvidence:list','business:clubRun:list');
--
-- 【风险】低。只插 3 个 C 菜单 + 1 个 F 按钮,不动既有菜单与目录结构。
--   ⚠️ 带队场次/团核销今天可能零行,页面偏空是对的,不是坏了。


-- ======================= 第一步:只读预检 =======================
--
-- P1【必看】是否已注册过:
--   SELECT menu_id, menu_name, menu_type, perms FROM sys_menu
--    WHERE perms IN ('business:roamPoi:list','business:roamPoi:edit',
--                    'business:verifyEvidence:list','business:clubRun:list');
--   · 0 行 → 正常,全执行。 · 有行 → 已跑过,NOT EXISTS 会跳过,无害。
--
-- P2【父级解析】两个九域目录必须都找得到:
--   SELECT menu_id, menu_name, path FROM sys_menu
--    WHERE parent_id = 0 AND path IN ('clubs-roam','audit-center');
--   · 少任何一行 → **停**:九域导航(migration_web_admin_nine_domains_20260719.sql)未落库,
--     下面对应的 @parent 为 NULL ⇒ 该目录下的菜单静默插 0 行,你会以为跑成功了。
--     先落九域那份;A1 回读断言必跑。


-- ======================= 第二步:注册 =======================

SET @clubsRoam := (SELECT menu_id FROM sys_menu WHERE path = 'clubs-roam' AND parent_id = 0 LIMIT 1);
SET @auditCenter := (SELECT menu_id FROM sys_menu WHERE path = 'audit-center' AND parent_id = 0 LIMIT 1);

-- ① POI 治理(俱乐部与漫游)
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT 'POI治理', @clubsRoam, 7, 'roampoi', 'business/roampoi/index', 1, 0, 'C', '0', '0', 'business:roamPoi:list', 'guide', 'admin', NOW(),
       '漫游据点全量列表(含下架)+ 上/下架治理杆。商家自助写入且 status=1 即玩家可见,此前劣质据点只能写 SQL 下架'
FROM dual
WHERE @clubsRoam IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:roamPoi:list');

SET @poiMenu := (SELECT menu_id FROM sys_menu WHERE perms = 'business:roamPoi:list' LIMIT 1);

-- ② POI 上/下架按钮(共用一个 edit 权限;字符串须与 RoamPoiAdminController 的 @PreAuthorize 逐字一致)
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT 'POI上下架', @poiMenu, 1, '', '', 1, 0, 'F', '0', '0', 'business:roamPoi:edit', '#', 'admin', NOW(),
       '上架=玩家立即可见 / 下架=立即不可见(不删行,发现流水引用 poi_id)'
FROM dual
WHERE @poiMenu IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:roamPoi:edit');

-- ③ 核销证据(审核与风控)—— 全只读,无 F 按钮
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '核销证据', @auditCenter, 8, 'verifyevidence', 'business/verifyevidence/index', 1, 0, 'C', '0', '0', 'business:verifyEvidence:list', 'eye-open', 'admin', NOW(),
       '扫码流水(数人头唯一真源)+ 团核销台账,只读穿透;从主题/商家/俱乐部带参跳入同一份证据(§4.5)'
FROM dual
WHERE @auditCenter IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:verifyEvidence:list');

-- ④ 带队场次(俱乐部与漫游)—— 纯只读调证(2026-07-21 拍板),无 F 按钮
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '带队场次', @clubsRoam, 8, 'clubrun', 'business/clubrun/index', 1, 0, 'C', '0', '0', 'business:clubRun:list', 'peoples', 'admin', NOW(),
       '跨主题带队场次(club_lead_state×activity):集合时间地点/责任领队/名册no-show/流程经过的商家(§4.6)'
FROM dual
WHERE @clubsRoam IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:clubRun:list');


-- ======================= 回读断言(必跑,别只看 "Query OK") =======================

-- A1:**必须看到 4 行**(3 个 C + 1 个 F)。少行 = 对应 @parent 解析不到 ⇒ 那部分静默没做。
SELECT menu_id, menu_name, parent_id, menu_type, perms FROM sys_menu
 WHERE perms IN ('business:roamPoi:list','business:roamPoi:edit',
                 'business:verifyEvidence:list','business:clubRun:list')
 ORDER BY menu_type DESC, perms;

-- A2:F 按钮的 parent 必须是「POI治理」C 菜单本身(不是父目录),挂错层=权限树勾不到。
SELECT f.menu_name AS button, f.parent_id, c.menu_name AS parent_menu, c.perms AS parent_perms
  FROM sys_menu f JOIN sys_menu c ON f.parent_id = c.menu_id
 WHERE f.perms = 'business:roamPoi:edit';

-- A3【验收提醒,不是 SQL】:非超管账号勾权限再点一遍;三个页面里带队场次/团核销可能为空,是对的。
