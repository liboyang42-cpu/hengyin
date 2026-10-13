-- 2026-08-18 production no-data schema 的最小数据前置。
-- 用途仅是让后续 migration 在 scratch MySQL 上验证真实父菜单和 RBAC 归属；不是生产 migration。
-- 事实来源：source commit 737f0c2eab1dab2afaa51944d8654a7f803671c3 的 bootstrap/migration，
-- 并于 2026-09-21 从生产库只读回读 ID、自然键、父子关系与角色归属。

INSERT IGNORE INTO sys_role
  (role_id, role_name, role_key, role_sort, data_scope, menu_check_strictly,
   dept_check_strictly, status, del_flag, create_by, create_time, update_by, update_time, remark)
VALUES
  (1, '超级管理员', 'admin', 1, '1', 1, 1, '0', '0', 'admin', NOW(), '', NULL, '超级管理员'),
  (2, '普通角色', 'common', 2, '2', 1, 1, '0', '0', 'admin', NOW(), 'admin', NULL, '普通角色'),
  (100, '日常运营', 'cy_operator', 10, '1', 1, 1, '0', '0', 'migration', NOW(), '', NULL, '城瘾后台日常运营：内容与供给，不含资金终审'),
  (101, '审核风控', 'cy_auditor', 20, '1', 1, 1, '0', '0', 'migration', NOW(), '', NULL, '城瘾后台审核风控：统一审核台，不含资金与业务事实写入'),
  (102, '客服售后', 'cy_support', 30, '2', 1, 1, '0', '0', 'migration', NOW(), '', NULL, '城瘾后台客服：用户调证与售后发起，只读业务对象'),
  (103, '财务结算', 'cy_finance', 40, '1', 1, 1, '0', '0', 'migration', NOW(), '', NULL, '城瘾后台财务：资金查询、复核与结算，不含内容供给编辑');

INSERT IGNORE INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, `query`, route_name,
   is_frame, is_cache, menu_type, visible, status, perms, icon,
   create_by, create_time, update_by, update_time, remark)
VALUES
  (1, '系统管理', 2216, 10, 'system', NULL, '', '', 1, 0, 'M', '0', '0', '', 'system', 'admin', NOW(), 'admin', NULL, '系统管理目录'),
  (2007, '官方主题', 2209, 10, 'official-topic', 'business/topic/index', '{"scope":"official"}', 'OfficialTopic', 1, 0, 'C', '0', '0', 'business:topic:list', 'tree', 'admin', NOW(), 'admin', NULL, '主题菜单'),
  (2008, '主题查询', 2007, 10, '#', '', NULL, '', 1, 0, 'F', '0', '0', 'business:topic:query', '#', 'admin', NOW(), 'admin', NULL, ''),
  (2111, '银行卡提现审核', 2214, 30, 'memberwithdrawal', 'business/memberwithdrawal/index', NULL, '', 1, 0, 'C', '0', '0', 'business:memberwithdrawal:list', 'money', 'admin', NOW(), 'admin', NULL, '提现管理菜单'),
  (2147, '俱乐部工作台', 2211, 10, 'club', 'business/club/index', NULL, '', 1, 0, 'C', '0', '0', 'business:club:list', 'peoples', 'admin', NOW(), '', NULL, ''),
  (2157, '审核工作台', 2210, 20, 'audit', 'business/audit/index', '', '', 1, 0, 'C', '0', '0', 'business:audit:list', 'validCode', 'admin', NOW(), '', NULL, 'P3统一审核'),
  (2209, '供给与发布', 0, 2, 'supply', NULL, '', '', 1, 0, 'M', '0', '0', '', 'shopping', 'migration', NOW(), '', NULL, '城瘾 Web 后台九域导航'),
  (2210, '审核与风控', 0, 3, 'audit-center', NULL, '', '', 1, 0, 'M', '0', '0', '', 'audit', 'migration', NOW(), '', NULL, '城瘾 Web 后台九域导航'),
  (2211, '俱乐部', 0, 4, 'clubs-roam', NULL, '', '', 1, 0, 'M', '0', '0', '', 'guide', 'migration', NOW(), '', NULL, '城瘾 Web 后台九域导航'),
  (2214, '资金与交易', 0, 8, 'funds-aftercare', NULL, '', '', 1, 0, 'M', '0', '0', '', 'money', 'migration', NOW(), '', NULL, '城瘾 Web 后台九域导航'),
  (2216, '系统与运维', 0, 10, 'system-ops', NULL, '', '', 1, 0, 'M', '0', '0', '', 'tool', 'migration', NOW(), '', NULL, '城瘾 Web 后台九域导航'),
  (2219, '微信提现回补', 2214, 20, 'cashwithdraw', 'business/cashwithdraw/index', NULL, '', 1, 0, 'C', '0', '0', 'business:cashwithdraw:list', 'money', 'admin', NOW(), '', NULL, 'G0 补注册：微信提现出金与人工补单队列');

