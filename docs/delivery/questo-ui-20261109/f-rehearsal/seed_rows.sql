-- f-rehearsal 合成行（只写进本机 scratch 库，不代表生产数据）。
-- 目的：让第 4/5 轮的数据迁移和依赖生产参考数据的迁移真正命中行；形状按各迁移文件头写的生产现状构造。
-- 每条一句；由 rehearse.py 在 10-29 演练的 SYNTHETIC_ROWS 之后执行。

-- sys_category type=1 通用种子（20261101_theme_categories 文件头：1–4、21–23、25 是生产现有行）
INSERT INTO sys_category(id, type, parent_id, category_name, level, sort_id, status, del_flag, create_time) VALUES
 (1,1,0,'运动健身',0,1,1,0,NOW()),(2,1,0,'科技数码',0,2,1,0,NOW()),(3,1,0,'美食烹饪',0,3,1,0,NOW()),(4,1,0,'娱乐达人',0,4,1,0,NOW()),
 (21,1,0,'衣物洗护',0,21,1,0,NOW()),(22,1,0,'家政维修',0,22,1,0,NOW()),(23,1,0,'出行服务',0,23,1,0,NOW()),(25,1,0,'生活美学',0,25,1,0,NOW());

-- settlement_rule 通用行（20261103 文件头：生产 2026-08 为 activity / topic 0.10/0.90/0）
INSERT INTO settlement_rule(merchant_id, biz_type, platform_commission_rate, merchant_rate, creator_rate, status, del_flag) VALUES
 (0,'activity',0.1000,0.9000,0.0000,1,0),(0,'topic',0.1000,0.9000,0.0000,1,0);

-- 积分规则 id 1（注册赠送）、id 6（被邀请人报名送分）
INSERT INTO cms_points_setting(id, title, value, status) VALUES (1,'注册赠送',20,1),(6,'被邀请人报名送分',10,1);

-- 35 款平台商家玩法封面旧地址（20261030_merchant_game_covers_to_oss）
INSERT INTO cms_template_library(id, member_id, title, img_url) VALUES
 (98000,100000,'rehearsal-cover-0','https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/branch-story-relay.jpg'),
 (98001,100000,'rehearsal-cover-1','https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/city-sidequest-observation.jpg'),
 (98002,100000,'rehearsal-cover-2','https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/clothing-care-bingo.jpg'),
 (98003,100000,'rehearsal-cover-3','https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/coffee-flavor-personality.jpg'),
 (98004,100000,'rehearsal-cover-4','https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/color-co-creation.jpg'),
 (98005,100000,'rehearsal-cover-5','https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/detail-observation-blitz.jpg'),
 (98006,100000,'rehearsal-cover-6','https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/duo-memory-relay.jpg'),
 (98007,100000,'rehearsal-cover-7','https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/family-safety-check-relay.jpg'),
 (98008,100000,'rehearsal-cover-8','https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/festival-story-puzzle.jpg'),
 (98009,100000,'rehearsal-cover-9','https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/fitness-reaction-60.jpg'),
 (98010,100000,'rehearsal-cover-10','https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/gift-match-guess.jpg'),
 (98011,100000,'rehearsal-cover-11','https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/gym-charity-sprint.jpg'),
 (98012,100000,'rehearsal-cover-12','https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/hidden-menu-clue-chain.jpg'),
 (98013,100000,'rehearsal-cover-13','https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/home-safety-mines.jpg'),
 (98014,100000,'rehearsal-cover-14','https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/keepsake-treasure-clues.jpg'),
 (98015,100000,'rehearsal-cover-15','https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/menu-co-creation.jpg'),
 (98016,100000,'rehearsal-cover-16','https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/movement-bingo.jpg'),
 (98017,100000,'rehearsal-cover-17','https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/original-role-mystery.jpg'),
 (98018,100000,'rehearsal-cover-18','https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/partner-training-relay.jpg'),
 (98019,100000,'rehearsal-cover-19','https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/performance-bingo.jpg'),
 (98020,100000,'rehearsal-cover-20','https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/repair-tool-guess.jpg'),
 (98021,100000,'rehearsal-cover-21','https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/rhythm-blind-guess.jpg'),
 (98022,100000,'rehearsal-cover-22','https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/route-badge-story.jpg'),
 (98023,100000,'rehearsal-cover-23','https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/seasonal-storage-challenge.jpg'),
 (98024,100000,'rehearsal-cover-24','https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/self-care-bingo.jpg'),
 (98025,100000,'rehearsal-cover-25','https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/signature-dish-blitz.jpg'),
 (98026,100000,'rehearsal-cover-26','https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/song-mood-relay.jpg'),
 (98027,100000,'rehearsal-cover-27','https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/space-styling-challenge.jpg'),
 (98028,100000,'rehearsal-cover-28','https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/stain-detective.jpg'),
 (98029,100000,'rehearsal-cover-29','https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/style-personality-lab.jpg'),
 (98030,100000,'rehearsal-cover-30','https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/table-flavor-relay.jpg'),
 (98031,100000,'rehearsal-cover-31','https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/table-mission-bingo.jpg'),
 (98032,100000,'rehearsal-cover-32','https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/team-energy-relay.jpg'),
 (98033,100000,'rehearsal-cover-33','https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/travel-partner-decisions.jpg'),
 (98034,100000,'rehearsal-cover-34','https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/travel-safety-bingo.jpg');

