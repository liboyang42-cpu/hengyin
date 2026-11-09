-- qx-b3 rig fixture (HARNESS): run/payment facts for topic 990014 self-play scope; reads/writes then go through real API.
SET @snap = JSON_OBJECT('routeStations', JSON_ARRAY(
  JSON_OBJECT('nodeId',71,'nodeName','样板咖啡第1站'), JSON_OBJECT('nodeId',72,'nodeName','样板书店第2站'),
  JSON_OBJECT('nodeId',73,'nodeName','样板花店第3站'), JSON_OBJECT('nodeId',74,'nodeName','样板面包第4站')),
  'themeConfig', JSON_OBJECT('contentGeography', JSON_OBJECT('countryCode','CN','cityCode','310100')));
UPDATE cms_topic SET self_play=1, self_play_price=39.00 WHERE id=990014;
UPDATE ums_member SET nickname='林野' WHERE id=9001;
DELETE FROM cms_registration WHERE id IN (880001,880002,880003);
INSERT INTO cms_registration (id,registration_no,owner_type,owner_id,activity_title,member_id,order_num,original_amount,discount_amount,total_amount,payable_amount,
  payment_status,payment_type,payment_time,registration_status,pay_expire_time,create_time,update_time,topic_id,expires_at,wechat_payment_amount)
VALUES
 (880001,'RIGB3-880001',1,990014,'E2E 探店日一期',9004,3,117.00,0,117.00,117.00,2,1,NOW() - INTERVAL 1 DAY,2,NOW() + INTERVAL 1 DAY,NOW() - INTERVAL 1 DAY,NOW(),990014,NOW() + INTERVAL 30 DAY,117.00),
 (880002,'RIGB3-880002',1,990014,'E2E 探店日一期',9001,1,39.00,0,39.00,39.00,2,1,NOW() - INTERVAL 2 DAY,2,NOW() + INTERVAL 1 DAY,NOW() - INTERVAL 2 DAY,NOW(),990014,NOW() + INTERVAL 30 DAY,39.00),
 (880003,'RIGB3-880003',1,990014,'E2E 探店日一期',9009,1,39.00,0,39.00,39.00,2,1,NOW() - INTERVAL 2 DAY,2,NOW() + INTERVAL 1 DAY,NOW() - INTERVAL 2 DAY,NOW(),990014,NOW() + INTERVAL 30 DAY,39.00);
DELETE FROM topic_route_session WHERE id IN (770001,770002,770003);
INSERT INTO topic_route_session (id,activity_id,topic_id,member_id,play_instance_id,config_version,config_snapshot_json,state_json,status,phase,seed,version,completed_at,create_time,update_time)
VALUES
 (770001,0,990014,9004,'rigb3-run-9004',1,@snap,JSON_OBJECT(),1,'DONE',1,1,NOW() - INTERVAL 2 HOUR,NOW() - INTERVAL 4 HOUR,NOW()),
 (770002,0,990014,9001,'rigb3-run-9001',1,@snap,JSON_OBJECT(),1,'DONE',2,1,NOW() - INTERVAL 20 HOUR,NOW() - INTERVAL 22 HOUR,NOW()),
 (770003,0,990014,9009,'rigb3-run-9009',1,@snap,JSON_OBJECT(),0,'PLAYING',3,1,NULL,NOW() - INTERVAL 3 HOUR,NOW());
DELETE FROM player_node_progress WHERE play_instance_id LIKE 'rigb3-run-%';
INSERT INTO player_node_progress (member_id,activity_id,topic_id,node_id,status,checkin_time,pic_url,del_flag,create_time,play_instance_id) VALUES
 (9004,0,990014,71,1,NOW() - INTERVAL 230 MINUTE,'/images/roam/tile-route.svg','0',NOW(),'rigb3-run-9004'),
 (9004,0,990014,72,1,NOW() - INTERVAL 200 MINUTE,NULL,'0',NOW(),'rigb3-run-9004'),
 (9004,0,990014,73,1,NOW() - INTERVAL 170 MINUTE,'/images/roam/tile-stamp.svg','0',NOW(),'rigb3-run-9004'),
 (9004,0,990014,74,1,NOW() - INTERVAL 125 MINUTE,NULL,'0',NOW(),'rigb3-run-9004'),
 (9001,0,990014,71,1,NOW() - INTERVAL 21 HOUR,NULL,'0',NOW(),'rigb3-run-9001'),
 (9001,0,990014,72,1,NOW() - INTERVAL 20 HOUR,NULL,'0',NOW(),'rigb3-run-9001'),
 (9001,0,990014,73,1,NOW() - INTERVAL 1220 MINUTE,NULL,'0',NOW(),'rigb3-run-9001'),
 (9001,0,990014,74,1,NOW() - INTERVAL 1210 MINUTE,NULL,'0',NOW(),'rigb3-run-9001'),
 (9009,0,990014,71,1,NOW() - INTERVAL 150 MINUTE,NULL,'0',NOW(),'rigb3-run-9009'),
 (9009,0,990014,72,1,NOW() - INTERVAL 140 MINUTE,NULL,'0',NOW(),'rigb3-run-9009');
UPDATE cms_topic SET user_status=1 WHERE id=990014;
