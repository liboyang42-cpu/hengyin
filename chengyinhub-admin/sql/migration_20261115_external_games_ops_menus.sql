-- G3 external-games (2026-11-15): console menu and buttons for external game operations.
-- New page 外部游戏节点绑定 (business/externalgame/bindings) under 审核与风控 (audit-center), and three button
-- permissions under the existing 外部游戏举报 page:
--   business:externalgame:report    resolve or dismiss a player report
--   business:externalgame:takedown  take a game down or restore it
--   business:externalgame:bind      bind or unbind a game to a topic node
-- Only sys_menu rows; no sys_role_menu rows, so no role holds these permissions until the owner grants them
-- (the super admin bypasses permission checks). Reads keep business:audit:list. Re-runnable.
-- Rollback: DELETE FROM sys_menu WHERE perms IN ('business:externalgame:report','business:externalgame:takedown','business:externalgame:bind')
--           OR component = 'business/externalgame/bindings';
-- runner: requires=transaction
DROP TEMPORARY TABLE IF EXISTS tmp_cy_extgame_ops_menu_guard;
CREATE TEMPORARY TABLE tmp_cy_extgame_ops_menu_guard(id INT NOT NULL PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO tmp_cy_extgame_ops_menu_guard(id) VALUES(1);
-- The parent directory and the reports page must each exist exactly once, otherwise abort (primary key clash).
INSERT INTO tmp_cy_extgame_ops_menu_guard(id) SELECT 1 WHERE (SELECT COUNT(*) FROM sys_menu WHERE menu_type='M' AND path='audit-center')<>1;
INSERT INTO tmp_cy_extgame_ops_menu_guard(id) SELECT 1 WHERE (SELECT COUNT(*) FROM sys_menu WHERE menu_type='C' AND component='business/externalgame/reports')<>1;

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '外部游戏节点绑定',(SELECT m.menu_id FROM (SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='audit-center') m),58,'external-game-binding','business/externalgame/bindings',1,0,'C','0','0','','link','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE component='business/externalgame/bindings');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '处理举报',(SELECT m.menu_id FROM (SELECT menu_id FROM sys_menu WHERE menu_type='C' AND component='business/externalgame/reports') m),1,'','',1,0,'F','0','0','business:externalgame:report','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:externalgame:report');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '下架与恢复',(SELECT m.menu_id FROM (SELECT menu_id FROM sys_menu WHERE menu_type='C' AND component='business/externalgame/reports') m),2,'','',1,0,'F','0','0','business:externalgame:takedown','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:externalgame:takedown');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '绑定节点',(SELECT m.menu_id FROM (SELECT menu_id FROM sys_menu WHERE menu_type='C' AND component='business/externalgame/bindings') m),1,'','',1,0,'F','0','0','business:externalgame:bind','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:externalgame:bind');

-- Read back: each permission exactly once, the page exactly once, and no role holds any of them via this file.
INSERT INTO tmp_cy_extgame_ops_menu_guard(id) SELECT 1 WHERE (SELECT COUNT(*) FROM sys_menu WHERE perms IN ('business:externalgame:report','business:externalgame:takedown','business:externalgame:bind'))<>3;
INSERT INTO tmp_cy_extgame_ops_menu_guard(id) SELECT 1 WHERE (SELECT COUNT(*) FROM sys_menu WHERE component='business/externalgame/bindings')<>1;
DROP TEMPORARY TABLE tmp_cy_extgame_ops_menu_guard;
