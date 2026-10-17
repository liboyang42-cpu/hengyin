-- PRE_DEPLOY: missing canonical menus only; no role grants or existing config overwrite.
-- Provenance: ci/prelaunch-seed-provenance.json (source and actual scratch readback).
-- Run with --transaction. Duplicate-key temp guards abort/rollback; no historical rows normalized.
DROP TEMPORARY TABLE IF EXISTS tmp_cy_seed_guard;
CREATE TEMPORARY TABLE tmp_cy_seed_guard(id INT PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO tmp_cy_seed_guard(id) VALUES(1);
INSERT INTO tmp_cy_seed_guard(id) SELECT 1 WHERE EXISTS (SELECT path FROM sys_menu WHERE menu_type='M' AND path IN ('workbench','supply','audit-center','clubs-roam','roam','merchants-coop','users-support','funds-aftercare','rules','system-ops','system','monitor','tool','log') GROUP BY path HAVING COUNT(*)>1);
INSERT INTO tmp_cy_seed_guard(id) SELECT 1 WHERE EXISTS (SELECT perms FROM sys_menu WHERE perms IN ('business:activity:list','business:membertemplate:list','business:ticket:list','business:merchant:list','business:registrationmerchant:list','business:square:list','business:comment:list','business:betagame:list','system:category:list','system:banner:list','business:topic:list','business:memberwithdrawal:list','business:club:list','business:audit:list','business:cashwithdraw:list','business:officialevent:list','business:roamPoi:list','business:verifyEvidence:list','business:clubRun:list','business:ailog:list','business:chapterOffer:list','business:userpermission:list','business:settlementrule:list','business:refund:list','business:verificationReview:list','business:growth:list','business:world:list','business:creator:list','business:crm:list','business:settlement:list','business:merchantlead:list','business:roamAiContent:list','business:analytics:list','business:recommendation:list','business:payablebatch:list','business:coopcomplaint:list','business:im:list','business:presetStyle:list','business:presetRoute:list','system:user:list','system:role:list','system:menu:list','system:dept:list','system:post:list','system:dict:list','system:config:list','system:notice:list','monitor:online:list','monitor:job:list','monitor:druid:list','monitor:server:list','monitor:cache:list','tool:build:list','tool:gen:list','tool:swagger:list','monitor:operlog:list','monitor:logininfor:list','business:product:list','business:infomation:list','business:coupon:list','business:order:list','business:gift:list','business:member:list','business:pointsetting:list','business:registration:list','business:merchantNpc:list','business:memberbalancedetail:list','business:activity:add','business:activity:edit','business:activity:export','business:activity:query','business:activity:remove','business:membertemplate:add','business:membertemplate:edit','business:membertemplate:export','business:membertemplate:query','business:membertemplate:remove','business:ticket:add','business:ticket:edit','business:ticket:export','business:ticket:query','business:ticket:remove','business:merchant:add','business:merchant:edit','business:merchant:export','business:merchant:query','business:merchant:remove','business:registrationmerchant:add','business:registrationmerchant:edit','business:registrationmerchant:export','business:registrationmerchant:query','business:registrationmerchant:remove','business:square:add','business:square:edit','business:square:export','business:square:query','business:square:remove','business:comment:add','business:comment:edit','business:comment:export','business:comment:query','business:comment:remove','business:betagame:add','business:betagame:edit','business:betagame:export','business:betagame:query','business:betagame:remove','system:category:add','system:category:edit','system:category:export','system:category:query','system:category:remove','system:banner:add','system:banner:edit','system:banner:export','system:banner:query','system:banner:remove','business:topic:add','business:topic:copy','business:topic:edit','business:topic:export','business:topic:query','business:topic:remove','business:memberwithdrawal:add','business:memberwithdrawal:audit','business:memberwithdrawal:edit','business:memberwithdrawal:export','business:memberwithdrawal:query','business:memberwithdrawal:remove','business:memberwithdrawal:viewpii','business:club:add','business:club:audit','business:club:edit','business:club:export','business:club:query','business:club:remove','business:audit:review','business:cashwithdraw:freeze','business:cashwithdraw:manage','business:cashwithdraw:query','business:officialevent:add','business:officialevent:audit','business:officialevent:authorize','business:officialevent:edit','business:officialevent:notify','business:officialevent:query','business:officialevent:settle','business:roamPoi:edit','business:ailog:query','business:chapterOffer:edit','business:userpermission:edit','business:settlementrule:edit','business:refund:review','business:verificationReview:handle','business:growth:edit','business:world:edit','business:creator:review','business:crm:aggregate','business:settlement:confirm','business:settlement:generate','business:merchantlead:add','business:merchantlead:edit','business:merchantlead:query','business:merchantlead:remove','business:roamAiContent:add','business:roamAiContent:audit','business:roamAiContent:edit','business:roamAiContent:query','business:roamAiContent:remove','business:payablebatch:edit','business:payablebatch:freeze','business:coopcomplaint:handle','business:coopcomplaint:query','business:im:block','business:im:send','business:presetStyle:add','business:presetStyle:edit','business:presetStyle:query','business:presetStyle:remove','business:presetRoute:add','business:presetRoute:edit','business:presetRoute:query','business:presetRoute:remove','system:user:add','system:user:edit','system:user:export','system:user:import','system:user:query','system:user:remove','system:user:resetPwd','system:role:add','system:role:edit','system:role:export','system:role:query','system:role:remove','system:menu:add','system:menu:edit','system:menu:query','system:menu:remove','system:dept:add','system:dept:edit','system:dept:query','system:dept:remove','system:post:add','system:post:edit','system:post:export','system:post:query','system:post:remove','system:dict:add','system:dict:edit','system:dict:export','system:dict:query','system:dict:remove','system:config:add','system:config:edit','system:config:export','system:config:query','system:config:remove','system:notice:add','system:notice:edit','system:notice:query','system:notice:remove','monitor:online:forceLogout','monitor:operlog:export','monitor:operlog:remove','monitor:logininfor:export','monitor:logininfor:remove','monitor:logininfor:unlock','business:product:add','business:product:edit','business:product:export','business:product:query','business:product:remove','business:infomation:add','business:infomation:edit','business:infomation:export','business:infomation:query','business:infomation:remove','business:coupon:add','business:coupon:edit','business:coupon:export','business:coupon:query','business:coupon:remove','business:order:add','business:order:edit','business:order:export','business:order:query','business:order:remove','business:gift:add','business:gift:edit','business:gift:export','business:gift:query','business:gift:remove','business:member:add','business:member:edit','business:member:export','business:member:query','business:member:remove','business:pointsetting:add','business:pointsetting:edit','business:pointsetting:export','business:pointsetting:query','business:pointsetting:remove','business:registration:add','business:registration:edit','business:registration:export','business:registration:query','business:registration:remove','business:registration:reverseRedeem','business:merchantNpc:add','business:merchantNpc:edit','business:merchantNpc:query','business:merchantNpc:remove','business:memberbalancedetail:add','business:memberbalancedetail:edit','business:memberbalancedetail:export','business:memberbalancedetail:query','business:memberbalancedetail:remove') GROUP BY perms HAVING COUNT(*)>1);
INSERT INTO tmp_cy_seed_guard(id) SELECT 1 WHERE EXISTS (SELECT menu_id FROM sys_menu WHERE perms IN ('business:activity:list','business:membertemplate:list','business:ticket:list','business:merchant:list','business:registrationmerchant:list','business:square:list','business:comment:list','business:betagame:list','system:category:list','system:banner:list','business:topic:list','business:memberwithdrawal:list','business:club:list','business:audit:list','business:cashwithdraw:list','business:officialevent:list','business:roamPoi:list','business:verifyEvidence:list','business:clubRun:list','business:ailog:list','business:chapterOffer:list','business:userpermission:list','business:settlementrule:list','business:refund:list','business:verificationReview:list','business:growth:list','business:world:list','business:creator:list','business:crm:list','business:settlement:list','business:merchantlead:list','business:roamAiContent:list','business:analytics:list','business:recommendation:list','business:payablebatch:list','business:coopcomplaint:list','business:im:list','business:presetStyle:list','business:presetRoute:list','system:user:list','system:role:list','system:menu:list','system:dept:list','system:post:list','system:dict:list','system:config:list','system:notice:list','monitor:online:list','monitor:job:list','monitor:druid:list','monitor:server:list','monitor:cache:list','tool:build:list','tool:gen:list','tool:swagger:list','monitor:operlog:list','monitor:logininfor:list','business:product:list','business:infomation:list','business:coupon:list','business:order:list','business:gift:list','business:member:list','business:pointsetting:list','business:registration:list','business:merchantNpc:list','business:memberbalancedetail:list') AND menu_type<>'C');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '工作台',0,1,'workbench',NULL,1,0,'M','0','0','','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_type='M' AND path='workbench');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '供给与发布',0,2,'supply',NULL,1,0,'M','0','0','','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_type='M' AND path='supply');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '审核与风控',0,3,'audit-center',NULL,1,0,'M','0','0','','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_type='M' AND path='audit-center');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '俱乐部',0,4,'clubs-roam',NULL,1,0,'M','0','0','','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_type='M' AND path='clubs-roam');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '漫游',0,5,'roam',NULL,1,0,'M','0','0','','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_type='M' AND path='roam');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '商家与合作',0,6,'merchants-coop',NULL,1,0,'M','0','0','','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_type='M' AND path='merchants-coop');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '用户与客服',0,7,'users-support',NULL,1,0,'M','0','0','','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_type='M' AND path='users-support');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '资金与交易',0,8,'funds-aftercare',NULL,1,0,'M','0','0','','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_type='M' AND path='funds-aftercare');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '玩法与规则',0,9,'rules',NULL,1,0,'M','0','0','','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_type='M' AND path='rules');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '系统与运维',0,10,'system-ops',NULL,1,0,'M','0','0','','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_type='M' AND path='system-ops');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '系统管理',(SELECT menu_id FROM sys_menu WHERE path='system-ops' AND menu_type='M'),10,'system',NULL,1,0,'M','0','0','','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_type='M' AND path='system');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '系统监控',(SELECT menu_id FROM sys_menu WHERE path='system-ops' AND menu_type='M'),20,'monitor',NULL,1,0,'M','0','0','','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_type='M' AND path='monitor');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '系统工具',(SELECT menu_id FROM sys_menu WHERE path='system-ops' AND menu_type='M'),30,'tool',NULL,1,0,'M','0','0','','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_type='M' AND path='tool');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '日志管理',(SELECT menu_id FROM sys_menu WHERE path='system' AND menu_type='M'),4,'log',NULL,1,0,'M','0','0','','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_type='M' AND path='log');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '活动管理',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='supply'),60,'activity','business/activity/index',1,0,'C','0','0','business:activity:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:activity:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '模板中心',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='supply'),50,'membertemplate','business/membertemplate/index',1,0,'C','0','0','business:membertemplate:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:membertemplate:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '票务管理',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='supply'),70,'ticket','business/ticket/index',1,0,'C','0','0','business:ticket:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:ticket:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '商家准入',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='merchants-coop'),10,'merchant','business/merchant/index',1,0,'C','0','0','business:merchant:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:merchant:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '商家报名与调配',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='merchants-coop'),30,'registrationmerchant-dispatch','business/registrationmerchant/dispatch',1,0,'C','0','0','business:registrationmerchant:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:registrationmerchant:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '创意广场',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='audit-center'),50,'square','business/square/index',1,0,'C','0','0','business:square:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:square:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '评论管理',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='audit-center'),60,'comment','business/comment/index',1,0,'C','0','0','business:comment:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:comment:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '游戏管理',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='rules'),40,'betagame','business/betagame/index',1,0,'C','0','0','business:betagame:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:betagame:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '分类管理',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='supply'),130,'category','system/category/index',1,0,'C','0','0','system:category:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:category:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '轮播图',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='supply'),140,'banner','system/banner/index',1,0,'C','0','0','system:banner:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:banner:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '官方主题',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='supply'),10,'official-topic','business/topic/index',1,0,'C','0','0','business:topic:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:topic:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '银行卡提现审核',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='funds-aftercare'),30,'memberwithdrawal','business/memberwithdrawal/index',1,0,'C','0','0','business:memberwithdrawal:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:memberwithdrawal:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '俱乐部工作台',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='clubs-roam'),10,'club','business/club/index',1,0,'C','0','0','business:club:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:club:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '审核工作台',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='audit-center'),20,'audit','business/audit/index',1,0,'C','0','0','business:audit:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:audit:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '微信提现回补',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='funds-aftercare'),20,'cashwithdraw','business/cashwithdraw/index',1,0,'C','0','0','business:cashwithdraw:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:cashwithdraw:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '官方活动',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='supply'),30,'officialevent','business/officialevent/index',1,0,'C','0','0','business:officialevent:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:officialevent:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'POI治理',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='roam'),10,'roampoi','business/roampoi/index',1,0,'C','0','0','business:roamPoi:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:roamPoi:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '核销证据',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='audit-center'),10,'verifyevidence','business/verifyevidence/index',1,0,'C','0','0','business:verifyEvidence:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:verifyEvidence:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '带队场次',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='clubs-roam'),20,'clubrun','business/clubrun/index',1,0,'C','0','0','business:clubRun:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:clubRun:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'AI 调用审计',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='audit-center'),40,'ailog','business/ailog/index',1,0,'C','0','0','business:ailog:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:ailog:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '三档承接审核',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='merchants-coop'),20,'chapteroffer','business/chapteroffer/index',1,0,'C','0','0','business:chapterOffer:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:chapterOffer:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '角色配额',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='rules'),30,'userpermission','business/userpermission/index',1,0,'C','0','0','business:userpermission:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:userpermission:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '结算规则',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='funds-aftercare'),80,'settlementrule','business/settlementrule/index',1,0,'C','0','0','business:settlementrule:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:settlementrule:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '退款处理台',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='funds-aftercare'),50,'refund','business/finance/refund/index',1,0,'C','0','0','business:refund:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:refund:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '核销异常复核',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='audit-center'),60,'verification-review','business/verificationReview/index',1,0,'C','0','0','business:verificationReview:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:verificationReview:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '成长任务配置',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='rules'),10,'growth','business/growth/index',1,0,'C','0','0','business:growth:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:growth:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'IP世界观',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='supply'),90,'world','business/world/index',1,0,'C','0','0','business:world:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:world:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '创作者准入',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='audit-center'),30,'creator','business/risk/creator-admission/index',1,0,'C','0','0','business:creator:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:creator:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '商家CRM',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='merchants-coop'),60,'crm','business/crm/index',1,0,'C','0','0','business:crm:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:crm:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '结算管理',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='funds-aftercare'),70,'settlement','business/settlement/index',1,0,'C','0','0','business:settlement:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:settlement:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '商家目录',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='merchants-coop'),40,'merchantlead','business/merchantlead/index',1,0,'C','0','0','business:merchantlead:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:merchantlead:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '漫游陪伴内容审核',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='roam'),2,'ai-content','business/roamaicontent/index',1,0,'C','0','0','business:roamAiContent:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:roamAiContent:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '增长漏斗',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='workbench'),10,'analytics','business/analytics/index',1,0,'C','0','0','business:analytics:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:analytics:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '推荐配置',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='supply'),80,'recommendation','business/recommendation/index',1,0,'C','0','0','business:recommendation:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:recommendation:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '应付月结',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='funds-aftercare'),60,'payablebatch','business/finance/payable-batch/index',1,0,'C','0','0','business:payablebatch:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:payablebatch:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '客诉工单',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='merchants-coop'),50,'coopcomplaint','business/coopcomplaint/index',1,0,'C','0','0','business:coopcomplaint:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:coopcomplaint:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '客服会话',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='users-support'),20,'im','business/im/index',1,0,'C','0','0','business:im:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:im:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '主题风格',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='supply'),100,'style','business/preset/style/index',1,0,'C','0','0','business:presetStyle:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:presetStyle:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '城市路线',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='supply'),110,'route','business/preset/route/index',1,0,'C','0','0','business:presetRoute:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:presetRoute:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '用户管理',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='system'),1,'user','system/user/index',1,0,'C','0','0','system:user:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:user:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '角色管理',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='system'),2,'role','system/role/index',1,0,'C','0','0','system:role:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:role:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '菜单管理',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='system'),3,'menu','system/menu/index',1,0,'C','0','0','system:menu:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:menu:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '部门管理',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='system'),4,'dept','system/dept/index',1,0,'C','0','0','system:dept:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:dept:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '岗位管理',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='system'),5,'post','system/post/index',1,0,'C','0','0','system:post:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:post:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '字典管理',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='system'),6,'dict','system/dict/index',1,0,'C','0','0','system:dict:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:dict:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '参数设置',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='system'),7,'config','system/config/index',1,0,'C','0','0','system:config:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:config:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '通知公告',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='system'),8,'notice','system/notice/index',1,0,'C','0','0','system:notice:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:notice:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '在线用户',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='monitor'),1,'online','monitor/online/index',1,0,'C','0','0','monitor:online:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='monitor:online:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '定时任务',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='monitor'),2,'job','monitor/job/index',1,0,'C','0','0','monitor:job:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='monitor:job:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '数据监控',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='monitor'),3,'druid','monitor/druid/index',1,0,'C','0','0','monitor:druid:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='monitor:druid:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '服务监控',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='monitor'),4,'server','monitor/server/index',1,0,'C','0','0','monitor:server:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='monitor:server:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '缓存监控',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='monitor'),5,'cache','monitor/cache/index',1,0,'C','0','0','monitor:cache:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='monitor:cache:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '表单构建',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='tool'),1,'build','tool/build/index',1,0,'C','0','0','tool:build:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='tool:build:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '代码生成',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='tool'),2,'gen','tool/gen/index',1,0,'C','0','0','tool:gen:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='tool:gen:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '系统接口',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='tool'),3,'swagger','tool/swagger/index',1,0,'C','0','0','tool:swagger:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='tool:swagger:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '操作日志',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='log'),1,'operlog','monitor/operlog/index',1,0,'C','0','0','monitor:operlog:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='monitor:operlog:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '登录日志',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='log'),2,'logininfor','monitor/logininfor/index',1,0,'C','0','0','monitor:logininfor:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='monitor:logininfor:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '积分商城商品',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='funds-aftercare'),130,'product','business/config/points-product/index',1,0,'C','0','0','business:product:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:product:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '玩法说明',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='supply'),120,'infomation','business/content/guide/index',1,0,'C','0','0','business:infomation:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:infomation:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '优惠券目录',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='supply'),150,'coupon','business/coupon/index',1,0,'C','0','0','business:coupon:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:coupon:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '订单查询',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='funds-aftercare'),10,'order','business/finance/order/index',1,0,'C','0','0','business:order:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:order:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '礼物目录与定价',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='funds-aftercare'),120,'gift','business/gift-catalog/index',1,0,'C','0','0','business:gift:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:gift:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '用户管理',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='users-support'),10,'member','business/member/index',1,0,'C','0','0','business:member:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:member:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '积分配置',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='rules'),20,'pointsetting','business/points/index',1,0,'C','0','0','business:pointsetting:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:pointsetting:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '报名台账',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='funds-aftercare'),100,'registration','business/registration/index',1,0,'C','0','0','business:registration:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:registration:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '门店AI形象',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='system'),91,'merchantnpc','business/merchantnpc/index',1,0,'C','0','0','business:merchantNpc:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:merchantNpc:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '资金流水',(SELECT menu_id FROM sys_menu WHERE menu_type='M' AND path='funds-aftercare'),40,'memberbalancedetail','business/finance/ledger',1,0,'C','0','0','business:memberbalancedetail:list','component','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:memberbalancedetail:list');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:activity:add',(SELECT menu_id FROM sys_menu WHERE perms='business:activity:list'),99,'#','',1,0,'F','0','0','business:activity:add','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:activity:add');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:activity:edit',(SELECT menu_id FROM sys_menu WHERE perms='business:activity:list'),99,'#','',1,0,'F','0','0','business:activity:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:activity:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:activity:export',(SELECT menu_id FROM sys_menu WHERE perms='business:activity:list'),99,'#','',1,0,'F','0','0','business:activity:export','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:activity:export');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:activity:query',(SELECT menu_id FROM sys_menu WHERE perms='business:activity:list'),99,'#','',1,0,'F','0','0','business:activity:query','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:activity:query');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:activity:remove',(SELECT menu_id FROM sys_menu WHERE perms='business:activity:list'),99,'#','',1,0,'F','0','0','business:activity:remove','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:activity:remove');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:membertemplate:add',(SELECT menu_id FROM sys_menu WHERE perms='business:membertemplate:list'),99,'#','',1,0,'F','0','0','business:membertemplate:add','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:membertemplate:add');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:membertemplate:edit',(SELECT menu_id FROM sys_menu WHERE perms='business:membertemplate:list'),99,'#','',1,0,'F','0','0','business:membertemplate:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:membertemplate:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:membertemplate:export',(SELECT menu_id FROM sys_menu WHERE perms='business:membertemplate:list'),99,'#','',1,0,'F','0','0','business:membertemplate:export','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:membertemplate:export');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:membertemplate:query',(SELECT menu_id FROM sys_menu WHERE perms='business:membertemplate:list'),99,'#','',1,0,'F','0','0','business:membertemplate:query','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:membertemplate:query');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:membertemplate:remove',(SELECT menu_id FROM sys_menu WHERE perms='business:membertemplate:list'),99,'#','',1,0,'F','0','0','business:membertemplate:remove','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:membertemplate:remove');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:ticket:add',(SELECT menu_id FROM sys_menu WHERE perms='business:ticket:list'),99,'#','',1,0,'F','0','0','business:ticket:add','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:ticket:add');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:ticket:edit',(SELECT menu_id FROM sys_menu WHERE perms='business:ticket:list'),99,'#','',1,0,'F','0','0','business:ticket:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:ticket:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:ticket:export',(SELECT menu_id FROM sys_menu WHERE perms='business:ticket:list'),99,'#','',1,0,'F','0','0','business:ticket:export','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:ticket:export');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:ticket:query',(SELECT menu_id FROM sys_menu WHERE perms='business:ticket:list'),99,'#','',1,0,'F','0','0','business:ticket:query','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:ticket:query');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:ticket:remove',(SELECT menu_id FROM sys_menu WHERE perms='business:ticket:list'),99,'#','',1,0,'F','0','0','business:ticket:remove','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:ticket:remove');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:merchant:add',(SELECT menu_id FROM sys_menu WHERE perms='business:merchant:list'),99,'#','',1,0,'F','0','0','business:merchant:add','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:merchant:add');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:merchant:edit',(SELECT menu_id FROM sys_menu WHERE perms='business:merchant:list'),99,'#','',1,0,'F','0','0','business:merchant:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:merchant:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:merchant:export',(SELECT menu_id FROM sys_menu WHERE perms='business:merchant:list'),99,'#','',1,0,'F','0','0','business:merchant:export','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:merchant:export');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:merchant:query',(SELECT menu_id FROM sys_menu WHERE perms='business:merchant:list'),99,'#','',1,0,'F','0','0','business:merchant:query','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:merchant:query');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:merchant:remove',(SELECT menu_id FROM sys_menu WHERE perms='business:merchant:list'),99,'#','',1,0,'F','0','0','business:merchant:remove','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:merchant:remove');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:registrationmerchant:add',(SELECT menu_id FROM sys_menu WHERE perms='business:registrationmerchant:list'),99,'#','',1,0,'F','0','0','business:registrationmerchant:add','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:registrationmerchant:add');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:registrationmerchant:edit',(SELECT menu_id FROM sys_menu WHERE perms='business:registrationmerchant:list'),99,'#','',1,0,'F','0','0','business:registrationmerchant:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:registrationmerchant:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:registrationmerchant:export',(SELECT menu_id FROM sys_menu WHERE perms='business:registrationmerchant:list'),99,'#','',1,0,'F','0','0','business:registrationmerchant:export','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:registrationmerchant:export');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:registrationmerchant:query',(SELECT menu_id FROM sys_menu WHERE perms='business:registrationmerchant:list'),99,'#','',1,0,'F','0','0','business:registrationmerchant:query','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:registrationmerchant:query');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:registrationmerchant:remove',(SELECT menu_id FROM sys_menu WHERE perms='business:registrationmerchant:list'),99,'#','',1,0,'F','0','0','business:registrationmerchant:remove','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:registrationmerchant:remove');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:square:add',(SELECT menu_id FROM sys_menu WHERE perms='business:square:list'),99,'#','',1,0,'F','0','0','business:square:add','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:square:add');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:square:edit',(SELECT menu_id FROM sys_menu WHERE perms='business:square:list'),99,'#','',1,0,'F','0','0','business:square:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:square:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:square:export',(SELECT menu_id FROM sys_menu WHERE perms='business:square:list'),99,'#','',1,0,'F','0','0','business:square:export','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:square:export');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:square:query',(SELECT menu_id FROM sys_menu WHERE perms='business:square:list'),99,'#','',1,0,'F','0','0','business:square:query','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:square:query');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:square:remove',(SELECT menu_id FROM sys_menu WHERE perms='business:square:list'),99,'#','',1,0,'F','0','0','business:square:remove','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:square:remove');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:comment:add',(SELECT menu_id FROM sys_menu WHERE perms='business:comment:list'),99,'#','',1,0,'F','0','0','business:comment:add','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:comment:add');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:comment:edit',(SELECT menu_id FROM sys_menu WHERE perms='business:comment:list'),99,'#','',1,0,'F','0','0','business:comment:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:comment:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:comment:export',(SELECT menu_id FROM sys_menu WHERE perms='business:comment:list'),99,'#','',1,0,'F','0','0','business:comment:export','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:comment:export');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:comment:query',(SELECT menu_id FROM sys_menu WHERE perms='business:comment:list'),99,'#','',1,0,'F','0','0','business:comment:query','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:comment:query');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:comment:remove',(SELECT menu_id FROM sys_menu WHERE perms='business:comment:list'),99,'#','',1,0,'F','0','0','business:comment:remove','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:comment:remove');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:betagame:add',(SELECT menu_id FROM sys_menu WHERE perms='business:betagame:list'),99,'#','',1,0,'F','0','0','business:betagame:add','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:betagame:add');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:betagame:edit',(SELECT menu_id FROM sys_menu WHERE perms='business:betagame:list'),99,'#','',1,0,'F','0','0','business:betagame:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:betagame:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:betagame:export',(SELECT menu_id FROM sys_menu WHERE perms='business:betagame:list'),99,'#','',1,0,'F','0','0','business:betagame:export','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:betagame:export');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:betagame:query',(SELECT menu_id FROM sys_menu WHERE perms='business:betagame:list'),99,'#','',1,0,'F','0','0','business:betagame:query','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:betagame:query');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:betagame:remove',(SELECT menu_id FROM sys_menu WHERE perms='business:betagame:list'),99,'#','',1,0,'F','0','0','business:betagame:remove','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:betagame:remove');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:category:add',(SELECT menu_id FROM sys_menu WHERE perms='system:category:list'),99,'#','',1,0,'F','0','0','system:category:add','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:category:add');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:category:edit',(SELECT menu_id FROM sys_menu WHERE perms='system:category:list'),99,'#','',1,0,'F','0','0','system:category:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:category:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:category:export',(SELECT menu_id FROM sys_menu WHERE perms='system:category:list'),99,'#','',1,0,'F','0','0','system:category:export','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:category:export');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:category:query',(SELECT menu_id FROM sys_menu WHERE perms='system:category:list'),99,'#','',1,0,'F','0','0','system:category:query','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:category:query');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:category:remove',(SELECT menu_id FROM sys_menu WHERE perms='system:category:list'),99,'#','',1,0,'F','0','0','system:category:remove','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:category:remove');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:banner:add',(SELECT menu_id FROM sys_menu WHERE perms='system:banner:list'),99,'#','',1,0,'F','0','0','system:banner:add','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:banner:add');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:banner:edit',(SELECT menu_id FROM sys_menu WHERE perms='system:banner:list'),99,'#','',1,0,'F','0','0','system:banner:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:banner:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:banner:export',(SELECT menu_id FROM sys_menu WHERE perms='system:banner:list'),99,'#','',1,0,'F','0','0','system:banner:export','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:banner:export');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:banner:query',(SELECT menu_id FROM sys_menu WHERE perms='system:banner:list'),99,'#','',1,0,'F','0','0','system:banner:query','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:banner:query');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:banner:remove',(SELECT menu_id FROM sys_menu WHERE perms='system:banner:list'),99,'#','',1,0,'F','0','0','system:banner:remove','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:banner:remove');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:topic:add',(SELECT menu_id FROM sys_menu WHERE perms='business:topic:list'),99,'#','',1,0,'F','0','0','business:topic:add','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:topic:add');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:topic:copy',(SELECT menu_id FROM sys_menu WHERE perms='business:topic:list'),99,'#','',1,0,'F','0','0','business:topic:copy','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:topic:copy');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:topic:edit',(SELECT menu_id FROM sys_menu WHERE perms='business:topic:list'),99,'#','',1,0,'F','0','0','business:topic:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:topic:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:topic:export',(SELECT menu_id FROM sys_menu WHERE perms='business:topic:list'),99,'#','',1,0,'F','0','0','business:topic:export','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:topic:export');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:topic:query',(SELECT menu_id FROM sys_menu WHERE perms='business:topic:list'),99,'#','',1,0,'F','0','0','business:topic:query','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:topic:query');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:topic:remove',(SELECT menu_id FROM sys_menu WHERE perms='business:topic:list'),99,'#','',1,0,'F','0','0','business:topic:remove','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:topic:remove');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:memberwithdrawal:add',(SELECT menu_id FROM sys_menu WHERE perms='business:memberwithdrawal:list'),99,'#','',1,0,'F','0','0','business:memberwithdrawal:add','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:memberwithdrawal:add');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:memberwithdrawal:audit',(SELECT menu_id FROM sys_menu WHERE perms='business:memberwithdrawal:list'),99,'#','',1,0,'F','0','0','business:memberwithdrawal:audit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:memberwithdrawal:audit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:memberwithdrawal:edit',(SELECT menu_id FROM sys_menu WHERE perms='business:memberwithdrawal:list'),99,'#','',1,0,'F','0','0','business:memberwithdrawal:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:memberwithdrawal:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:memberwithdrawal:export',(SELECT menu_id FROM sys_menu WHERE perms='business:memberwithdrawal:list'),99,'#','',1,0,'F','0','0','business:memberwithdrawal:export','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:memberwithdrawal:export');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:memberwithdrawal:query',(SELECT menu_id FROM sys_menu WHERE perms='business:memberwithdrawal:list'),99,'#','',1,0,'F','0','0','business:memberwithdrawal:query','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:memberwithdrawal:query');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:memberwithdrawal:remove',(SELECT menu_id FROM sys_menu WHERE perms='business:memberwithdrawal:list'),99,'#','',1,0,'F','0','0','business:memberwithdrawal:remove','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:memberwithdrawal:remove');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:memberwithdrawal:viewpii',(SELECT menu_id FROM sys_menu WHERE perms='business:memberwithdrawal:list'),99,'#','',1,0,'F','0','0','business:memberwithdrawal:viewpii','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:memberwithdrawal:viewpii');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:club:add',(SELECT menu_id FROM sys_menu WHERE perms='business:club:list'),99,'#','',1,0,'F','0','0','business:club:add','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:club:add');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:club:audit',(SELECT menu_id FROM sys_menu WHERE perms='business:club:list'),99,'#','',1,0,'F','0','0','business:club:audit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:club:audit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:club:edit',(SELECT menu_id FROM sys_menu WHERE perms='business:club:list'),99,'#','',1,0,'F','0','0','business:club:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:club:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:club:export',(SELECT menu_id FROM sys_menu WHERE perms='business:club:list'),99,'#','',1,0,'F','0','0','business:club:export','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:club:export');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:club:query',(SELECT menu_id FROM sys_menu WHERE perms='business:club:list'),99,'#','',1,0,'F','0','0','business:club:query','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:club:query');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:club:remove',(SELECT menu_id FROM sys_menu WHERE perms='business:club:list'),99,'#','',1,0,'F','0','0','business:club:remove','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:club:remove');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:audit:review',(SELECT menu_id FROM sys_menu WHERE perms='business:audit:list'),99,'#','',1,0,'F','0','0','business:audit:review','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:audit:review');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:cashwithdraw:freeze',(SELECT menu_id FROM sys_menu WHERE perms='business:cashwithdraw:list'),99,'#','',1,0,'F','0','0','business:cashwithdraw:freeze','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:cashwithdraw:freeze');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:cashwithdraw:manage',(SELECT menu_id FROM sys_menu WHERE perms='business:cashwithdraw:list'),99,'#','',1,0,'F','0','0','business:cashwithdraw:manage','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:cashwithdraw:manage');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:cashwithdraw:query',(SELECT menu_id FROM sys_menu WHERE perms='business:cashwithdraw:list'),99,'#','',1,0,'F','0','0','business:cashwithdraw:query','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:cashwithdraw:query');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:officialevent:add',(SELECT menu_id FROM sys_menu WHERE perms='business:officialevent:list'),99,'#','',1,0,'F','0','0','business:officialevent:add','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:officialevent:add');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:officialevent:audit',(SELECT menu_id FROM sys_menu WHERE perms='business:officialevent:list'),99,'#','',1,0,'F','0','0','business:officialevent:audit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:officialevent:audit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:officialevent:authorize',(SELECT menu_id FROM sys_menu WHERE perms='business:officialevent:list'),99,'#','',1,0,'F','0','0','business:officialevent:authorize','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:officialevent:authorize');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:officialevent:edit',(SELECT menu_id FROM sys_menu WHERE perms='business:officialevent:list'),99,'#','',1,0,'F','0','0','business:officialevent:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:officialevent:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:officialevent:notify',(SELECT menu_id FROM sys_menu WHERE perms='business:officialevent:list'),99,'#','',1,0,'F','0','0','business:officialevent:notify','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:officialevent:notify');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:officialevent:query',(SELECT menu_id FROM sys_menu WHERE perms='business:officialevent:list'),99,'#','',1,0,'F','0','0','business:officialevent:query','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:officialevent:query');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:officialevent:settle',(SELECT menu_id FROM sys_menu WHERE perms='business:officialevent:list'),99,'#','',1,0,'F','0','0','business:officialevent:settle','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:officialevent:settle');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:roamPoi:edit',(SELECT menu_id FROM sys_menu WHERE perms='business:roamPoi:list'),99,'#','',1,0,'F','0','0','business:roamPoi:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:roamPoi:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:ailog:query',(SELECT menu_id FROM sys_menu WHERE perms='business:ailog:list'),99,'#','',1,0,'F','0','0','business:ailog:query','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:ailog:query');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:chapterOffer:edit',(SELECT menu_id FROM sys_menu WHERE perms='business:chapterOffer:list'),99,'#','',1,0,'F','0','0','business:chapterOffer:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:chapterOffer:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:userpermission:edit',(SELECT menu_id FROM sys_menu WHERE perms='business:userpermission:list'),99,'#','',1,0,'F','0','0','business:userpermission:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:userpermission:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:settlementrule:edit',(SELECT menu_id FROM sys_menu WHERE perms='business:settlementrule:list'),99,'#','',1,0,'F','0','0','business:settlementrule:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:settlementrule:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:refund:review',(SELECT menu_id FROM sys_menu WHERE perms='business:refund:list'),99,'#','',1,0,'F','0','0','business:refund:review','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:refund:review');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:verificationReview:handle',(SELECT menu_id FROM sys_menu WHERE perms='business:verificationReview:list'),99,'#','',1,0,'F','0','0','business:verificationReview:handle','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:verificationReview:handle');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:growth:edit',(SELECT menu_id FROM sys_menu WHERE perms='business:growth:list'),99,'#','',1,0,'F','0','0','business:growth:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:growth:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:world:edit',(SELECT menu_id FROM sys_menu WHERE perms='business:world:list'),99,'#','',1,0,'F','0','0','business:world:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:world:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:creator:review',(SELECT menu_id FROM sys_menu WHERE perms='business:creator:list'),99,'#','',1,0,'F','0','0','business:creator:review','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:creator:review');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:crm:aggregate',(SELECT menu_id FROM sys_menu WHERE perms='business:crm:list'),99,'#','',1,0,'F','0','0','business:crm:aggregate','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:crm:aggregate');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:settlement:confirm',(SELECT menu_id FROM sys_menu WHERE perms='business:settlement:list'),99,'#','',1,0,'F','0','0','business:settlement:confirm','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:settlement:confirm');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:settlement:generate',(SELECT menu_id FROM sys_menu WHERE perms='business:settlement:list'),99,'#','',1,0,'F','0','0','business:settlement:generate','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:settlement:generate');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:merchantlead:add',(SELECT menu_id FROM sys_menu WHERE perms='business:merchantlead:list'),99,'#','',1,0,'F','0','0','business:merchantlead:add','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:merchantlead:add');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:merchantlead:edit',(SELECT menu_id FROM sys_menu WHERE perms='business:merchantlead:list'),99,'#','',1,0,'F','0','0','business:merchantlead:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:merchantlead:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:merchantlead:query',(SELECT menu_id FROM sys_menu WHERE perms='business:merchantlead:list'),99,'#','',1,0,'F','0','0','business:merchantlead:query','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:merchantlead:query');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:merchantlead:remove',(SELECT menu_id FROM sys_menu WHERE perms='business:merchantlead:list'),99,'#','',1,0,'F','0','0','business:merchantlead:remove','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:merchantlead:remove');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:roamAiContent:add',(SELECT menu_id FROM sys_menu WHERE perms='business:roamAiContent:list'),99,'#','',1,0,'F','0','0','business:roamAiContent:add','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:roamAiContent:add');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:roamAiContent:audit',(SELECT menu_id FROM sys_menu WHERE perms='business:roamAiContent:list'),99,'#','',1,0,'F','0','0','business:roamAiContent:audit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:roamAiContent:audit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:roamAiContent:edit',(SELECT menu_id FROM sys_menu WHERE perms='business:roamAiContent:list'),99,'#','',1,0,'F','0','0','business:roamAiContent:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:roamAiContent:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:roamAiContent:query',(SELECT menu_id FROM sys_menu WHERE perms='business:roamAiContent:list'),99,'#','',1,0,'F','0','0','business:roamAiContent:query','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:roamAiContent:query');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:roamAiContent:remove',(SELECT menu_id FROM sys_menu WHERE perms='business:roamAiContent:list'),99,'#','',1,0,'F','0','0','business:roamAiContent:remove','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:roamAiContent:remove');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:payablebatch:edit',(SELECT menu_id FROM sys_menu WHERE perms='business:payablebatch:list'),99,'#','',1,0,'F','0','0','business:payablebatch:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:payablebatch:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:payablebatch:freeze',(SELECT menu_id FROM sys_menu WHERE perms='business:payablebatch:list'),99,'#','',1,0,'F','0','0','business:payablebatch:freeze','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:payablebatch:freeze');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:coopcomplaint:handle',(SELECT menu_id FROM sys_menu WHERE perms='business:coopcomplaint:list'),99,'#','',1,0,'F','0','0','business:coopcomplaint:handle','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:coopcomplaint:handle');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:coopcomplaint:query',(SELECT menu_id FROM sys_menu WHERE perms='business:coopcomplaint:list'),99,'#','',1,0,'F','0','0','business:coopcomplaint:query','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:coopcomplaint:query');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:im:block',(SELECT menu_id FROM sys_menu WHERE perms='business:im:list'),99,'#','',1,0,'F','0','0','business:im:block','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:im:block');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:im:send',(SELECT menu_id FROM sys_menu WHERE perms='business:im:list'),99,'#','',1,0,'F','0','0','business:im:send','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:im:send');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:presetStyle:add',(SELECT menu_id FROM sys_menu WHERE perms='business:presetStyle:list'),99,'#','',1,0,'F','0','0','business:presetStyle:add','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:presetStyle:add');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:presetStyle:edit',(SELECT menu_id FROM sys_menu WHERE perms='business:presetStyle:list'),99,'#','',1,0,'F','0','0','business:presetStyle:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:presetStyle:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:presetStyle:query',(SELECT menu_id FROM sys_menu WHERE perms='business:presetStyle:list'),99,'#','',1,0,'F','0','0','business:presetStyle:query','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:presetStyle:query');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:presetStyle:remove',(SELECT menu_id FROM sys_menu WHERE perms='business:presetStyle:list'),99,'#','',1,0,'F','0','0','business:presetStyle:remove','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:presetStyle:remove');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:presetRoute:add',(SELECT menu_id FROM sys_menu WHERE perms='business:presetRoute:list'),99,'#','',1,0,'F','0','0','business:presetRoute:add','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:presetRoute:add');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:presetRoute:edit',(SELECT menu_id FROM sys_menu WHERE perms='business:presetRoute:list'),99,'#','',1,0,'F','0','0','business:presetRoute:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:presetRoute:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:presetRoute:query',(SELECT menu_id FROM sys_menu WHERE perms='business:presetRoute:list'),99,'#','',1,0,'F','0','0','business:presetRoute:query','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:presetRoute:query');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:presetRoute:remove',(SELECT menu_id FROM sys_menu WHERE perms='business:presetRoute:list'),99,'#','',1,0,'F','0','0','business:presetRoute:remove','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:presetRoute:remove');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:user:add',(SELECT menu_id FROM sys_menu WHERE perms='system:user:list'),99,'#','',1,0,'F','0','0','system:user:add','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:user:add');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:user:edit',(SELECT menu_id FROM sys_menu WHERE perms='system:user:list'),99,'#','',1,0,'F','0','0','system:user:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:user:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:user:export',(SELECT menu_id FROM sys_menu WHERE perms='system:user:list'),99,'#','',1,0,'F','0','0','system:user:export','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:user:export');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:user:import',(SELECT menu_id FROM sys_menu WHERE perms='system:user:list'),99,'#','',1,0,'F','0','0','system:user:import','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:user:import');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:user:query',(SELECT menu_id FROM sys_menu WHERE perms='system:user:list'),99,'#','',1,0,'F','0','0','system:user:query','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:user:query');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:user:remove',(SELECT menu_id FROM sys_menu WHERE perms='system:user:list'),99,'#','',1,0,'F','0','0','system:user:remove','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:user:remove');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:user:resetPwd',(SELECT menu_id FROM sys_menu WHERE perms='system:user:list'),99,'#','',1,0,'F','0','0','system:user:resetPwd','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:user:resetPwd');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:role:add',(SELECT menu_id FROM sys_menu WHERE perms='system:role:list'),99,'#','',1,0,'F','0','0','system:role:add','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:role:add');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:role:edit',(SELECT menu_id FROM sys_menu WHERE perms='system:role:list'),99,'#','',1,0,'F','0','0','system:role:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:role:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:role:export',(SELECT menu_id FROM sys_menu WHERE perms='system:role:list'),99,'#','',1,0,'F','0','0','system:role:export','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:role:export');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:role:query',(SELECT menu_id FROM sys_menu WHERE perms='system:role:list'),99,'#','',1,0,'F','0','0','system:role:query','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:role:query');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:role:remove',(SELECT menu_id FROM sys_menu WHERE perms='system:role:list'),99,'#','',1,0,'F','0','0','system:role:remove','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:role:remove');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:menu:add',(SELECT menu_id FROM sys_menu WHERE perms='system:menu:list'),99,'#','',1,0,'F','0','0','system:menu:add','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:menu:add');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:menu:edit',(SELECT menu_id FROM sys_menu WHERE perms='system:menu:list'),99,'#','',1,0,'F','0','0','system:menu:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:menu:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:menu:query',(SELECT menu_id FROM sys_menu WHERE perms='system:menu:list'),99,'#','',1,0,'F','0','0','system:menu:query','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:menu:query');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:menu:remove',(SELECT menu_id FROM sys_menu WHERE perms='system:menu:list'),99,'#','',1,0,'F','0','0','system:menu:remove','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:menu:remove');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:dept:add',(SELECT menu_id FROM sys_menu WHERE perms='system:dept:list'),99,'#','',1,0,'F','0','0','system:dept:add','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:dept:add');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:dept:edit',(SELECT menu_id FROM sys_menu WHERE perms='system:dept:list'),99,'#','',1,0,'F','0','0','system:dept:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:dept:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:dept:query',(SELECT menu_id FROM sys_menu WHERE perms='system:dept:list'),99,'#','',1,0,'F','0','0','system:dept:query','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:dept:query');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:dept:remove',(SELECT menu_id FROM sys_menu WHERE perms='system:dept:list'),99,'#','',1,0,'F','0','0','system:dept:remove','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:dept:remove');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:post:add',(SELECT menu_id FROM sys_menu WHERE perms='system:post:list'),99,'#','',1,0,'F','0','0','system:post:add','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:post:add');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:post:edit',(SELECT menu_id FROM sys_menu WHERE perms='system:post:list'),99,'#','',1,0,'F','0','0','system:post:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:post:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:post:export',(SELECT menu_id FROM sys_menu WHERE perms='system:post:list'),99,'#','',1,0,'F','0','0','system:post:export','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:post:export');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:post:query',(SELECT menu_id FROM sys_menu WHERE perms='system:post:list'),99,'#','',1,0,'F','0','0','system:post:query','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:post:query');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:post:remove',(SELECT menu_id FROM sys_menu WHERE perms='system:post:list'),99,'#','',1,0,'F','0','0','system:post:remove','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:post:remove');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:dict:add',(SELECT menu_id FROM sys_menu WHERE perms='system:dict:list'),99,'#','',1,0,'F','0','0','system:dict:add','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:dict:add');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:dict:edit',(SELECT menu_id FROM sys_menu WHERE perms='system:dict:list'),99,'#','',1,0,'F','0','0','system:dict:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:dict:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:dict:export',(SELECT menu_id FROM sys_menu WHERE perms='system:dict:list'),99,'#','',1,0,'F','0','0','system:dict:export','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:dict:export');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:dict:query',(SELECT menu_id FROM sys_menu WHERE perms='system:dict:list'),99,'#','',1,0,'F','0','0','system:dict:query','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:dict:query');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:dict:remove',(SELECT menu_id FROM sys_menu WHERE perms='system:dict:list'),99,'#','',1,0,'F','0','0','system:dict:remove','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:dict:remove');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:config:add',(SELECT menu_id FROM sys_menu WHERE perms='system:config:list'),99,'#','',1,0,'F','0','0','system:config:add','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:config:add');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:config:edit',(SELECT menu_id FROM sys_menu WHERE perms='system:config:list'),99,'#','',1,0,'F','0','0','system:config:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:config:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:config:export',(SELECT menu_id FROM sys_menu WHERE perms='system:config:list'),99,'#','',1,0,'F','0','0','system:config:export','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:config:export');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:config:query',(SELECT menu_id FROM sys_menu WHERE perms='system:config:list'),99,'#','',1,0,'F','0','0','system:config:query','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:config:query');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:config:remove',(SELECT menu_id FROM sys_menu WHERE perms='system:config:list'),99,'#','',1,0,'F','0','0','system:config:remove','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:config:remove');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:notice:add',(SELECT menu_id FROM sys_menu WHERE perms='system:notice:list'),99,'#','',1,0,'F','0','0','system:notice:add','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:notice:add');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:notice:edit',(SELECT menu_id FROM sys_menu WHERE perms='system:notice:list'),99,'#','',1,0,'F','0','0','system:notice:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:notice:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:notice:query',(SELECT menu_id FROM sys_menu WHERE perms='system:notice:list'),99,'#','',1,0,'F','0','0','system:notice:query','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:notice:query');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'system:notice:remove',(SELECT menu_id FROM sys_menu WHERE perms='system:notice:list'),99,'#','',1,0,'F','0','0','system:notice:remove','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:notice:remove');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'monitor:online:forceLogout',(SELECT menu_id FROM sys_menu WHERE perms='monitor:online:list'),99,'#','',1,0,'F','0','0','monitor:online:forceLogout','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='monitor:online:forceLogout');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'monitor:operlog:export',(SELECT menu_id FROM sys_menu WHERE perms='monitor:operlog:list'),99,'#','',1,0,'F','0','0','monitor:operlog:export','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='monitor:operlog:export');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'monitor:operlog:remove',(SELECT menu_id FROM sys_menu WHERE perms='monitor:operlog:list'),99,'#','',1,0,'F','0','0','monitor:operlog:remove','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='monitor:operlog:remove');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'monitor:logininfor:export',(SELECT menu_id FROM sys_menu WHERE perms='monitor:logininfor:list'),99,'#','',1,0,'F','0','0','monitor:logininfor:export','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='monitor:logininfor:export');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'monitor:logininfor:remove',(SELECT menu_id FROM sys_menu WHERE perms='monitor:logininfor:list'),99,'#','',1,0,'F','0','0','monitor:logininfor:remove','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='monitor:logininfor:remove');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'monitor:logininfor:unlock',(SELECT menu_id FROM sys_menu WHERE perms='monitor:logininfor:list'),99,'#','',1,0,'F','0','0','monitor:logininfor:unlock','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='monitor:logininfor:unlock');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:product:add',(SELECT menu_id FROM sys_menu WHERE perms='business:product:list'),99,'#','',1,0,'F','0','0','business:product:add','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:product:add');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:product:edit',(SELECT menu_id FROM sys_menu WHERE perms='business:product:list'),99,'#','',1,0,'F','0','0','business:product:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:product:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:product:export',(SELECT menu_id FROM sys_menu WHERE perms='business:product:list'),99,'#','',1,0,'F','0','0','business:product:export','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:product:export');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:product:query',(SELECT menu_id FROM sys_menu WHERE perms='business:product:list'),99,'#','',1,0,'F','0','0','business:product:query','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:product:query');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:product:remove',(SELECT menu_id FROM sys_menu WHERE perms='business:product:list'),99,'#','',1,0,'F','0','0','business:product:remove','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:product:remove');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:infomation:add',(SELECT menu_id FROM sys_menu WHERE perms='business:infomation:list'),99,'#','',1,0,'F','0','0','business:infomation:add','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:infomation:add');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:infomation:edit',(SELECT menu_id FROM sys_menu WHERE perms='business:infomation:list'),99,'#','',1,0,'F','0','0','business:infomation:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:infomation:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:infomation:export',(SELECT menu_id FROM sys_menu WHERE perms='business:infomation:list'),99,'#','',1,0,'F','0','0','business:infomation:export','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:infomation:export');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:infomation:query',(SELECT menu_id FROM sys_menu WHERE perms='business:infomation:list'),99,'#','',1,0,'F','0','0','business:infomation:query','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:infomation:query');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:infomation:remove',(SELECT menu_id FROM sys_menu WHERE perms='business:infomation:list'),99,'#','',1,0,'F','0','0','business:infomation:remove','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:infomation:remove');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:coupon:add',(SELECT menu_id FROM sys_menu WHERE perms='business:coupon:list'),99,'#','',1,0,'F','0','0','business:coupon:add','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:coupon:add');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:coupon:edit',(SELECT menu_id FROM sys_menu WHERE perms='business:coupon:list'),99,'#','',1,0,'F','0','0','business:coupon:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:coupon:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:coupon:export',(SELECT menu_id FROM sys_menu WHERE perms='business:coupon:list'),99,'#','',1,0,'F','0','0','business:coupon:export','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:coupon:export');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:coupon:query',(SELECT menu_id FROM sys_menu WHERE perms='business:coupon:list'),99,'#','',1,0,'F','0','0','business:coupon:query','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:coupon:query');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:coupon:remove',(SELECT menu_id FROM sys_menu WHERE perms='business:coupon:list'),99,'#','',1,0,'F','0','0','business:coupon:remove','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:coupon:remove');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:order:add',(SELECT menu_id FROM sys_menu WHERE perms='business:order:list'),99,'#','',1,0,'F','0','0','business:order:add','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:order:add');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:order:edit',(SELECT menu_id FROM sys_menu WHERE perms='business:order:list'),99,'#','',1,0,'F','0','0','business:order:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:order:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:order:export',(SELECT menu_id FROM sys_menu WHERE perms='business:order:list'),99,'#','',1,0,'F','0','0','business:order:export','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:order:export');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:order:query',(SELECT menu_id FROM sys_menu WHERE perms='business:order:list'),99,'#','',1,0,'F','0','0','business:order:query','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:order:query');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:order:remove',(SELECT menu_id FROM sys_menu WHERE perms='business:order:list'),99,'#','',1,0,'F','0','0','business:order:remove','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:order:remove');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:gift:add',(SELECT menu_id FROM sys_menu WHERE perms='business:gift:list'),99,'#','',1,0,'F','0','0','business:gift:add','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:gift:add');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:gift:edit',(SELECT menu_id FROM sys_menu WHERE perms='business:gift:list'),99,'#','',1,0,'F','0','0','business:gift:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:gift:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:gift:export',(SELECT menu_id FROM sys_menu WHERE perms='business:gift:list'),99,'#','',1,0,'F','0','0','business:gift:export','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:gift:export');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:gift:query',(SELECT menu_id FROM sys_menu WHERE perms='business:gift:list'),99,'#','',1,0,'F','0','0','business:gift:query','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:gift:query');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:gift:remove',(SELECT menu_id FROM sys_menu WHERE perms='business:gift:list'),99,'#','',1,0,'F','0','0','business:gift:remove','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:gift:remove');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:member:add',(SELECT menu_id FROM sys_menu WHERE perms='business:member:list'),99,'#','',1,0,'F','0','0','business:member:add','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:member:add');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:member:edit',(SELECT menu_id FROM sys_menu WHERE perms='business:member:list'),99,'#','',1,0,'F','0','0','business:member:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:member:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:member:export',(SELECT menu_id FROM sys_menu WHERE perms='business:member:list'),99,'#','',1,0,'F','0','0','business:member:export','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:member:export');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:member:query',(SELECT menu_id FROM sys_menu WHERE perms='business:member:list'),99,'#','',1,0,'F','0','0','business:member:query','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:member:query');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:member:remove',(SELECT menu_id FROM sys_menu WHERE perms='business:member:list'),99,'#','',1,0,'F','0','0','business:member:remove','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:member:remove');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:pointsetting:add',(SELECT menu_id FROM sys_menu WHERE perms='business:pointsetting:list'),99,'#','',1,0,'F','0','0','business:pointsetting:add','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:pointsetting:add');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:pointsetting:edit',(SELECT menu_id FROM sys_menu WHERE perms='business:pointsetting:list'),99,'#','',1,0,'F','0','0','business:pointsetting:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:pointsetting:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:pointsetting:export',(SELECT menu_id FROM sys_menu WHERE perms='business:pointsetting:list'),99,'#','',1,0,'F','0','0','business:pointsetting:export','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:pointsetting:export');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:pointsetting:query',(SELECT menu_id FROM sys_menu WHERE perms='business:pointsetting:list'),99,'#','',1,0,'F','0','0','business:pointsetting:query','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:pointsetting:query');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:pointsetting:remove',(SELECT menu_id FROM sys_menu WHERE perms='business:pointsetting:list'),99,'#','',1,0,'F','0','0','business:pointsetting:remove','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:pointsetting:remove');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:registration:add',(SELECT menu_id FROM sys_menu WHERE perms='business:registration:list'),99,'#','',1,0,'F','0','0','business:registration:add','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:registration:add');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:registration:edit',(SELECT menu_id FROM sys_menu WHERE perms='business:registration:list'),99,'#','',1,0,'F','0','0','business:registration:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:registration:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:registration:export',(SELECT menu_id FROM sys_menu WHERE perms='business:registration:list'),99,'#','',1,0,'F','0','0','business:registration:export','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:registration:export');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:registration:query',(SELECT menu_id FROM sys_menu WHERE perms='business:registration:list'),99,'#','',1,0,'F','0','0','business:registration:query','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:registration:query');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:registration:remove',(SELECT menu_id FROM sys_menu WHERE perms='business:registration:list'),99,'#','',1,0,'F','0','0','business:registration:remove','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:registration:remove');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:registration:reverseRedeem',(SELECT menu_id FROM sys_menu WHERE perms='business:registration:list'),99,'#','',1,0,'F','0','0','business:registration:reverseRedeem','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:registration:reverseRedeem');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:merchantNpc:add',(SELECT menu_id FROM sys_menu WHERE perms='business:merchantNpc:list'),99,'#','',1,0,'F','0','0','business:merchantNpc:add','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:merchantNpc:add');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:merchantNpc:edit',(SELECT menu_id FROM sys_menu WHERE perms='business:merchantNpc:list'),99,'#','',1,0,'F','0','0','business:merchantNpc:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:merchantNpc:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:merchantNpc:query',(SELECT menu_id FROM sys_menu WHERE perms='business:merchantNpc:list'),99,'#','',1,0,'F','0','0','business:merchantNpc:query','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:merchantNpc:query');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:merchantNpc:remove',(SELECT menu_id FROM sys_menu WHERE perms='business:merchantNpc:list'),99,'#','',1,0,'F','0','0','business:merchantNpc:remove','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:merchantNpc:remove');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:memberbalancedetail:add',(SELECT menu_id FROM sys_menu WHERE perms='business:memberbalancedetail:list'),99,'#','',1,0,'F','0','0','business:memberbalancedetail:add','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:memberbalancedetail:add');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:memberbalancedetail:edit',(SELECT menu_id FROM sys_menu WHERE perms='business:memberbalancedetail:list'),99,'#','',1,0,'F','0','0','business:memberbalancedetail:edit','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:memberbalancedetail:edit');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:memberbalancedetail:export',(SELECT menu_id FROM sys_menu WHERE perms='business:memberbalancedetail:list'),99,'#','',1,0,'F','0','0','business:memberbalancedetail:export','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:memberbalancedetail:export');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:memberbalancedetail:query',(SELECT menu_id FROM sys_menu WHERE perms='business:memberbalancedetail:list'),99,'#','',1,0,'F','0','0','business:memberbalancedetail:query','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:memberbalancedetail:query');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT 'business:memberbalancedetail:remove',(SELECT menu_id FROM sys_menu WHERE perms='business:memberbalancedetail:list'),99,'#','',1,0,'F','0','0','business:memberbalancedetail:remove','#','migration',NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='business:memberbalancedetail:remove');
INSERT INTO tmp_cy_seed_guard(id) SELECT 1 WHERE NOT ((SELECT COUNT(*)=67 FROM sys_menu WHERE menu_type='C' AND perms IN ('business:activity:list','business:membertemplate:list','business:ticket:list','business:merchant:list','business:registrationmerchant:list','business:square:list','business:comment:list','business:betagame:list','system:category:list','system:banner:list','business:topic:list','business:memberwithdrawal:list','business:club:list','business:audit:list','business:cashwithdraw:list','business:officialevent:list','business:roamPoi:list','business:verifyEvidence:list','business:clubRun:list','business:ailog:list','business:chapterOffer:list','business:userpermission:list','business:settlementrule:list','business:refund:list','business:verificationReview:list','business:growth:list','business:world:list','business:creator:list','business:crm:list','business:settlement:list','business:merchantlead:list','business:roamAiContent:list','business:analytics:list','business:recommendation:list','business:payablebatch:list','business:coopcomplaint:list','business:im:list','business:presetStyle:list','business:presetRoute:list','system:user:list','system:role:list','system:menu:list','system:dept:list','system:post:list','system:dict:list','system:config:list','system:notice:list','monitor:online:list','monitor:job:list','monitor:druid:list','monitor:server:list','monitor:cache:list','tool:build:list','tool:gen:list','tool:swagger:list','monitor:operlog:list','monitor:logininfor:list','business:product:list','business:infomation:list','business:coupon:list','business:order:list','business:gift:list','business:member:list','business:pointsetting:list','business:registration:list','business:merchantNpc:list','business:memberbalancedetail:list')));
DROP TEMPORARY TABLE tmp_cy_seed_guard;
