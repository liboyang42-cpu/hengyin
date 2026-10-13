-- G0 后台页面迁移前的生产菜单缺口补齐（2026-07-22）
--
-- 【类别】③ 注册数据：只 INSERT sys_menu / sys_role_menu，不改表结构、不 UPDATE 既有菜单、不 DELETE。
-- 【范围】补 15 条经生产只读回读确认缺失的菜单记录：
--   ① cashwithdraw C + query/manage F；② chapterOffer C + edit F；
--   ③ roamPoi C + edit F；④ verifyEvidence C；⑤ clubRun C；
--   ⑥ officialevent 的 query/add/edit/notify F；⑦ memberwithdrawal 的 viewpii/audit F。
--
-- 【为什么不直接重跑旧文件】
-- - migration_official_event_menu.sql 会把已正确位于「供给与发布」的官方活动 C 改回旧目录；
-- - migration_fund_perm_withdrawal_split.sql 从旧 root F 推导 parent，会继续制造根级 F。
-- 本文件只锚定当前已验证的 C/九域父级，避免修缺口时回退已有 IA。
--
-- 【角色授权】除提现分权外，不批量授予新菜单：具体角色归属须由 G0-08 至 G0-12 决定。
-- 提现的 viewpii/audit 仅复制当前持有旧 review 权限的角色，保持原迁移声明的兼容语义。
--
-- 【可逆】仅当本迁移确认是唯一写入来源时：
-- DELETE FROM sys_role_menu WHERE menu_id IN (SELECT menu_id FROM sys_menu WHERE perms IN
--   ('business:cashwithdraw:list','business:cashwithdraw:query','business:cashwithdraw:manage',
--    'business:chapterOffer:list','business:chapterOffer:edit',
--    'business:roamPoi:list','business:roamPoi:edit','business:verifyEvidence:list','business:clubRun:list',
--    'business:officialevent:query','business:officialevent:add','business:officialevent:edit','business:officialevent:notify',
--    'business:memberwithdrawal:viewpii','business:memberwithdrawal:audit'));
-- DELETE FROM sys_menu WHERE perms IN (...同上，先删 F 再删 C...);
--
-- ======================= 第一步：只读预检 =======================
-- 运行前必须确认以下父级各一行、以下 target perms 全为 0 行：
-- SELECT menu_id, parent_id, menu_name, path, component, perms, menu_type
-- FROM sys_menu
-- WHERE component IN ('business/memberwithdrawal/index','business/officialevent/index')
--    OR (parent_id = 0 AND path IN ('merchants-coop','clubs-roam','audit-center'))
--    OR perms IN ('business:cashwithdraw:list','business:chapterOffer:list','business:roamPoi:list',
--                 'business:verifyEvidence:list','business:clubRun:list','business:officialevent:query',
--                 'business:memberwithdrawal:viewpii');

-- 生产锚点（2026-07-22 预检：memberwithdrawal C=2111，officialevent C=2192，九域父级均存在）
SET @cashParent := (SELECT parent_id FROM sys_menu
                    WHERE component = 'business/memberwithdrawal/index' AND menu_type = 'C'
                    ORDER BY menu_id LIMIT 1);
SET @merchantParent := (SELECT menu_id FROM sys_menu
                        WHERE parent_id = 0 AND path = 'merchants-coop' AND menu_type = 'M'
                        ORDER BY menu_id LIMIT 1);
SET @clubsRoamParent := (SELECT menu_id FROM sys_menu
                         WHERE parent_id = 0 AND path = 'clubs-roam' AND menu_type = 'M'
                         ORDER BY menu_id LIMIT 1);
SET @auditCenterParent := (SELECT menu_id FROM sys_menu
                           WHERE parent_id = 0 AND path = 'audit-center' AND menu_type = 'M'
                           ORDER BY menu_id LIMIT 1);
SET @officialEventMenu := (SELECT menu_id FROM sys_menu
                           WHERE component = 'business/officialevent/index' AND menu_type = 'C'
                           ORDER BY menu_id LIMIT 1);
