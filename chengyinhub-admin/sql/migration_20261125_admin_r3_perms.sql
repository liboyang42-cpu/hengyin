-- ============================================================================
-- HIGH-A2: 随机掉落配置 / 盲盒任务池 专用按钮权限
-- ----------------------------------------------------------------------------
-- 两页原借用 business:topicnode:*,该组权限从未写入 sys_menu,非超管全部 403;
-- 掉落概率也不应随节点编辑权一起放开(LOW-S5)。
-- 仅新增 F 行与角色授权行,NOT EXISTS 幂等;不动账本表、不删改存量行。
-- 回滚脚本：bak_admin_r3_perms_20261125_rollback.sql
-- ============================================================================

SET @rd_menu := (SELECT menu_id FROM sys_menu WHERE component = 'business/rewarddrop/index' AND menu_type = 'C' LIMIT 1);
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '掉落查询', @rd_menu, 1, '#', '', 1, 0, 'F', '0', '0', 'business:rewarddrop:list', '#', 'migration', NOW(), ''
FROM DUAL WHERE @rd_menu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:rewarddrop:list');
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '掉落详情', @rd_menu, 2, '#', '', 1, 0, 'F', '0', '0', 'business:rewarddrop:query', '#', 'migration', NOW(), ''
FROM DUAL WHERE @rd_menu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:rewarddrop:query');
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '掉落新增', @rd_menu, 3, '#', '', 1, 0, 'F', '0', '0', 'business:rewarddrop:add', '#', 'migration', NOW(), ''
FROM DUAL WHERE @rd_menu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:rewarddrop:add');
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '掉落修改', @rd_menu, 4, '#', '', 1, 0, 'F', '0', '0', 'business:rewarddrop:edit', '#', 'migration', NOW(), ''
FROM DUAL WHERE @rd_menu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:rewarddrop:edit');
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '掉落删除', @rd_menu, 5, '#', '', 1, 0, 'F', '0', '0', 'business:rewarddrop:remove', '#', 'migration', NOW(), ''
FROM DUAL WHERE @rd_menu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:rewarddrop:remove');
-- 已被授予该页面的角色同步获得按钮权限(只增不删)。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT rm.role_id, f.menu_id
FROM sys_role_menu rm
JOIN sys_menu f ON f.parent_id = rm.menu_id AND f.menu_type = 'F' AND f.perms LIKE 'business:rewarddrop:%'
WHERE rm.menu_id = @rd_menu
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu x WHERE x.role_id = rm.role_id AND x.menu_id = f.menu_id);

SET @tp_menu := (SELECT menu_id FROM sys_menu WHERE component = 'business/nodetaskpool/index' AND menu_type = 'C' LIMIT 1);
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '任务池查询', @tp_menu, 1, '#', '', 1, 0, 'F', '0', '0', 'business:taskpool:list', '#', 'migration', NOW(), ''
FROM DUAL WHERE @tp_menu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:taskpool:list');
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '任务池详情', @tp_menu, 2, '#', '', 1, 0, 'F', '0', '0', 'business:taskpool:query', '#', 'migration', NOW(), ''
FROM DUAL WHERE @tp_menu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:taskpool:query');
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '任务池新增', @tp_menu, 3, '#', '', 1, 0, 'F', '0', '0', 'business:taskpool:add', '#', 'migration', NOW(), ''
FROM DUAL WHERE @tp_menu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:taskpool:add');
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '任务池修改', @tp_menu, 4, '#', '', 1, 0, 'F', '0', '0', 'business:taskpool:edit', '#', 'migration', NOW(), ''
FROM DUAL WHERE @tp_menu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:taskpool:edit');
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '任务池删除', @tp_menu, 5, '#', '', 1, 0, 'F', '0', '0', 'business:taskpool:remove', '#', 'migration', NOW(), ''
FROM DUAL WHERE @tp_menu IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:taskpool:remove');
-- 已被授予该页面的角色同步获得按钮权限(只增不删)。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT rm.role_id, f.menu_id
FROM sys_role_menu rm
JOIN sys_menu f ON f.parent_id = rm.menu_id AND f.menu_type = 'F' AND f.perms LIKE 'business:taskpool:%'
WHERE rm.menu_id = @tp_menu
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu x WHERE x.role_id = rm.role_id AND x.menu_id = f.menu_id);
