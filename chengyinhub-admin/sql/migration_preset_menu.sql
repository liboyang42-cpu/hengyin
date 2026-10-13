-- ============================================================
-- 建议版预设 后台菜单 + 权限按钮(风格管理 / 路线管理)
-- 依赖:先执行 migration_preset_route.sql 建表
-- 执行后:超级管理员(admin)即可见;其他角色需在「角色管理」勾选授权
-- 注意:菜单ID用自增,parent 用变量回填,避免与现有ID冲突
-- ============================================================

-- 1) 目录:建议版预设
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
VALUES ('建议版预设', 0, 90, 'preset', NULL, 1, 0, 'M', '0', '0', '', 'guide', 'admin', NOW(), '建议版发布的风格/路线预设管理');
SET @pid = LAST_INSERT_ID();

-- 2) 菜单:主题风格
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
VALUES ('主题风格', @pid, 1, 'style', 'business/preset/style/index', 1, 0, 'C', '0', '0', 'business:presetStyle:list', 'skill', 'admin', NOW(), '');
SET @sid = LAST_INSERT_ID();
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time)
VALUES ('风格查询', @sid, 1, '', NULL, 1, 0, 'F', '0', '0', 'business:presetStyle:query', '#', 'admin', NOW()),
       ('风格新增', @sid, 2, '', NULL, 1, 0, 'F', '0', '0', 'business:presetStyle:add', '#', 'admin', NOW()),
       ('风格修改', @sid, 3, '', NULL, 1, 0, 'F', '0', '0', 'business:presetStyle:edit', '#', 'admin', NOW()),
       ('风格删除', @sid, 4, '', NULL, 1, 0, 'F', '0', '0', 'business:presetStyle:remove', '#', 'admin', NOW());

-- 3) 菜单:城市路线
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
VALUES ('城市路线', @pid, 2, 'route', 'business/preset/route/index', 1, 0, 'C', '0', '0', 'business:presetRoute:list', 'guide', 'admin', NOW(), '');
SET @rid = LAST_INSERT_ID();
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time)
VALUES ('路线查询', @rid, 1, '', NULL, 1, 0, 'F', '0', '0', 'business:presetRoute:query', '#', 'admin', NOW()),
       ('路线新增', @rid, 2, '', NULL, 1, 0, 'F', '0', '0', 'business:presetRoute:add', '#', 'admin', NOW()),
       ('路线修改', @rid, 3, '', NULL, 1, 0, 'F', '0', '0', 'business:presetRoute:edit', '#', 'admin', NOW()),
       ('路线删除', @rid, 4, '', NULL, 1, 0, 'F', '0', '0', 'business:presetRoute:remove', '#', 'admin', NOW());
