-- coop 客诉工单后台菜单 + 权限(幂等 INSERT...SELECT...WHERE NOT EXISTS,menu_id 自增防撞)
INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
SELECT '客诉工单', 0, 61, 'coopcomplaint', 'business/coopcomplaint/index', '', '', 1, 0, 'C', '0', '0', 'business:coopcomplaint:list', 'message', 'admin', sysdate(), '', NULL, 'coop 客诉责任链工单(受理/垫付/扣划/关闭)'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:coopcomplaint:list');

SET @cc_parent = (SELECT menu_id FROM sys_menu WHERE perms = 'business:coopcomplaint:list' ORDER BY menu_id DESC LIMIT 1);

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
SELECT '客诉工单查询', @cc_parent, 1, '', '', '', '', 1, 0, 'F', '0', '0', 'business:coopcomplaint:query', '#', 'admin', sysdate(), '', NULL, ''
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:coopcomplaint:query');

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
SELECT '客诉处理(垫付/关闭)', @cc_parent, 2, '', '', '', '', 1, 0, 'F', '0', '0', 'business:coopcomplaint:handle', '#', 'admin', sysdate(), '', NULL, '涉及资金:先行垫付/过错扣划'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:coopcomplaint:handle');
