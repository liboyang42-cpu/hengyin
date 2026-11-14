-- fe-admin-mp(2026-11-14):共享审核状态机（ReviewStateMachine）的后台审核页菜单。
-- 页面:外部游戏审核、外部游戏举报、工坊专业化审核、商家供给退出回执(只读)、IM Webhook 投递/死信(重新投递),
-- 都挂在「审核与风控」(audit-center)下。这五页调用的接口沿用审核工作台权限(business:audit:list 读 / business:audit:review 审),不新增权限串;
-- 菜单 perms 留空,避免与 business:audit:list 唯一性守卫冲突。只建 sys_menu,不写 sys_role_menu,
-- 不授权给任何角色(owner 决定后再分配);超管可直接访问。不改、不删既有菜单。可重复执行。
-- runner: requires=transaction
DROP TEMPORARY TABLE IF EXISTS tmp_cy_review_queue_menu_guard;
CREATE TEMPORARY TABLE tmp_cy_review_queue_menu_guard(id INT NOT NULL PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO tmp_cy_review_queue_menu_guard(id) VALUES(1);
-- 父目录必须恰好一个;本文件的五个组件路径不得已有重复,否则主键冲突中止本文件。
INSERT INTO tmp_cy_review_queue_menu_guard(id) SELECT 1 WHERE (SELECT COUNT(*) FROM sys_menu WHERE menu_type='M' AND path='audit-center')<>1;
INSERT INTO tmp_cy_review_queue_menu_guard(id) SELECT 1 WHERE EXISTS (SELECT component FROM sys_menu WHERE component IN ('business/externalgame/index','business/externalgame/reports','business/workshopreview/index','business/offerwithdrawal/index','business/imwebhook/index') GROUP BY component HAVING COUNT(*)>1);

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '外部游戏审核',(SELECT m.menu_id FROM (SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='audit-center') m),52,'external-game','business/externalgame/index',1,0,'C','0','0','','eye-open','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE component='business/externalgame/index');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '外部游戏举报',(SELECT m.menu_id FROM (SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='audit-center') m),53,'external-game-report','business/externalgame/reports',1,0,'C','0','0','','bug','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE component='business/externalgame/reports');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '工坊专业化审核',(SELECT m.menu_id FROM (SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='audit-center') m),54,'workshop-review','business/workshopreview/index',1,0,'C','0','0','','build','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE component='business/workshopreview/index');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '商家供给退出',(SELECT m.menu_id FROM (SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='audit-center') m),55,'offer-withdrawal','business/offerwithdrawal/index',1,0,'C','0','0','','log','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE component='business/offerwithdrawal/index');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'Webhook 投递',(SELECT m.menu_id FROM (SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='audit-center') m),56,'im-webhook','business/imwebhook/index',1,0,'C','0','0','','message','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE component='business/imwebhook/index');

-- 回读:五条 C 菜单各恰好一条且都在 audit-center 下。
INSERT INTO tmp_cy_review_queue_menu_guard(id) SELECT 1 WHERE (SELECT COUNT(*) FROM sys_menu c JOIN sys_menu p ON p.menu_id=c.parent_id
  WHERE p.menu_type='M' AND p.path='audit-center' AND c.menu_type='C'
    AND c.component IN ('business/externalgame/index','business/externalgame/reports','business/workshopreview/index','business/offerwithdrawal/index','business/imwebhook/index'))<>5;
DROP TEMPORARY TABLE tmp_cy_review_queue_menu_guard;
