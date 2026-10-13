-- runner: requires=transaction
-- ============================================================================
-- 首页「自己玩」活动内容种子（8 场 · 上海 · 窗口期至 2026-10-08）
--
-- 起因：玩家首页三个消费 cms_activity 的区块（附近活动 / 即将上线 / 底部「活动」页签）
--       全空。库里 8 条存量活动里 7 条 end_date=2026-03-28 已过期，公开 Mapper 的
--       `isNew=1 ⇒ end_date > now()` 把它们全滤掉了。这里补真实内容，不改存量行。
--
-- 产品边界（决定了这批为什么可以直接上架）：
--   · 全部「自己玩」：单人自助完成，无集合、无领队、无商家接待、无核销；
--   · club_id 为 NULL ⇒ 公域可见（公开 Mapper 的 publicOnly 闸）；
--   · topic_id 为 NULL ⇒ 不是玩法会话，没有节点/章节/entitlement，
--     purchase_kind 恒不为 EXPLORE_PASS，不碰探店日那套资损结构；
--   · 票价 ¥0 ⇒ 走 RegistrationCheckoutServiceImpl 的 completeZeroAmountPayment
--     零元单分支，不进微信支付、不进资金链、不产生结算与退款义务；
--   · 封面复用生产机 /profile 下已有素材（本批落库前逐条 curl 回读 200）。
--
-- 状态取值按存量口径：status=1（公开 Mapper 硬闸，前端渲染「即将开始」）、
-- publish_status=1、del_flag=0、check_status=1、cancel_time 为 NULL。
--
-- 幂等键：cms_activity(member_id=100000, name)；票以 (owner_type=2, owner_id) 判。
-- 纯 DML，不创建/修改表结构。
-- ============================================================================

-- ======================= 一、8 场自己玩活动 =======================
-- 走临时表而非派生表：--transaction 白名单拒绝 `FROM (` 形状（无法证明读集合）。

DROP TEMPORARY TABLE IF EXISTS seed_home_solo_activities;
CREATE TEMPORARY TABLE seed_home_solo_activities (
  name VARCHAR(80) NOT NULL PRIMARY KEY,
  description VARCHAR(500) NOT NULL,
  img VARCHAR(255) NOT NULL,
  category_ids VARCHAR(32) NOT NULL,
  address_name VARCHAR(120) NOT NULL,
  address VARCHAR(200) NOT NULL,
  longitude VARCHAR(32) NOT NULL,
  latitude VARCHAR(32) NOT NULL,
  start_date DATETIME NOT NULL,
  end_date DATETIME NOT NULL
) ENGINE=InnoDB;

INSERT INTO seed_home_solo_activities
       (name, description, img, category_ids, address_name, address,
        longitude, latitude, start_date, end_date)