SET @withdrawMenu := (SELECT menu_id FROM sys_menu
                      WHERE component = 'business/memberwithdrawal/index' AND menu_type = 'C'
                      ORDER BY menu_id LIMIT 1);

-- ① 微信提现出金：同当前银行卡提现 C 的父级，保留双通道而不改旧 IA。
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '提现出金管理', @cashParent, 20, 'cashwithdraw', 'business/cashwithdraw/index', 1, 0, 'C', '0', '0', 'business:cashwithdraw:list', 'money', 'admin', NOW(),
       'G0 补注册：微信提现出金与人工补单队列'
FROM dual
WHERE @cashParent IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:cashwithdraw:list');

SET @cashMenu := (SELECT menu_id FROM sys_menu WHERE perms = 'business:cashwithdraw:list' ORDER BY menu_id LIMIT 1);

INSERT INTO sys_menu (menu_name, parent_id, order_num, menu_type, visible, status, perms, create_by, create_time, remark)
SELECT '提现详情', @cashMenu, 1, 'F', '0', '0', 'business:cashwithdraw:query', 'admin', NOW(), 'G0 补注册：出金详情'
FROM dual
WHERE @cashMenu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:cashwithdraw:query');

INSERT INTO sys_menu (menu_name, parent_id, order_num, menu_type, visible, status, perms, create_by, create_time, remark)
SELECT '提现人工处理', @cashMenu, 2, 'F', '0', '0', 'business:cashwithdraw:manage', 'admin', NOW(), 'G0 补注册：重查与双人补单'
FROM dual
WHERE @cashMenu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:cashwithdraw:manage');

-- ② 商家三档承接审核：挂九域「商家与合作」。
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '三档承接审核', @merchantParent, 8, 'chapteroffer', 'business/chapteroffer/index', 1, 0, 'C', '0', '0', 'business:chapterOffer:list', 'merchant', 'admin', NOW(),
       'G0 补注册：商家章节供给审核台'
FROM dual
WHERE @merchantParent IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:chapterOffer:list');

SET @chapterOfferMenu := (SELECT menu_id FROM sys_menu WHERE perms = 'business:chapterOffer:list' ORDER BY menu_id LIMIT 1);

INSERT INTO sys_menu (menu_name, parent_id, order_num, menu_type, visible, status, perms, create_by, create_time, remark)
SELECT '三档承接操作', @chapterOfferMenu, 1, 'F', '0', '0', 'business:chapterOffer:edit', 'admin', NOW(), 'G0 补注册：定档定参与停用'
FROM dual
WHERE @chapterOfferMenu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:chapterOffer:edit');

-- ③ POI 治理、核销证据、带队场次：分别挂九域的俱乐部与漫游 / 审核与风控。
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT 'POI治理', @clubsRoamParent, 7, 'roampoi', 'business/roampoi/index', 1, 0, 'C', '0', '0', 'business:roamPoi:list', 'guide', 'admin', NOW(),
       'G0 补注册：漫游据点全量治理'
FROM dual
WHERE @clubsRoamParent IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:roamPoi:list');

SET @roamPoiMenu := (SELECT menu_id FROM sys_menu WHERE perms = 'business:roamPoi:list' ORDER BY menu_id LIMIT 1);

INSERT INTO sys_menu (menu_name, parent_id, order_num, menu_type, visible, status, perms, create_by, create_time, remark)
SELECT 'POI上下架', @roamPoiMenu, 1, 'F', '0', '0', 'business:roamPoi:edit', 'admin', NOW(), 'G0 补注册：可逆上下架治理'
FROM dual
WHERE @roamPoiMenu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:roamPoi:edit');

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '核销证据', @auditCenterParent, 8, 'verifyevidence', 'business/verifyevidence/index', 1, 0, 'C', '0', '0', 'business:verifyEvidence:list', 'eye-open', 'admin', NOW(),
       'G0 补注册：扫码与团核销只读调证'
FROM dual
WHERE @auditCenterParent IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:verifyEvidence:list');

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '带队场次', @clubsRoamParent, 8, 'clubrun', 'business/clubrun/index', 1, 0, 'C', '0', '0', 'business:clubRun:list', 'peoples', 'admin', NOW(),
       'G0 补注册：跨主题带队场次只读调证'
