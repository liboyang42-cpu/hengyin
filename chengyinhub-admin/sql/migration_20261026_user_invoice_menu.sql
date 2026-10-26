-- Dedicated invoice menu/permissions only. No role grants or issuer/user configuration.
-- runner: requires=transaction
DROP TEMPORARY TABLE IF EXISTS tmp_cy_invoice_menu_guard;
CREATE TEMPORARY TABLE tmp_cy_invoice_menu_guard(id INT NOT NULL PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO tmp_cy_invoice_menu_guard(id) VALUES(1);
INSERT INTO tmp_cy_invoice_menu_guard(id) SELECT 1 WHERE (SELECT COUNT(*) FROM sys_menu WHERE menu_type='M' AND path='funds-aftercare')<>1;
INSERT INTO tmp_cy_invoice_menu_guard(id) SELECT 1 WHERE EXISTS (SELECT perms FROM sys_menu WHERE perms IN ('business:userInvoice:list','business:userInvoice:query','business:userInvoice:taxView','business:userInvoice:process','business:userInvoice:reconcile') GROUP BY perms HAVING COUNT(*)>1);
INSERT INTO tmp_cy_invoice_menu_guard(id) SELECT 1 WHERE EXISTS (SELECT 1 FROM sys_menu WHERE (perms='business:userInvoice:list' OR component='business/userInvoice/index' OR route_name='UserInvoiceProcessing' OR (path='userInvoice' AND parent_id=(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='funds-aftercare'))) AND NOT (perms <=> 'business:userInvoice:list' AND menu_type <=> 'C' AND component <=> 'business/userInvoice/index' AND route_name <=> 'UserInvoiceProcessing' AND path <=> 'userInvoice' AND parent_id <=> (SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='funds-aftercare')));
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '用户发票处理',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='funds-aftercare'),110,'userInvoice','business/userInvoice/index','UserInvoiceProcessing',1,0,'C','0','0','business:userInvoice:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:userInvoice:list');
INSERT INTO tmp_cy_invoice_menu_guard(id) SELECT 1 WHERE EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:userInvoice:query' AND NOT (menu_type <=> 'F' AND parent_id <=> (SELECT menu_id FROM sys_menu WHERE perms='business:userInvoice:list')));
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:userInvoice:query',(SELECT menu_id FROM sys_menu WHERE perms='business:userInvoice:list'),99,'#','',1,0,'F','0','0','business:userInvoice:query','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:userInvoice:query');
INSERT INTO tmp_cy_invoice_menu_guard(id) SELECT 1 WHERE EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:userInvoice:taxView' AND NOT (menu_type <=> 'F' AND parent_id <=> (SELECT menu_id FROM sys_menu WHERE perms='business:userInvoice:list')));
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:userInvoice:taxView',(SELECT menu_id FROM sys_menu WHERE perms='business:userInvoice:list'),99,'#','',1,0,'F','0','0','business:userInvoice:taxView','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:userInvoice:taxView');
INSERT INTO tmp_cy_invoice_menu_guard(id) SELECT 1 WHERE EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:userInvoice:process' AND NOT (menu_type <=> 'F' AND parent_id <=> (SELECT menu_id FROM sys_menu WHERE perms='business:userInvoice:list')));
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:userInvoice:process',(SELECT menu_id FROM sys_menu WHERE perms='business:userInvoice:list'),99,'#','',1,0,'F','0','0','business:userInvoice:process','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:userInvoice:process');
INSERT INTO tmp_cy_invoice_menu_guard(id) SELECT 1 WHERE EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:userInvoice:reconcile' AND NOT (menu_type <=> 'F' AND parent_id <=> (SELECT menu_id FROM sys_menu WHERE perms='business:userInvoice:list')));
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:userInvoice:reconcile',(SELECT menu_id FROM sys_menu WHERE perms='business:userInvoice:list'),99,'#','',1,0,'F','0','0','business:userInvoice:reconcile','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:userInvoice:reconcile');
DROP TEMPORARY TABLE tmp_cy_invoice_menu_guard;