-- runner: assert=scalar-true
SELECT
  (SELECT COUNT(*) FROM sys_role WHERE role_id = 1 AND role_key = 'admin' AND status = '0' AND del_flag = '0') = 1
  AND (SELECT COUNT(*) FROM sys_role WHERE role_key = 'admin') = 1
  AND (SELECT COUNT(*) FROM sys_role WHERE role_id = 103 AND role_key = 'cy_finance' AND status = '0' AND del_flag = '0') = 1
  AND (SELECT COUNT(*) FROM sys_menu WHERE menu_id = 2209 AND parent_id = 0 AND path = 'supply') = 1
  AND (SELECT COUNT(*) FROM sys_menu WHERE menu_id = 2210 AND parent_id = 0 AND path = 'audit-center') = 1
  AND (SELECT COUNT(*) FROM sys_menu WHERE menu_id = 2211 AND parent_id = 0 AND path = 'clubs-roam') = 1
  AND (SELECT COUNT(*) FROM sys_menu WHERE menu_id = 2214 AND parent_id = 0 AND path = 'funds-aftercare') = 1
  AND (SELECT COUNT(*) FROM sys_menu WHERE menu_id = 2216 AND parent_id = 0 AND path = 'system-ops') = 1
  AND (SELECT COUNT(*) FROM sys_menu WHERE menu_id = 2007 AND parent_id = 2209 AND component = 'business/topic/index' AND perms = 'business:topic:list') = 1
  AND (SELECT COUNT(*) FROM sys_menu WHERE menu_id = 2008 AND parent_id = 2007 AND perms = 'business:topic:query') = 1
  AND (SELECT COUNT(*) FROM sys_menu WHERE menu_id = 2111 AND parent_id = 2214 AND perms = 'business:memberwithdrawal:list') = 1
  AND (SELECT COUNT(*) FROM sys_menu WHERE menu_id = 2147 AND parent_id = 2211 AND perms = 'business:club:list') = 1
  AND (SELECT COUNT(*) FROM sys_menu WHERE menu_id = 2157 AND parent_id = 2210 AND perms = 'business:audit:list') = 1
  AND (SELECT COUNT(*) FROM sys_menu WHERE menu_id = 2219 AND parent_id = 2214 AND perms = 'business:cashwithdraw:list') = 1
  AND (SELECT COUNT(*) FROM sys_menu WHERE perms IN (
        'business:audit:log:list',
        'business:withdrawal:cooldown-config',
        'business:clubextra:approve',
        'business:clubhours:confirm',
        'business:clubhours:correct',
        'business:clubpayable:confirm',
        'business:clubpayable:paid',
        'business:clubquality:confirm',
        'business:clubquality:exclude',
        'business:editionfinal:execute',
        'business:clubpayee:payout',
        'business:clubpayee:verify')) = 0
  AND (SELECT COUNT(*) FROM sys_config
       WHERE config_key = 'withdrawal.player-complaint-cooldown-hours') = 0
  AS assertion_ok;