SELECT '苏州河，一个人走完九座桥',
               '从外白渡桥出发沿苏州河向西，依次走过九座桥，全程约 6 公里、两个半小时。一个人就能完成：不集合、不需要领队、走到哪算哪，累了随时收工。每座桥拍下桥名牌当作自己的记录。全程公共步道与人行桥面，不需要任何商家配合。请走人行道、别翻护栏；雨天路面湿滑或遇桥梁检修封闭时改期。',
               'https://www.chengyinhub.com/prod-api/profile/template-covers/t21-solo-departure.webp',
               '13',
               '外白渡桥南堍',
               '上海市黄浦区中山东一路33号 外白渡桥南侧步道',
               '121.489400', '31.245200',
               '2026-08-26 08:00:00', '2026-10-08 20:00:00'
  UNION ALL SELECT '一个人的美术馆下午',
               '给自己留半个下午，一个人去西岸看一场展。不做攻略、不赶场，只挑一件真正让你停下来的作品，坐下来看满十分钟，再用一句话写下它让你想到什么。展览由场馆自行售票，本活动不代售、不代订，只提供一份一个人也能舒服完成的看展节奏。闭馆日与临时布展期请以场馆公告为准。',
               'https://www.chengyinhub.com/prod-api/profile/template-covers/t26-shanghai-midlife-day.webp',
               '16', '西岸美术馆', '上海市徐汇区龙腾大道2600号 西岸美术馆入口',
               '121.459300', '31.173700',
               '2026-08-27 13:00:00', '2026-10-07 20:00:00'
  UNION ALL SELECT '独立书店巡游：五家店，五本没听过的书',
               '沿衡山路、乌鲁木齐中路一带走五家独立书店，每家只做一件事：找一本你完全没听说过的书，读完前三页再决定要不要带走。不买也算完成。一个人去最合适——不用迁就同伴的节奏，也不用解释为什么在一本书前站了二十分钟。请遵守各店的拍照与试读规定，营业时间以门店当日公告为准。',
               'https://www.chengyinhub.com/prod-api/profile/topic-covers/prebuilt-life.webp',
               '25', '衡山路·乌中路书店片区', '上海市徐汇区衡山路880号 起点',
               '121.448200', '31.203200',
               '2026-08-26 10:00:00', '2026-10-06 21:00:00'
  UNION ALL SELECT '清晨菜场味觉考：一个人的早市',
               '早上七点半，一个人去一次菜场。任务只有三件：认出三种你叫不出名字的时令菜、问清其中一种怎么做、买一样今天就吃得掉的东西。全程半小时，比闹钟更能让人真的醒过来。摊主在做生意，问一句就好，别长时间占着摊位；现金与移动支付请提前备好。',
               'https://www.chengyinhub.com/prod-api/profile/template-covers/t25-shanghai-flavor-tour.webp',
               '15', '乌中市集', '上海市徐汇区乌鲁木齐中路318号 乌中市集',
               '121.446200', '31.211800',
               '2026-09-01 07:00:00', '2026-10-06 12:00:00'
  UNION ALL SELECT '夜色外滩·独行摄影任务',
               '天黑后一个人去外滩源，只拍三张：一张不含任何人的建筑局部、一张有陌生人但看不清脸的街景、一张你自己的影子。手机就够，不需要器材。拍完选一张留下，其余两张删掉——这一步才是任务本身。夜间注意随身物品，人流密集处请听从现场指引，不要为取景越过围栏或站上台阶边沿。',
               'https://www.chengyinhub.com/prod-api/profile/template-covers/t24-night-owl-city.webp',
               '16', '外滩源·圆明园路', '上海市黄浦区圆明园路169号 外滩源入口',
               '121.487400', '31.240400',
               '2026-09-05 18:30:00', '2026-10-08 22:00:00'
  UNION ALL SELECT '梧桐区慢跑五公里：不看配速那一次',
               '从武康大楼出发，沿武康路、五原路、安福路跑一圈，五公里，全程不看配速也不发朋友圈。一个人跑，想停就停，遇到好看的路口可以走过去。终点回到起点时记下自己当时的第一个念头。人行道行人较多，请降速避让、礼让行人；高温或空气质量预警日改期。',
               'https://www.chengyinhub.com/prod-api/profile/template-covers/t20-after-work-switch.webp',
               '13', '武康大楼', '上海市徐汇区淮海中路1850号 武康大楼路口',
               '121.433100', '31.208700',
               '2026-09-12 08:00:00', '2026-10-07 20:00:00'
  UNION ALL SELECT '滨江骑行十五公里：一个人的江边',
               '沿徐汇滨江骑行道往南骑十五公里再折返，共享单车即可，全程无机动车混行。一个人骑最自在：不用等人、不用商量在哪里掉头。中途找一处江边台阶停下来待够十分钟，什么都不做。请佩戴合适装备、遵守骑行道限速与单向指引，风力较大或雨天改期。',
               'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/route-badge-story.jpg',
               '13', '徐汇滨江骑行驿站', '上海市徐汇区龙腾大道3398号 龙美术馆前广场',
               '121.457800', '31.164600',
               '2026-09-19 09:00:00', '2026-10-08 20:00:00'
  UNION ALL SELECT '老城厢：找出十个没人拍过的细节',
               '一个人走进文庙与老西门之间的小马路，找十个几乎不会被拍到的细节：门牌的旧编号、砖缝里的植物、被漆盖过的招牌、修过三次的排水口……找到就拍，凑满十个结束。这一带住着人，请压低音量、不进私人院落、不拍摄居民与室内，遇到施工围挡绕行。',
               'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/city-sidequest-observation.jpg',
               '18', '文庙路', '上海市黄浦区文庙路215号 文庙门口',
               '121.487700', '31.222500',
               '2026-09-26 09:00:00', '2026-10-08 20:00:00';

INSERT INTO cms_activity
       (member_id, name, description, img_url, img_arr, category_ids,
        address_name, address, longitude, latitude,
        status, publish_status, del_flag, check_status, is_settlement,
        start_date, end_date, create_by, create_time)
SELECT 100000, s.name, s.description, s.img, s.img, s.category_ids,
       s.address_name, s.address, s.longitude, s.latitude,
       1, 1, 0, 1, 0,
       s.start_date, s.end_date, 'seed_home_solo_activities_20260826', NOW()
  FROM seed_home_solo_activities s
 WHERE NOT EXISTS (SELECT 1 FROM cms_activity a
                    WHERE a.member_id = 100000 AND a.name = s.name AND a.del_flag = 0);

-- ======================= 二、每场一张 ¥0 单人自助票 =======================
-- price=0 ⇒ 报名走零元单分支（RegistrationCheckoutServiceImpl.finishOrder），不进微信支付。
-- end_time 必须晚于当下，否则 scene-play-activity-detail 的 openSignup 判「已截止报名」。

INSERT INTO oms_ticket
       (member_id, owner_type, owner_id, name, price,
        start_time, end_time, signup_deadline,
        total_inventory, remaining_inventory, mode, team_size,
        team_status, need_audit, del_flag, create_by, create_time)
SELECT 100000, 2, a.id, '单人自助票', 0.00,
       NOW(), a.end_date, a.end_date,
       200, 200, 1, 0,
       0, 0, 0, 'seed_home_solo_activities_20260826', NOW()
  FROM cms_activity a
 WHERE a.member_id = 100000
   AND a.del_flag = 0
   AND a.create_by = 'seed_home_solo_activities_20260826'
   AND NOT EXISTS (SELECT 1 FROM oms_ticket t
                    WHERE t.owner_type = 2 AND t.owner_id = a.id AND t.del_flag = 0);

DROP TEMPORARY TABLE IF EXISTS seed_home_solo_activities;
