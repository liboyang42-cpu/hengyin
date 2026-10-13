-- =====================================================================================
-- T1 · 四页授权勘察落地：后台业务页面角色授权 + freeze 菜单注册
-- 执行人 = 主控（当前仓库仅负责准备文件，不连生产库）
-- 日期   = 2026-07-26
--
-- 【做什么】
-- 1. 给 5 个「功能建了、代码在跑、但 sys_role_menu = 0 行 ⇒ 非超管看不到」的业务页面
--    补上 RBAC 角色授权（INSERT sys_role_menu），让 cy_operator / cy_auditor /
--    cy_support / cy_finance 能看见并操作对应页面。
-- 2. 补两条从没落库的 freeze 菜单注册（business:cashwithdraw:freeze / payablebatch:freeze），
--    只建 sys_menu 行、不预置给任何角色（满足 P1-6 "独立权限点、先落位后手动授予"）。
--
-- 【为什么不是"新增功能"而是"补授权"】
-- 这 10 条菜单行早在 G0 补缺批次（migration_g0_admin_menu_gap_backfill_20260722）和
-- 控制台补全批次（migration_console_menus_20260721 + chapter_offer_menu）里就已注册进
-- sys_menu，只是 sys_role_menu 里 0 行。页面代码、Controller、Vue 路由全在跑，
-- 仅超管凭 *:*:* 能看到，其他角色菜单树里连目录都消失了（父目录整棵从侧栏消失）。
--
-- 【类别】③ 注册数据。要在库上跑，不跑 = 授权不生效 = 跟没做一样。
-- 【落库时机】必须在合并前由主控手动落库。纯 sys_role_menu INSERT + sys_menu INSERT，
--   不触发部署（pom.xml 和 chengyinhub-admin/ 改动文件分类中纯 sql/** 自动跳过部署）。
--
-- 【铁律】本文件只有 INSERT + SELECT，绝不含 DELETE / UPDATE / TRUNCATE / DDL。
--   授权只增不删。幂等 NOT EXISTS 守卫，可重复执行。
--
-- 【回滚】见末尾注释。落库前先拍快照 sys_role_menu_bak_t1_20260726。
--
-- ======================= 核心事实（请勿跳过） =======================
--
-- 角色映射（来自 migration_web_admin_roles_20260719.sql）:
--   100 = cy_operator  · 日常运营（内容与供给，不含资金终审）
--   101 = cy_auditor   · 审核风控（统一审核台，不含资金与业务事实写入）
--   102 = cy_support   · 客服售后（用户调证与售后发起，只读业务对象）
--   103 = cy_finance   · 财务结算（资金查询、复核与结算，不含内容供给编辑）
--
-- 菜单对应（经 github/master 代码核实，不是凭记忆写的）:
--   2219 = 微信提现回补        C  business:cashwithdraw:list      父=2214 资金与交易
--   2220 = 提现详情            F  business:cashwithdraw:query      父=2219
--   2221 = 提现人工处理        F  business:cashwithdraw:manage     父=2219
--   2222 = 三档承接审核        C  business:chapterOffer:list       父=2212 商家与合作
--   2223 = 三档承接操作        F  business:chapterOffer:edit       父=2222
--   2224 = POI治理             C  business:roamPoi:list            父=2240 漫游
--   2225 = POI上下架           F  business:roamPoi:edit            父=2224
--   2226 = 核销证据            C  business:verifyEvidence:list     父=2210 审核与风控  (无F子)
--   2227 = 带队场次            C  business:clubRun:list            父=2211 俱乐部      (无F子)
--   2240 = 漫游                M  (一级目录,parent_id=0)           —               —

-- =====================================================================================
-- 第〇节：只读预检（执行前必须跑，预期不符就停，别当注释扫过去了）
-- =====================================================================================

-- P1【菜单存在性】10 条菜单 menu_id × menu_name × perms 的对应关系必须与上面表格逐字一致。
--   期望:正好 10 行，每行的 perms 与上面表吻合。菜单被人改过/重建过就是 menu_id 漂移，
--   下面的硬编码 INSERT 会挂错 menu_id ⇒ 给错授权。0 行 = 菜单根本没注册，也得停。
SELECT menu_id, menu_name, menu_type, perms FROM sys_menu
 WHERE menu_id IN (2219,2220,2221,2222,2223,2224,2225,2226,2227,2240)
 ORDER BY menu_id;
--   检查清单(逐行对):
--   2219=微信提现回补/C/business:cashwithdraw:list  2220=提现详情/F/business:cashwithdraw:query
--   2221=提现人工处理/F/business:cashwithdraw:manage 2222=三档承接审核/C/business:chapterOffer:list
--   2223=三档承接操作/F/business:chapterOffer:edit   2224=POI治理/C/business:roamPoi:list
--   2225=POI上下架/F/business:roamPoi:edit          2226=核销证据/C/business:verifyEvidence:list
--   2227=带队场次/C/business:clubRun:list           2240=漫游/M

-- P2【授权空白确认】这 10 条在 sys_role_menu 里当前必须是 0 行。
--   期望:Empty set (0 rows)。不是 0 行 ⇒ 有人已经补过授权，停下来搞清楚原因再决定。
SELECT srm.role_id, srm.menu_id, sm.menu_name, sm.perms
  FROM sys_role_menu srm
  JOIN sys_menu sm ON sm.menu_id = srm.menu_id
 WHERE srm.menu_id IN (2219,2220,2221,2222,2223,2224,2225,2226,2227,2240)
 ORDER BY srm.menu_id, srm.role_id;
--   期望:0 行。非空 = 已有角色握着这些菜单 ⇒ 停下来，本批 INSERT 的 NOT EXISTS
--   会跳过已有行，不会重复插入，但"已有人补过"这件事需要人为确认。

-- P3【freeze 菜单不存在】两条 freeze perms 在 sys_menu 里必须是 0 行。
--   期望:0 行。已有行 ⇒ 已跑过，NOT EXISTS 会跳过，无害——但说明你没按这条做。
SELECT menu_id, menu_name, menu_type, perms FROM sys_menu
 WHERE perms IN ('business:cashwithdraw:freeze', 'business:payablebatch:freeze');
--   期望:0 行。

-- P4【freeze 父菜单存在性】
--   期望:各 1 行。0 行 ⇒ 还没注册 C 菜单，下面的 freeze INSERT 会静默插 0 行（回执≠观测）。
SELECT menu_id, menu_name, perms FROM sys_menu
 WHERE perms IN ('business:cashwithdraw:list', 'business:payablebatch:list');

-- P5【全库悬空授权为 0】sys_role_menu 里没有指向不存在 menu_id 的孤儿行(安全基线)。
SELECT COUNT(*) AS orphan_role_menu_count FROM sys_role_menu srm
  LEFT JOIN sys_menu sm ON sm.menu_id = srm.menu_id
 WHERE sm.menu_id IS NULL;
--   期望:0。

-- P6【全库孤儿菜单为 0】没有 parent_id 指向不存在 menu_id 的菜单(安全基线)。
SELECT COUNT(*) AS orphan_menu_count FROM sys_menu cur
  LEFT JOIN sys_menu par ON par.menu_id = cur.parent_id
 WHERE cur.parent_id > 0 AND par.menu_id IS NULL;
--   期望:0。


-- =====================================================================================
-- 第一节：角色授权 INSERT（全部幂等 NOT EXISTS 守卫，只增不减）
-- =====================================================================================

-- --- [2219] 微信提现回补 C, perms = business:cashwithdraw:list ---
-- 授权:100(运营)·102(客服)·103(财务)。不给 101(审核):提现回补是财务+运营操作面，非审核。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 100, 2219 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2219);
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 102, 2219 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 102 AND menu_id = 2219);
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 103, 2219 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 103 AND menu_id = 2219);

-- --- [2220] 提现详情 F, perms = business:cashwithdraw:query ---
-- 授权:与父菜单 2219 一致(100·102·103)。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 100, 2220 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2220);
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 102, 2220 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 102 AND menu_id = 2220);
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 103, 2220 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 103 AND menu_id = 2220);

-- --- [2221] 提现人工处理 F, perms = business:cashwithdraw:manage ---
-- 授权:仅 103(财务)。碰钱操作(微信提现人工回补=平台向用户付款)，只给财务。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 103, 2221 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 103 AND menu_id = 2221);

-- --- [2222] 三档承接审核 C, perms = business:chapterOffer:list ---
-- 授权:100(运营)·101(审核)·102(客服)。不给 103(财务):供给编排属性，非财务域。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 100, 2222 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2222);
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 101, 2222 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2222);
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 102, 2222 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 102 AND menu_id = 2222);

-- --- [2223] 三档承接操作 F, perms = business:chapterOffer:edit ---
-- ★ 碰钱重权。仅 100(运营)。adjustTerms 改 perHeadFee(人头费=平台按核销付商家真金白银)，
--   经二清闸+锁价闸+并发消耗守卫三重防护。审核/客服/财务一律不给——给了就是越权写入资金链路。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 100, 2223 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2223);

-- --- [2224] POI治理 C, perms = business:roamPoi:list ---
-- 授权:100(运营)·102(客服)。内容治理域:运营看全量POI、客服溯源玩家反馈。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 100, 2224 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2224);
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 102, 2224 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 102 AND menu_id = 2224);

-- --- [2225] POI上下架 F, perms = business:roamPoi:edit ---
-- 仅 100(运营)。上/下架改变玩家可见性，是运营内容管控动作，非审核/客服/财务职责。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 100, 2225 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2225);

-- --- [2226] 核销证据 C, perms = business:verifyEvidence:list (全只读，无 F 子) ---
-- 授权:100(运营)·101(审核)·102(客服)·103(财务)。核销证据是调证工具，全只读零写入风险，
--   四角色各有调证需求:运营看履约、审核看模式、客服回用户、财务对账。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 100, 2226 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2226);
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 101, 2226 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 101 AND menu_id = 2226);
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 102, 2226 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 102 AND menu_id = 2226);
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 103, 2226 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 103 AND menu_id = 2226);

-- --- [2227] 带队场次 C, perms = business:clubRun:list (全只读，无 F 子) ---
-- 授权:100(运营)·102(客服)。运营监控场次履约、客服处理 no-show 纠纷。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 100, 2227 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2227);
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 102, 2227 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 102 AND menu_id = 2227);

-- --- [2240] 漫游 M 一级目录(parent_id=0) ---
-- ★ 阻断项。不补它，2224(POI治理)即使补了授权也会因为父目录不在授权树里
--   而从侧栏整棵消失——菜单树 = 从 parent_id=0 起在已授权集合内递归构建。
-- 授权:100(运营)·102(客服)。漫游域下目前只有 2224(POI治理，运营+客服)，
--   所以只给这两个角色。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 100, 2240 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 100 AND menu_id = 2240);
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 102, 2240 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 102 AND menu_id = 2240);


-- =====================================================================================
-- 第二节：freeze 菜单注册（照抄 migration_p_block_freeze_menu_20260725.sql，只建行不授权）
-- =====================================================================================

-- ① 提现冻结：挂在「微信提现回补」(business:cashwithdraw:list)之下。
SET @cw_menu := (SELECT menu_id FROM sys_menu WHERE perms = 'business:cashwithdraw:list' LIMIT 1);

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '提现冻结', @cw_menu, 3, '', '', 1, 0, 'F', '0', '0', 'business:cashwithdraw:freeze', '#', 'admin', NOW(),
       '冻结/解冻某笔提现单——暂停该单的定时查询与人工补单处理,不移动任何资金。独立权限点,不与日常提现处理捆绑,默认不预置给任何角色'
FROM DUAL
WHERE @cw_menu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:cashwithdraw:freeze');

-- ② 批次冻结：挂在「③应付批次」(business:payablebatch:list)之下。
SET @pb_menu := (SELECT menu_id FROM sys_menu WHERE perms = 'business:payablebatch:list' LIMIT 1);

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '批次冻结', @pb_menu, 2, '', '', 1, 0, 'F', '0', '0', 'business:payablebatch:freeze', '#', 'admin', NOW(),
       '冻结/解冻某个应付批次——暂停该批次的对账确认与记录打款,不移动任何资金。独立权限点,不与日常批次操作捆绑,默认不预置给任何角色'
FROM DUAL
WHERE @pb_menu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:payablebatch:freeze');


-- =====================================================================================
-- 第三节：回读断言（★执行后必须跑，每条都能变红才有意义）
-- =====================================================================================

-- A1【2219 授权集合】期望 role_id 集合 = {100,102,103}，共 3 行。
SELECT role_id, menu_id FROM sys_role_menu WHERE menu_id = 2219 ORDER BY role_id;
--   期望:(100,2219),(102,2219),(103,2219)。缺/多都是异常。

-- A2【2220 授权集合】期望 {100,102,103}，3 行。
SELECT role_id, menu_id FROM sys_role_menu WHERE menu_id = 2220 ORDER BY role_id;
--   期望:(100,2220),(102,2220),(103,2220)。

-- A3【2221 授权集合】期望 {103}，1 行。这是碰钱操作，严禁多出任何角色。
SELECT role_id, menu_id FROM sys_role_menu WHERE menu_id = 2221 ORDER BY role_id;
--   期望:(103,2221)。多一行就是资金权限泄露，生产事故。

-- A4【2222 授权集合】期望 {100,101,102}，3 行。
SELECT role_id, menu_id FROM sys_role_menu WHERE menu_id = 2222 ORDER BY role_id;
--   期望:(100,2222),(101,2222),(102,2222)。

-- A5【2223 授权集合】期望 {100}，1 行。碰钱重权，严禁多出。
SELECT role_id, menu_id FROM sys_role_menu WHERE menu_id = 2223 ORDER BY role_id;
--   期望:(100,2223)。同上 A3——多一行就是越权写入人头费。

-- A6【2224 授权集合】期望 {100,102}，2 行。
SELECT role_id, menu_id FROM sys_role_menu WHERE menu_id = 2224 ORDER BY role_id;
--   期望:(100,2224),(102,2224)。

-- A7【2225 授权集合】期望 {100}，1 行。
SELECT role_id, menu_id FROM sys_role_menu WHERE menu_id = 2225 ORDER BY role_id;
--   期望:(100,2225)。

-- A8【2226 授权集合】期望 {100,101,102,103}，4 行。全只读，四角色各有调证需求。
SELECT role_id, menu_id FROM sys_role_menu WHERE menu_id = 2226 ORDER BY role_id;
--   期望:(100,2226),(101,2226),(102,2226),(103,2226)。

-- A9【2227 授权集合】期望 {100,102}，2 行。
SELECT role_id, menu_id FROM sys_role_menu WHERE menu_id = 2227 ORDER BY role_id;
--   期望:(100,2227),(102,2227)。

-- A10【2240 授权集合】期望 {100,102}，2 行。
--     这是阻断级断言——如果少了，2224 从侧栏整棵消失、所有非超管用户看不到漫游域。
SELECT role_id, menu_id FROM sys_role_menu WHERE menu_id = 2240 ORDER BY role_id;
--   期望:(100,2240),(102,2240)。

-- A11【freeze 菜单存在】必须看到 2 行。0 行 = 父菜单没解析到 ⇒ 静默插 0 行(回执≠观测)。
SELECT menu_id, menu_name, parent_id, menu_type, perms FROM sys_menu
 WHERE perms IN ('business:cashwithdraw:freeze', 'business:payablebatch:freeze')
 ORDER BY perms;
--   期望:2 行，分别是提现冻结(F)和批次冻结(F)。

-- A12【freeze 菜单仍无角色授权】期望 0 行。freeze 菜单建了但不应预置给任何角色。
SELECT srm.role_id, srm.menu_id, sm.perms
  FROM sys_role_menu srm
  JOIN sys_menu sm ON sm.menu_id = srm.menu_id
 WHERE sm.perms IN ('business:cashwithdraw:freeze', 'business:payablebatch:freeze');
--   期望:0 行。

-- A13【全库悬空授权基线不变】期望仍为 0。
SELECT COUNT(*) AS orphan_role_menu_count FROM sys_role_menu srm
  LEFT JOIN sys_menu sm ON sm.menu_id = srm.menu_id
 WHERE sm.menu_id IS NULL;
--   期望:0。

-- A14【全库孤儿菜单基线不变】期望仍为 0。
SELECT COUNT(*) AS orphan_menu_count FROM sys_menu cur
  LEFT JOIN sys_menu par ON par.menu_id = cur.parent_id
 WHERE cur.parent_id > 0 AND par.menu_id IS NULL;
--   期望:0。

-- A15【汇总视角：本批涉及的 12 条菜单的 sys_role_menu 全貌】用于人工扫描合理性。
SELECT sm.menu_id, sm.menu_name, sm.menu_type, sm.perms,
       GROUP_CONCAT(srm.role_id ORDER BY srm.role_id) AS granted_roles
  FROM sys_menu sm
  LEFT JOIN sys_role_menu srm ON srm.menu_id = sm.menu_id
 WHERE sm.menu_id IN (2219,2220,2221,2222,2223,2224,2225,2226,2227,2240)
    OR sm.perms IN ('business:cashwithdraw:freeze', 'business:payablebatch:freeze')
 GROUP BY sm.menu_id, sm.menu_name, sm.menu_type, sm.perms
 ORDER BY sm.menu_id;

-- A16【验收提醒，不是 SQL】:必须用非超管账号逐角色登录验收——
--   用 cy_operator(100) 的账号:应能看到 2219/2220/2222/2223/2224/2225/2226/2227/2240，
--     并能操作 2223(定档定参)和 2225(上下架)。
--   用 cy_auditor(101) 的账号:应能看到 2222(只读列表)和 2226(核销证据)。
--   用 cy_support(102) 的账号:应能看到除 2221/2223/2225 之外的全部，但不能做任何碰钱操作。
--   用 cy_finance(103) 的账号:应能看到 2219/2220/2221/2226，并能操作 2221(人工回补)。
--   超管的 *:*:* 恒能看见一切 ⇒ 用超管验收等于没验收。


-- =====================================================================================
-- 回滚说明（不写进正文，仅供主控落库前参考）
-- =====================================================================================
-- 落库前:
--   CREATE TABLE sys_role_menu_bak_t1_20260726 AS SELECT * FROM sys_role_menu;
-- 回滚:
--   DELETE FROM sys_role_menu;
--   INSERT INTO sys_role_menu SELECT * FROM sys_role_menu_bak_t1_20260726;
--   -- 再加一条清理 freeze 菜单:
--   DELETE FROM sys_menu WHERE perms IN
--    ('business:cashwithdraw:freeze','business:payablebatch:freeze');
-- 验证回滚:
--   SELECT COUNT(*) FROM sys_role_menu WHERE menu_id IN
--    (2219,2220,2221,2222,2223,2224,2225,2226,2227,2240);
--   -- 期望:0
--
-- 为什么不用 DELETE 反向语句:
--   拍快照+整表恢复 比逐行 DELETE 更安全——不会因为多跑了一个 session 插了新行
--   而漏删，也不会因为漏写了一行 DELETE 而在回滚后残留脏行。
--   而且 sys_role_menu 就是个纯关联表，整表恢复是原子的、可验证的。
