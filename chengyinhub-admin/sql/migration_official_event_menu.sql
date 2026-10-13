-- ============================================================================
-- 官方活动 Web 后台菜单:挂在业务管理目录下,C 菜单 + 按钮权限(query/add/edit/notify)。
-- 组件 business/officialevent/index(对应 chengyinhub-ui 页面)。
-- 幂等:C 菜单按 component 去重、按钮按 perms 去重;parent_id 动态解析(不硬编码)。
-- super admin 自动可见(getRouters 返回全部);非超管运营角色须被分配这些权限后才能用。
-- ============================================================================

-- ① 业务管理目录 menu_id 动态解析(勿硬编码 parent_id;沿用 migration_club.sql 同一模式)
SET @parent := (SELECT menu_id FROM sys_menu WHERE menu_name IN ('业务管理','内容管理','系统管理') ORDER BY parent_id LIMIT 1);

-- ② C 菜单(按 component 幂等)
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '官方活动', IFNULL(@parent, 0), 80, 'officialevent', 'business/officialevent/index', 1, 0, 'C', '0', '0', 'business:officialevent:list', 'star', 'admin', NOW(), '官方活动系统管理'
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE component = 'business/officialevent/index');

-- ③ 纠正:若此前用硬编码 parent_id 挂错父级,且能解析到业务管理目录,则归位
UPDATE sys_menu SET parent_id = @parent
WHERE component = 'business/officialevent/index' AND @parent IS NOT NULL AND parent_id <> @parent;

-- ④ 该 C 菜单 menu_id(用于挂按钮权限)
SET @evtMenu := (SELECT menu_id FROM sys_menu WHERE component = 'business/officialevent/index' LIMIT 1);

-- ⑤ 按钮权限(F,按 perms 幂等):list 已在 C 菜单;补 query/add/edit/notify
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time)
SELECT '官方活动查询', @evtMenu, 1, '', '', 1, 0, 'F', '0', '0', 'business:officialevent:query', '#', 'admin', NOW()
FROM dual WHERE @evtMenu IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:officialevent:query');

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time)
SELECT '官方活动新增', @evtMenu, 2, '', '', 1, 0, 'F', '0', '0', 'business:officialevent:add', '#', 'admin', NOW()
FROM dual WHERE @evtMenu IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:officialevent:add');

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time)
SELECT '官方活动编辑', @evtMenu, 3, '', '', 1, 0, 'F', '0', '0', 'business:officialevent:edit', '#', 'admin', NOW()
FROM dual WHERE @evtMenu IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:officialevent:edit');

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time)
SELECT '官方活动通知', @evtMenu, 4, '', '', 1, 0, 'F', '0', '0', 'business:officialevent:notify', '#', 'admin', NOW()
FROM dual WHERE @evtMenu IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:officialevent:notify');
