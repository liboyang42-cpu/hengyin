-- 随机掉落配置页权限合并(2026-11-26,路线图 × 安全 r3 集成)。
-- 背景:同一页面 business/rewarddrop/index 现在调用两组接口:
--   * sys-random 后台 /business/reward-drop/**     校验 business:rewarddrop:{list,query,add,edit,remove}
--   * R10 规则编辑器 /business/rewardDropRule/**   校验 business:rewardDrop:{list,add,edit}(校验与启停按钮)
--   sys_menu.perms 是 utf8mb4_general_ci(不分大小写),20261115_admin_consoles 与 20261125_admin_r3_perms 的
--   NOT EXISTS(perms = ...) 会把两组互相当成已存在而跳过;而 Spring 的 @ss.hasPermi 按 Java 字符串精确比较。
--   结果:按台账顺序执行后,非超管角色缺一半权限,页面上的按钮 403。
-- 做法:只补 F 行(COLLATE utf8mb4_bin 精确判重),挂在唯一的页面行下;再把这些按钮授给
--       已持有该页面的角色和 role_id = 1(管理员角色)。只增不删,不动页面行,不动台账表,可重复执行。
-- 前置:20261115_admin_consoles、20261123_sys_random、20261125_admin_r3_perms(ci/migration-dependencies.json)。
-- 回滚:
--   DELETE FROM sys_role_menu WHERE menu_id IN (SELECT menu_id FROM (SELECT menu_id FROM sys_menu WHERE remark = 'reward-drop perm union') x);
--   DELETE FROM sys_menu WHERE remark = 'reward-drop perm union';

SET @rd_page := (SELECT menu_id FROM sys_menu WHERE component = 'business/rewarddrop/index' AND menu_type = 'C' ORDER BY menu_id LIMIT 1);

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '掉落查询', @rd_page, 11, '', '', 1, 0, 'F', '0', '0', 'business:rewarddrop:list', '#', 'migration', NOW(), 'reward-drop perm union'
FROM DUAL WHERE @rd_page IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms COLLATE utf8mb4_bin = 'business:rewarddrop:list');
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '掉落详情', @rd_page, 12, '', '', 1, 0, 'F', '0', '0', 'business:rewarddrop:query', '#', 'migration', NOW(), 'reward-drop perm union'
FROM DUAL WHERE @rd_page IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms COLLATE utf8mb4_bin = 'business:rewarddrop:query');
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '掉落新增', @rd_page, 13, '', '', 1, 0, 'F', '0', '0', 'business:rewarddrop:add', '#', 'migration', NOW(), 'reward-drop perm union'
FROM DUAL WHERE @rd_page IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms COLLATE utf8mb4_bin = 'business:rewarddrop:add');
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '掉落修改', @rd_page, 14, '', '', 1, 0, 'F', '0', '0', 'business:rewarddrop:edit', '#', 'migration', NOW(), 'reward-drop perm union'
FROM DUAL WHERE @rd_page IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms COLLATE utf8mb4_bin = 'business:rewarddrop:edit');
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '掉落删除', @rd_page, 15, '', '', 1, 0, 'F', '0', '0', 'business:rewarddrop:remove', '#', 'migration', NOW(), 'reward-drop perm union'
FROM DUAL WHERE @rd_page IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms COLLATE utf8mb4_bin = 'business:rewarddrop:remove');
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '掉落规则列表与校验', @rd_page, 16, '', '', 1, 0, 'F', '0', '0', 'business:rewardDrop:list', '#', 'migration', NOW(), 'reward-drop perm union'
FROM DUAL WHERE @rd_page IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms COLLATE utf8mb4_bin = 'business:rewardDrop:list');
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '新建掉落规则', @rd_page, 17, '', '', 1, 0, 'F', '0', '0', 'business:rewardDrop:add', '#', 'migration', NOW(), 'reward-drop perm union'
FROM DUAL WHERE @rd_page IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms COLLATE utf8mb4_bin = 'business:rewardDrop:add');
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '编辑与启停掉落规则', @rd_page, 18, '', '', 1, 0, 'F', '0', '0', 'business:rewardDrop:edit', '#', 'migration', NOW(), 'reward-drop perm union'
FROM DUAL WHERE @rd_page IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms COLLATE utf8mb4_bin = 'business:rewardDrop:edit');

-- 管理员角色(role_id = 1)持有页面与全部按钮。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, m.menu_id FROM sys_menu m
WHERE @rd_page IS NOT NULL
  AND (m.menu_id = @rd_page OR (m.parent_id = @rd_page AND m.menu_type = 'F'))
  AND EXISTS (SELECT 1 FROM sys_role WHERE role_id = 1)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 1 AND rm.menu_id = m.menu_id);

-- 已被授予该页面的其它角色同步获得全部按钮(只增不删)。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, f.menu_id
FROM sys_role_menu rm
JOIN sys_menu f ON f.parent_id = rm.menu_id AND f.menu_type = 'F'
WHERE rm.menu_id = @rd_page
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu x WHERE x.role_id = rm.role_id AND x.menu_id = f.menu_id);

-- 回读:页面存在时,两组 8 个权限标识(按二进制区分大小写)都应在 sys_menu 中各至少一行。
-- runner: assert=scalar-true
SELECT @rd_page IS NULL OR COUNT(DISTINCT CAST(perms AS BINARY)) = 8 FROM sys_menu
WHERE CAST(perms AS BINARY) IN (CAST('business:rewarddrop:list' AS BINARY), CAST('business:rewarddrop:query' AS BINARY),
  CAST('business:rewarddrop:add' AS BINARY), CAST('business:rewarddrop:edit' AS BINARY), CAST('business:rewarddrop:remove' AS BINARY),
  CAST('business:rewardDrop:list' AS BINARY), CAST('business:rewardDrop:add' AS BINARY), CAST('business:rewardDrop:edit' AS BINARY));
