-- Actual MEDIA_ASSET controls reuse the audit workbench. Create only missing permissions; grant none.
-- runner: requires=transaction
DROP TEMPORARY TABLE IF EXISTS tmp_cy_media_menu_guard;
CREATE TEMPORARY TABLE tmp_cy_media_menu_guard(id INT NOT NULL PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO tmp_cy_media_menu_guard(id) VALUES(1);
INSERT INTO tmp_cy_media_menu_guard(id) SELECT 1 WHERE (SELECT COUNT(*) FROM sys_menu WHERE perms='business:audit:list' AND menu_type='C')<>1;
INSERT INTO tmp_cy_media_menu_guard(id) SELECT 1 WHERE EXISTS (SELECT perms FROM sys_menu WHERE perms IN ('business:mediaReview:query','business:mediaReview:approve','business:mediaReview:reject') GROUP BY perms HAVING COUNT(*)>1);
INSERT INTO tmp_cy_media_menu_guard(id) SELECT 1 WHERE EXISTS (SELECT menu_id FROM sys_menu WHERE perms IN ('business:mediaReview:query','business:mediaReview:approve','business:mediaReview:reject') AND menu_type<>'F');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:mediaReview:query',(SELECT menu_id FROM sys_menu WHERE perms='business:audit:list'),99,'#','',1,0,'F','0','0','business:mediaReview:query','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:mediaReview:query');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:mediaReview:approve',(SELECT menu_id FROM sys_menu WHERE perms='business:audit:list'),99,'#','',1,0,'F','0','0','business:mediaReview:approve','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:mediaReview:approve');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:mediaReview:reject',(SELECT menu_id FROM sys_menu WHERE perms='business:audit:list'),99,'#','',1,0,'F','0','0','business:mediaReview:reject','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:mediaReview:reject');
INSERT INTO tmp_cy_media_menu_guard(id) SELECT 1 WHERE (SELECT COUNT(*) FROM sys_menu WHERE menu_type='F' AND perms IN ('business:mediaReview:query','business:mediaReview:approve','business:mediaReview:reject'))<>3;
DROP TEMPORARY TABLE tmp_cy_media_menu_guard;
