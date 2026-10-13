-- 角色配额管理 菜单 + 权限
-- 挂在已有的「俱乐部管理」菜单所在父级之下（menu_id=1即顶层,或手动指定 parent_id）

SET @parent := (SELECT menu_id FROM sys_menu WHERE menu_name = '俱乐部管理' LIMIT 1);
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time)
VALUES ('角色配额', IFNULL(@parent, 1), 10, 'userpermission', 'business/userpermission/index', 1, 0, 'C', '0', '0', 'business:userpermission:list', 'component', 'admin', sysdate());
SET @m := LAST_INSERT_ID();
INSERT INTO sys_menu (menu_name, parent_id, order_num, menu_type, visible, status, perms, create_by, create_time) VALUES
('配额修改', @m, 1, 'F', '0', '0', 'business:userpermission:edit', 'admin', sysdate());
