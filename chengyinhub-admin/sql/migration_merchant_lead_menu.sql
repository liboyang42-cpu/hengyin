-- 商家目录后台菜单 + 按钮权限(幂等:INSERT...SELECT...WHERE NOT EXISTS,menu_id 自增防撞)
-- 顶级菜单(如需归入其它目录,改 parent_id)
INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
SELECT '商家目录', 0, 60, 'merchantlead', 'business/merchantlead/index', '', '', 1, 0, 'C', '0', '0', 'business:merchantlead:list', 'peoples', 'admin', sysdate(), '', NULL, '含未注册商家目录(附近可电话联系)'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:merchantlead:list');

SET @ml_parent = (SELECT menu_id FROM sys_menu WHERE perms = 'business:merchantlead:list' ORDER BY menu_id DESC LIMIT 1);

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
SELECT '商家目录查询', @ml_parent, 1, '', '', '', '', 1, 0, 'F', '0', '0', 'business:merchantlead:query', '#', 'admin', sysdate(), '', NULL, ''
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:merchantlead:query');

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
SELECT '商家目录新增', @ml_parent, 2, '', '', '', '', 1, 0, 'F', '0', '0', 'business:merchantlead:add', '#', 'admin', sysdate(), '', NULL, ''
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:merchantlead:add');

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
SELECT '商家目录修改', @ml_parent, 3, '', '', '', '', 1, 0, 'F', '0', '0', 'business:merchantlead:edit', '#', 'admin', sysdate(), '', NULL, ''
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:merchantlead:edit');

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
SELECT '商家目录删除', @ml_parent, 4, '', '', '', '', 1, 0, 'F', '0', '0', 'business:merchantlead:remove', '#', 'admin', sysdate(), '', NULL, ''
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:merchantlead:remove');
