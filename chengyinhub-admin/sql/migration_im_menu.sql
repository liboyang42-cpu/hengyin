-- 站内消息·后台客服台 菜单 + 权限
-- 执行：MySQL 连库后 source 本文件（依赖 migration_im.sql 先建表）
-- 说明：超级管理员(role_key='admin')自动拥有全部菜单；非超管运营角色需见文件末尾授权段。

-- 1) 客服消息 菜单(C) —— 默认挂在顶级(parent_id=0)，可在后台「菜单管理」里拖到「业务管理」下
INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
VALUES
  ('客服消息', 0, 90, 'im', 'business/im/index', 1, 0, 'C', '0', '0', 'business:im:list', 'message', 'admin', sysdate(), '站内消息后台客服台');

SET @mid := LAST_INSERT_ID();

-- 2) 按钮权限
INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time)
VALUES
  ('客服发送', @mid, 1, '', '', 1, 0, 'F', '0', '0', 'business:im:send', '#', 'admin', sysdate()),
  ('拉黑用户', @mid, 2, '', '', 1, 0, 'F', '0', '0', 'business:im:block', '#', 'admin', sysdate());

-- 3) （可选）授权给非超管运营角色：把 @role 改成目标 sys_role.role_id 再执行
-- SET @role := 2;
-- INSERT INTO sys_role_menu (role_id, menu_id)
-- SELECT @role, menu_id FROM sys_menu WHERE perms IN ('business:im:list','business:im:send','business:im:block')
-- ON DUPLICATE KEY UPDATE menu_id = menu_id;
