-- 外部游戏变现与流量激励后台菜单，挂在「审核与风控」(audit-center)下；复用 business:audit:list/review 权限。
-- runner: requires=transaction
DROP TEMPORARY TABLE IF EXISTS tmp_cy_extgame_money_menu_guard;
CREATE TEMPORARY TABLE tmp_cy_extgame_money_menu_guard(id INT NOT NULL PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO tmp_cy_extgame_money_menu_guard(id) VALUES(1);
INSERT INTO tmp_cy_extgame_money_menu_guard(id) SELECT 1 WHERE (SELECT COUNT(*) FROM sys_menu WHERE menu_type='M' AND path='audit-center')<>1;
INSERT INTO tmp_cy_extgame_money_menu_guard(id) SELECT 1 WHERE EXISTS (SELECT component FROM sys_menu WHERE component='business/externalgame/monetization');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '外部游戏变现',(SELECT m.menu_id FROM (SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='audit-center') m),57,'external-game-monetization','business/externalgame/monetization',1,0,'C','0','0','','money','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE component='business/externalgame/monetization');

DROP TEMPORARY TABLE tmp_cy_extgame_money_menu_guard;
