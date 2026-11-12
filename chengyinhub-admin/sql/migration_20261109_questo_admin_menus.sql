-- qx-b6(2026-11-09):Questo 后台新页面的菜单与按钮权限。
-- 只建 sys_menu,不写 sys_role_menu,不授权给任何角色(owner 决定后再分配);超管可直接访问。
-- 页面:视频悬赏(§18 E/F)、主持小费审计(§18 E)、落地译文审校(§20.1)、渠道兑换码(§20.2)、
--       营销分配只读(§19)、有效复报(U08-04 / §27.4)。不改、不删既有菜单。可重复执行。

DROP TEMPORARY TABLE IF EXISTS tmp_cy_questo_menu_guard;
CREATE TEMPORARY TABLE tmp_cy_questo_menu_guard(id INT NOT NULL PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO tmp_cy_questo_menu_guard(id) VALUES(1);
-- 三个父目录必须各恰好一个,否则主键冲突中止本文件。
INSERT INTO tmp_cy_questo_menu_guard(id) SELECT 1 WHERE (SELECT COUNT(*) FROM sys_menu WHERE menu_type='M' AND path='audit-center')<>1;
INSERT INTO tmp_cy_questo_menu_guard(id) SELECT 1 WHERE (SELECT COUNT(*) FROM sys_menu WHERE menu_type='M' AND path='funds-aftercare')<>1;
INSERT INTO tmp_cy_questo_menu_guard(id) SELECT 1 WHERE (SELECT COUNT(*) FROM sys_menu WHERE menu_type='M' AND path='workbench')<>1;

-- 审核与风控:视频悬赏、落地译文审校
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '视频悬赏',(SELECT m.menu_id FROM (SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='audit-center') m),50,'videobounty','business/videobounty/index',1,0,'C','0','0','business:videobounty:review','eye','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:videobounty:review');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '视频悬赏活动配置',(SELECT m.menu_id FROM (SELECT menu_id FROM sys_menu WHERE perms='business:videobounty:review') m),1,'#','',1,0,'F','0','0','business:videobounty:config','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:videobounty:config');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '落地译文审校',(SELECT m.menu_id FROM (SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='audit-center') m),51,'content-landing','business/contentlanding/index',1,0,'C','0','0','business:contentlanding:review','language','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:contentlanding:review');

-- 资金与交易:主持小费、渠道兑换码、营销分配
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '主持小费',(SELECT m.menu_id FROM (SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='funds-aftercare') m),60,'hosttip','business/hosttip/index',1,0,'C','0','0','business:hosttip:audit','money','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:hosttip:audit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '渠道兑换码',(SELECT m.menu_id FROM (SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='funds-aftercare') m),61,'channel-voucher','business/channel/index',1,0,'C','0','0','business:marketing:channel:list','list','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:marketing:channel:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '渠道主体维护',(SELECT m.menu_id FROM (SELECT menu_id FROM sys_menu WHERE perms='business:marketing:channel:list') m),1,'#','',1,0,'F','0','0','business:marketing:channel:manage','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:marketing:channel:manage');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '渠道订单验证',(SELECT m.menu_id FROM (SELECT menu_id FROM sys_menu WHERE perms='business:marketing:channel:list') m),2,'#','',1,0,'F','0','0','business:marketing:channel:verify','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:marketing:channel:verify');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '兑换码发行',(SELECT m.menu_id FROM (SELECT menu_id FROM sys_menu WHERE perms='business:marketing:channel:list') m),3,'#','',1,0,'F','0','0','business:marketing:channel:issue','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:marketing:channel:issue');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '兑换码撤销',(SELECT m.menu_id FROM (SELECT menu_id FROM sys_menu WHERE perms='business:marketing:channel:list') m),4,'#','',1,0,'F','0','0','business:marketing:channel:revoke','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:marketing:channel:revoke');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '渠道对账',(SELECT m.menu_id FROM (SELECT menu_id FROM sys_menu WHERE perms='business:marketing:channel:list') m),5,'#','',1,0,'F','0','0','business:marketing:channel:reconcile','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:marketing:channel:reconcile');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '营销分配',(SELECT m.menu_id FROM (SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='funds-aftercare') m),62,'revenue','business/revenue/index',1,0,'C','0','0','business:revenue:list','chart','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:revenue:list');

-- 工作台:有效复报(沿用读取权限 business:analytics:list,只新增菜单行)
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '有效复报',(SELECT m.menu_id FROM (SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='workbench') m),21,'post-game-outcome','business/analytics/postgame',1,0,'C','0','0','business:analytics:list','chart','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE component='business/analytics/postgame');

INSERT INTO tmp_cy_questo_menu_guard(id) SELECT 1 WHERE (SELECT COUNT(*) FROM sys_menu WHERE perms IN (
  'business:videobounty:review','business:videobounty:config','business:contentlanding:review','business:hosttip:audit',
  'business:marketing:channel:list','business:marketing:channel:manage','business:marketing:channel:verify',
  'business:marketing:channel:issue','business:marketing:channel:revoke','business:marketing:channel:reconcile',
  'business:revenue:list'))<>11;
DROP TEMPORARY TABLE tmp_cy_questo_menu_guard;

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM sys_menu WHERE perms IN (
          'business:videobounty:review','business:videobounty:config','business:contentlanding:review','business:hosttip:audit',
          'business:marketing:channel:list','business:marketing:channel:manage','business:marketing:channel:verify',
          'business:marketing:channel:issue','business:marketing:channel:revoke','business:marketing:channel:reconcile',
          'business:revenue:list')) = 11
  AND (SELECT COUNT(*) FROM sys_menu WHERE component='business/analytics/postgame') = 1
  AND (SELECT COUNT(*) FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id = rm.menu_id
       WHERE m.create_by='migration' AND (m.component IN ('business/videobounty/index','business/contentlanding/index',
             'business/hosttip/index','business/channel/index','business/revenue/index','business/analytics/postgame')
             OR m.perms IN ('business:videobounty:config','business:marketing:channel:manage','business:marketing:channel:verify',
                            'business:marketing:channel:issue','business:marketing:channel:revoke','business:marketing:channel:reconcile'))) = 0 AS assertion_ok;