INSERT INTO cms_member_template(id, member_id, title, img_url) VALUES (98100,100000,'rehearsal-member-cover','https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/branch-story-relay.jpg');

-- 官方模板种子规则原文（20261111_rule_seed_split_steps）
INSERT INTO cms_template_library(id, member_id, title, rule_instructions) VALUES (98200,100000,'rehearsal-rule-seed','1. 到达指定门店。2. 向店员说出今日暗号。3. 输入暗号或扫码完成任务,领取隐藏奖励。(运营提示:暗号每天可换一次;商家必须提前知道暗号和奖励,否则现场体验会断)');

INSERT INTO cms_member_template(id, member_id, title, rule_instructions) VALUES (98201,100000,'rehearsal-rule-seed','1. 到达指定门店。2. 向店员说出今日暗号。3. 输入暗号或扫码完成任务,领取隐藏奖励。(运营提示:暗号每天可换一次;商家必须提前知道暗号和奖励,否则现场体验会断)');

-- 野生动物主题（20261028_remove_wildlife_theme）与模板货架（20261028_template_shelf_list_free_explore）
INSERT INTO cms_topic(id, name, member_id, del_flag) VALUES (990052,'野生邻居观察局',100000,0);
INSERT INTO cms_topic(id, name, member_id, del_flag, is_template, status, user_status) VALUES (98300,'风味巡游',100000,0,1,1,0);

-- 主题总时长按分钟误存（20261104_topic_total_time_seconds）：节点 30+60 分钟，total_time=90
INSERT INTO cms_topic(id, name, member_id, del_flag, total_time) VALUES (98400,'rehearsal-minutes',100000,0,90);
INSERT INTO cms_topic_node(id, topic_id, node_time) VALUES (98401,98400,30),(98402,98400,60);

-- 俱乐部场次集合点（20261102_club_session_gather_point）
INSERT INTO cms_activity(id, club_id, del_flag, end_date) VALUES (98500,9001,0,DATE_ADD(NOW(), INTERVAL 7 DAY));
INSERT INTO oms_ticket(id, member_id, owner_type, owner_id, gather_lat, gather_lng, del_flag) VALUES (98501,9801,2,98500,31.230000,121.470000,0);

-- 官方模板 990059 文案（20261101_prefab_life_template_copy）
INSERT INTO cms_topic(id, name, member_id, del_flag, create_by, description) VALUES (990059,'预制人生',100000,0,'seed_prefab_life_20260918','本主题是内部试玩版,不进公开列表;持 0 元自玩通行证即可开走。');
INSERT INTO cms_member_template(id, member_id, title) VALUES (133,100000,'PL01-出生登记:写下你叫什么');
INSERT INTO cms_member_template(id, member_id, title, advanced_config_json) VALUES (135,100000,'PL03-会议出口:hello world','{"target":"hello world"}');
INSERT INTO cms_topic_chapter(id, topic_id, blocks) VALUES (212,990059,'[{"type":"node","nodeId":194}]'),(213,990059,'[{"type":"text","content":"小周点了一根烟。"}]');

-- @@before-round 4_OWNER_DATA_PRE
-- 以下在第 1 轮建表之后、第 4 轮之前写入（模拟新 jar 上线后产生的发票申请）。
-- 发票处理回填（20261027_user_invoice_processing_backfill）：一张可映射、一张不可映射
INSERT INTO user_invoice_issuer(id, issuer_name, reconciliation_user_id) VALUES (98600,'rehearsal-issuer',9900);
INSERT INTO user_invoice_issuer_owner(owner_type, owner_id, issuer_id) VALUES (1,98610,98600);
INSERT INTO cms_registration(id, registration_no, member_id, owner_type, owner_id, topic_id, original_amount, total_amount, payable_amount, pay_expire_time, create_time) VALUES
 (98611,'rehearsal-inv-1',9801,1,98610,98610,10,10,10,NOW(),NOW()),(98612,'rehearsal-inv-2',9801,1,98699,98699,10,10,10,NOW(),NOW());
INSERT INTO user_invoice_application(id, application_no, member_id, request_id, title_id, title_name, title_type, order_count, total_amount, create_time) VALUES
 (98621,'rehearsal-app-1',9801,'rehearsal-req-1',1,'t',1,1,10,NOW()),(98622,'rehearsal-app-2',9801,'rehearsal-req-2',1,'t',1,1,10,NOW());
INSERT INTO user_invoice_application_order(application_id, member_id, order_source, order_id, amount, create_time) VALUES
 (98621,9801,'registration',98611,10,NOW()),(98622,9801,'registration',98612,10,NOW());