FROM dual
WHERE @clubsRoamParent IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:clubRun:list');

-- ④ 官方活动：仅补缺失 F，绝不重写既有 C 的 parent_id。
INSERT INTO sys_menu (menu_name, parent_id, order_num, menu_type, visible, status, perms, create_by, create_time, remark)
SELECT '官方活动查询', @officialEventMenu, 1, 'F', '0', '0', 'business:officialevent:query', 'admin', NOW(), 'G0 补注册'
FROM dual
WHERE @officialEventMenu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:officialevent:query');

INSERT INTO sys_menu (menu_name, parent_id, order_num, menu_type, visible, status, perms, create_by, create_time, remark)
SELECT '官方活动新增', @officialEventMenu, 2, 'F', '0', '0', 'business:officialevent:add', 'admin', NOW(), 'G0 补注册'
FROM dual
WHERE @officialEventMenu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:officialevent:add');

INSERT INTO sys_menu (menu_name, parent_id, order_num, menu_type, visible, status, perms, create_by, create_time, remark)
SELECT '官方活动编辑', @officialEventMenu, 3, 'F', '0', '0', 'business:officialevent:edit', 'admin', NOW(), 'G0 补注册；含状态与结算动作'
FROM dual
WHERE @officialEventMenu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:officialevent:edit');

INSERT INTO sys_menu (menu_name, parent_id, order_num, menu_type, visible, status, perms, create_by, create_time, remark)
SELECT '官方活动通知', @officialEventMenu, 4, 'F', '0', '0', 'business:officialevent:notify', 'admin', NOW(), 'G0 补注册：官方通知'
FROM dual
WHERE @officialEventMenu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:officialevent:notify');

-- ⑤ 银行卡提现分权：两个 F 挂当前提现 C，而非历史 root review F。
INSERT INTO sys_menu (menu_name, parent_id, order_num, menu_type, visible, status, perms, create_by, create_time, remark)
SELECT '查看提现收款资料明文', @withdrawMenu, 6, 'F', '0', '0', 'business:memberwithdrawal:viewpii', 'admin', NOW(), 'G0 补注册：仅查看收款资料'
FROM dual
WHERE @withdrawMenu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:memberwithdrawal:viewpii');

INSERT INTO sys_menu (menu_name, parent_id, order_num, menu_type, visible, status, perms, create_by, create_time, remark)
SELECT '提现审核操作', @withdrawMenu, 7, 'F', '0', '0', 'business:memberwithdrawal:audit', 'admin', NOW(), 'G0 补注册：审核通过或驳回'
FROM dual
WHERE @withdrawMenu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:memberwithdrawal:audit');

-- 仅继承历史 review 角色，防止 Controller 已拆权限后原财务角色静默 403。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT rm.role_id, added.menu_id
FROM sys_role_menu rm
JOIN sys_menu legacy ON legacy.menu_id = rm.menu_id AND legacy.perms = 'business:memberwithdrawal:review'
JOIN sys_menu added ON added.perms IN ('business:memberwithdrawal:viewpii', 'business:memberwithdrawal:audit')
WHERE NOT EXISTS (
    SELECT 1 FROM (SELECT role_id, menu_id FROM sys_role_menu) existing_grant
    WHERE existing_grant.role_id = rm.role_id AND existing_grant.menu_id = added.menu_id
);

-- ======================= 回读断言（执行后必须另行 SELECT 取结果） =======================
SELECT menu_id, parent_id, menu_name, path, component, perms, menu_type, visible, status
FROM sys_menu
WHERE perms IN (
    'business:cashwithdraw:list','business:cashwithdraw:query','business:cashwithdraw:manage',
    'business:chapterOffer:list','business:chapterOffer:edit',
    'business:roamPoi:list','business:roamPoi:edit','business:verifyEvidence:list','business:clubRun:list',
    'business:officialevent:query','business:officialevent:add','business:officialevent:edit','business:officialevent:notify',
    'business:memberwithdrawal:viewpii','business:memberwithdrawal:audit'
)
ORDER BY menu_id;
