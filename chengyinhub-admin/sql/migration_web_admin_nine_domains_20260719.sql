-- 九域一级导航基线：只调整 sys_menu 层级，不改 component/path/perms。
SET @now = NOW();

INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '工作台',0,1,'workbench',NULL,'','',1,0,'M','0','0','','dashboard','migration',@now,'城瘾 Web 后台九域导航'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path='workbench' AND parent_id=0 AND menu_type='M');
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '供给与发布',0,2,'supply',NULL,'','',1,0,'M','0','0','','shopping','migration',@now,'城瘾 Web 后台九域导航'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path='supply' AND parent_id=0 AND menu_type='M');
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '审核与风控',0,3,'audit-center',NULL,'','',1,0,'M','0','0','','audit','migration',@now,'城瘾 Web 后台九域导航'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path='audit-center' AND parent_id=0 AND menu_type='M');
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '俱乐部与漫游',0,4,'clubs-roam',NULL,'','',1,0,'M','0','0','','guide','migration',@now,'城瘾 Web 后台九域导航'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path='clubs-roam' AND parent_id=0 AND menu_type='M');
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '商家与合作',0,5,'merchants-coop',NULL,'','',1,0,'M','0','0','','merchant','migration',@now,'城瘾 Web 后台九域导航'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path='merchants-coop' AND parent_id=0 AND menu_type='M');
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '用户与客服',0,6,'users-support',NULL,'','',1,0,'M','0','0','','user','migration',@now,'城瘾 Web 后台九域导航'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path='users-support' AND parent_id=0 AND menu_type='M');
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '资金与售后',0,7,'funds-aftercare',NULL,'','',1,0,'M','0','0','','money','migration',@now,'城瘾 Web 后台九域导航'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path='funds-aftercare' AND parent_id=0 AND menu_type='M');
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '数据与配置',0,8,'data-config',NULL,'','',1,0,'M','0','0','','chart','migration',@now,'城瘾 Web 后台九域导航'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path='data-config' AND parent_id=0 AND menu_type='M');
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '系统与运维',0,9,'system-ops',NULL,'','',1,0,'M','0','0','','tool','migration',@now,'城瘾 Web 后台九域导航'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path='system-ops' AND parent_id=0 AND menu_type='M');

SET @workbench=(SELECT menu_id FROM sys_menu WHERE path='workbench' AND parent_id=0 LIMIT 1);
SET @supply=(SELECT menu_id FROM sys_menu WHERE path='supply' AND parent_id=0 LIMIT 1);
SET @audit=(SELECT menu_id FROM sys_menu WHERE path='audit-center' AND parent_id=0 LIMIT 1);
SET @clubs=(SELECT menu_id FROM sys_menu WHERE path='clubs-roam' AND parent_id=0 LIMIT 1);
SET @merchants=(SELECT menu_id FROM sys_menu WHERE path='merchants-coop' AND parent_id=0 LIMIT 1);
SET @users=(SELECT menu_id FROM sys_menu WHERE path='users-support' AND parent_id=0 LIMIT 1);
SET @funds=(SELECT menu_id FROM sys_menu WHERE path='funds-aftercare' AND parent_id=0 LIMIT 1);
SET @data=(SELECT menu_id FROM sys_menu WHERE path='data-config' AND parent_id=0 LIMIT 1);

UPDATE sys_menu SET parent_id=@supply WHERE parent_id=4 AND menu_type='C' AND (component LIKE 'business/topic/%' OR component LIKE 'business/officialevent/%' OR component LIKE 'business/templatelibrary/%');
UPDATE sys_menu SET parent_id=@audit WHERE parent_id=4 AND menu_type='C' AND (component LIKE 'business/audit/%' OR component LIKE 'business/auditTask/%');
UPDATE sys_menu SET parent_id=@clubs WHERE parent_id=4 AND menu_type='C' AND (component LIKE 'business/club/%' OR component LIKE 'business/npcRouteEgg/%' OR component LIKE 'business/roam/%');
UPDATE sys_menu SET parent_id=@merchants WHERE parent_id=4 AND menu_type='C' AND (component LIKE 'business/merchant/%' OR component LIKE 'business/registrationmerchant/%' OR component LIKE 'business/coop/%');
UPDATE sys_menu SET parent_id=@users WHERE parent_id=4 AND menu_type='C' AND (component LIKE 'business/member/%' OR component LIKE 'business/comment/%' OR component LIKE 'business/square/%');
UPDATE sys_menu SET parent_id=@funds WHERE parent_id=4 AND menu_type='C' AND (component LIKE 'business/refund/%' OR component LIKE 'business/withdraw/%' OR component LIKE 'business/settlement/%');
UPDATE sys_menu SET parent_id=@data WHERE parent_id=4 AND menu_type='C' AND (component LIKE 'business/analytics/%' OR component LIKE 'business/config/%');

SELECT menu_id,menu_name,parent_id,order_num FROM sys_menu WHERE parent_id IN (@workbench,@supply,@audit,@clubs,@merchants,@users,@funds,@data) ORDER BY parent_id,order_num,menu_id;
