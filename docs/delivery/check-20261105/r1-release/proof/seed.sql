-- fictional scratch-only rows, ids 98xx; each exercises one release migration
DELETE FROM settlement_rule WHERE id BETWEEN 9801 AND 9806;
INSERT INTO settlement_rule(id,rule_name,merchant_id,biz_type,platform_commission_rate,merchant_rate,creator_rate,status,del_flag,create_time,update_time) VALUES
 (9801,'reh activity generic',0,'activity',0.1000,0.9000,0.0000,1,0,NOW(),NOW()),
 (9802,'reh topic generic',0,'topic',0.1000,0.9000,0.0000,1,0,NOW(),NOW()),
 (9803,'reh topic_club generic',0,'topic_club',0.1000,0.9000,0.0000,1,0,NOW(),NOW()),
 (9804,'reh merchant-specific keep',8001,'activity',0.0500,0.9500,0.0000,1,0,NOW(),NOW()),
 (9805,'reh disabled generic keep',0,'activity',0.2000,0.8000,0.0000,0,0,NOW(),NOW());
DELETE FROM sys_category WHERE id IN (9811,9812);
INSERT INTO sys_category(id,type,parent_id,category_name,level,sort_id,status,del_flag,create_time,update_time) VALUES
 (9811,1,0,'茶馆',0,90,1,0,NOW(),NOW()),(9812,1,0,'书店',0,91,1,0,NOW(),NOW());
DELETE FROM cms_topic WHERE id BETWEEN 9821 AND 9869;
INSERT INTO cms_topic(id,name,category_ids,del_flag,member_id) VALUES (9821,'reh topic using 书店','21,9812',0,9002);
DELETE FROM cms_activity WHERE id BETWEEN 9801 AND 9805;
INSERT INTO cms_activity(id,club_id,del_flag,cancel_time,end_date,latitude,longitude) VALUES
 (9801,7001,0,NULL,DATE_ADD(NOW(),INTERVAL 3 DAY),NULL,NULL),
 (9802,7001,0,NULL,DATE_SUB(NOW(),INTERVAL 1 DAY),NULL,NULL),
 (9803,7001,0,NULL,DATE_ADD(NOW(),INTERVAL 3 DAY),'30.100000','120.100000'),
 (9804,NULL,0,NULL,DATE_ADD(NOW(),INTERVAL 3 DAY),NULL,NULL),
 (9805,7001,0,NOW(),DATE_ADD(NOW(),INTERVAL 3 DAY),NULL,NULL);
DELETE FROM oms_ticket WHERE id BETWEEN 98011 AND 98018;
INSERT INTO oms_ticket(id,owner_type,owner_id,name,gather_lat,gather_lng,del_flag) VALUES
 (98011,2,9801,'no coords',NULL,NULL,0),(98012,2,9801,'pick me',31.230400,121.473700,0),(98013,2,9801,'later',32.0,122.0,0),
 (98014,2,9802,'ended',31.1,121.1,0),(98015,2,9803,'has',31.2,121.2,0),(98016,2,9804,'noclub',31.3,121.3,0),(98017,2,9805,'cancelled',31.4,121.4,0),
 (98018,1,9801,'topic-owned ignored',10.0,10.0,0);
DELETE FROM club_role_assignment WHERE id=9831;
INSERT INTO club_role_assignment(id,club_id,role_code,scope_type,scope_id,target_member_id,assigned_by_member_id,assigned_at,create_time) VALUES
 (9831,7001,'EVENT_CHECKIN','EVENT',9801,9005,9003,NOW(),NOW());
-- merchant default row as production has it (1/0 per migration comment)
UPDATE user_permission SET can_create_event=1, max_events=0 WHERE role='merchant' AND membership_level IS NULL;
-- points rules as production (ids 5,6 on); id 3 disabled-but-valued to show status now honoured
DELETE FROM cms_points_setting WHERE id BETWEEN 1 AND 7;
INSERT INTO cms_points_setting(id,title,value,status,del_flag,type,create_time) VALUES
 (1,'注册赠送',10,1,0,1,NOW()),(2,'报名活动',10,1,0,1,NOW()),(3,'报名额外(停用)',5,0,0,1,NOW()),
 (4,'评价',5,1,0,1,NOW()),(5,'邀请1位完成首次购买的用户',500,1,0,1,NOW()),(6,'被邀请用户报名任意活动',100,1,0,1,NOW()),(7,'推荐商户注册',30,1,0,1,NOW());
-- total_time: editor-minute rows, seed-second row, host-only, edge case, zero
INSERT INTO cms_topic(id,name,del_flag,member_id,total_time) VALUES
 (9831,'reh minutes all',0,9002,30),(9832,'reh seed seconds',0,9002,5400),(9833,'reh minutes host-only',0,9002,10),
 (9834,'reh edge host1 merchant59',0,9002,1),(9835,'reh zero',0,9002,0);
DELETE FROM cms_topic_node WHERE topic_id BETWEEN 9831 AND 9835;
INSERT INTO cms_topic_node(topic_id,name,node_time,merchant_member_id) VALUES
 (9831,'a',10,NULL),(9831,'b',20,NULL),(9832,'a',30,NULL),(9832,'b',60,NULL),
 (9833,'host',10,NULL),(9833,'shop',15,9006),(9834,'host',1,NULL),(9834,'shop',59,9006),(9835,'a',NULL,NULL);
-- wording: seeded template rows (exact text), an edited one, a merchant copy
INSERT INTO cms_topic(id,name,description,del_flag,member_id,is_template,create_by) VALUES
 (9851,'三个盲盒','三个「盲盒袋子」,每个拆开是一种不同的小任务:观察、拍照、开口问。全部完成即通关。适合想让常客每次来都玩点新花样的店。复制后把三个任务分别换成你店的版本。',0,100000,1,'seed_playpack_20260820'),
 (9852,'三个盲盒(后台改过)',NULL,0,100000,1,'seed_playpack_20260820'),
 (9853,'穿搭盲配',NULL,0,100000,1,'seed_playpack2_20260821'),
 (9854,'三个盲盒',NULL,0,9006,0,'system_copy');
INSERT INTO cms_topic_node(topic_id,name,create_by) VALUES (9851,'盲盒一:观察','seed_playpack_20260820'),(9851,'盲盒二:拍照','seed_playpack_20260820'),(9854,'盲盒一:观察','system_copy');
DELETE FROM cms_member_template WHERE id BETWEEN 9861 AND 9863;
INSERT INTO cms_member_template(id,member_id,title,create_by) VALUES (9861,100000,'玩法包·盲盒之观察','seed_playpack_20260820'),(9862,9006,'玩法包·盲盒之观察','system_copy');
DELETE FROM cms_topic_chapter WHERE topic_id=9853;
INSERT INTO cms_topic_chapter(topic_id,name,create_by) VALUES (9853,'盲盒衣柜','seed_playpack2_20260821');
