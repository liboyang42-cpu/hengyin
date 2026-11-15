-- ==================================================
-- SYS-AINPC: Theme character relation binding, avatar job review columns, and E1 admin menu
-- Additive and reversible (zero changes to financial/ledger tables).
-- Rollback:
--   DELETE FROM sys_role_menu WHERE menu_id IN (SELECT menu_id FROM sys_menu WHERE component = 'business/themenpc/index' OR perms LIKE 'business:themeNpc:%');
--   DELETE FROM sys_menu WHERE component = 'business/themenpc/index' OR perms LIKE 'business:themeNpc:%';
--   ALTER TABLE merchant_npc_avatar_job DROP COLUMN review_time, DROP COLUMN review_reason, DROP COLUMN review_status;
--   ALTER TABLE npc_profile DROP COLUMN relation_key;
-- ==================================================

SET @col_rel := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'npc_profile' AND COLUMN_NAME = 'relation_key');
SET @sql_rel := IF(@col_rel = 0, 'ALTER TABLE npc_profile ADD COLUMN relation_key VARCHAR(64) NULL COMMENT "relation key (scope_type=1)"', 'SELECT 1');
PREPARE stmt_rel FROM @sql_rel; EXECUTE stmt_rel; DEALLOCATE PREPARE stmt_rel;

SET @col_rev_status := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'merchant_npc_avatar_job' AND COLUMN_NAME = 'review_status');
SET @sql_rev_status := IF(@col_rev_status = 0, 'ALTER TABLE merchant_npc_avatar_job ADD COLUMN review_status VARCHAR(16) NULL COMMENT "PENDING/APPROVED/REJECTED"', 'SELECT 1');
PREPARE stmt_rev_status FROM @sql_rev_status; EXECUTE stmt_rev_status; DEALLOCATE PREPARE stmt_rev_status;

SET @col_rev_reason := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'merchant_npc_avatar_job' AND COLUMN_NAME = 'review_reason');
SET @sql_rev_reason := IF(@col_rev_reason = 0, 'ALTER TABLE merchant_npc_avatar_job ADD COLUMN review_reason VARCHAR(255) NULL COMMENT "review reason"', 'SELECT 1');
PREPARE stmt_rev_reason FROM @sql_rev_reason; EXECUTE stmt_rev_reason; DEALLOCATE PREPARE stmt_rev_reason;

SET @col_rev_time := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'merchant_npc_avatar_job' AND COLUMN_NAME = 'review_time');
SET @sql_rev_time := IF(@col_rev_time = 0, 'ALTER TABLE merchant_npc_avatar_job ADD COLUMN review_time DATETIME(3) NULL COMMENT "review time"', 'SELECT 1');
PREPARE stmt_rev_time FROM @sql_rev_time; EXECUTE stmt_rev_time; DEALLOCATE PREPARE stmt_rev_time;

SET @parent := COALESCE((SELECT menu_id FROM sys_menu WHERE menu_name = '供给与发布' AND menu_type = 'M' LIMIT 1), (SELECT menu_id FROM sys_menu WHERE menu_name = '业务管理' AND menu_type = 'M' LIMIT 1), 0);
INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '剧本NPC与AI审核', IFNULL(@parent, 0), 91, 'themenpc', 'business/themenpc/index', 1, 0, 'C', '0', '0', 'business:themeNpc:list', 'user', 'admin', NOW(), 'E1主题人物表、3D形象任务审核、声音授权审计与AI提供方状态'
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE component = 'business/themenpc/index');

SET @themeNpcMenu := (SELECT menu_id FROM sys_menu WHERE component = 'business/themenpc/index' LIMIT 1);

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time)
SELECT '剧本NPC查询', @themeNpcMenu, 1, '', '', 1, 0, 'F', '0', '0', 'business:themeNpc:query', '#', 'admin', NOW()
FROM dual WHERE @themeNpcMenu IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:themeNpc:query');

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time)
SELECT '剧本NPC新增', @themeNpcMenu, 2, '', '', 1, 0, 'F', '0', '0', 'business:themeNpc:add', '#', 'admin', NOW()
FROM dual WHERE @themeNpcMenu IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:themeNpc:add');

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time)
SELECT '剧本NPC编辑', @themeNpcMenu, 3, '', '', 1, 0, 'F', '0', '0', 'business:themeNpc:edit', '#', 'admin', NOW()
FROM dual WHERE @themeNpcMenu IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:themeNpc:edit');

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time)
SELECT '剧本NPC删除', @themeNpcMenu, 4, '', '', 1, 0, 'F', '0', '0', 'business:themeNpc:remove', '#', 'admin', NOW()
FROM dual WHERE @themeNpcMenu IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:themeNpc:remove');

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, m.menu_id FROM sys_menu m
WHERE (m.component = 'business/themenpc/index' OR m.perms LIKE 'business:themeNpc:%')
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 1 AND rm.menu_id = m.menu_id);
