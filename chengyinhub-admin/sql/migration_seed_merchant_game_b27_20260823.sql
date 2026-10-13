-- ============================================================================
-- 商家互动游戏组合 B 档补齐 27 款 与 A8 合计 35 款
--
-- 落点 cms_template_library 公共节点玩法库
-- 仅使用当前高级玩法运行时 timer random branch leaderboard multiplayer
-- 跨周期战绩 全店共同进度 私密答案聚合 用户票选等能力未实现 不在本迁移中冒充
-- 本文件只随代码交付 不自动执行
-- 生产执行必须走 chengyin-db-migrate 并做落库后业务回读
-- runner: requires=transaction
-- ============================================================================

CREATE TEMPORARY TABLE seed_merchant_game_b27_guard (
    id INT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;
INSERT INTO seed_merchant_game_b27_guard (id) VALUES (1);

CREATE TEMPORARY TABLE seed_merchant_game_b27_config (
    slug VARCHAR(64) NOT NULL PRIMARY KEY,
    advanced_config JSON NOT NULL
) ENGINE=InnoDB;

CREATE TEMPORARY TABLE seed_merchant_game_b27_spec (
    slug VARCHAR(64) NOT NULL PRIMARY KEY,
    title VARCHAR(64) NOT NULL UNIQUE,
    category_name VARCHAR(64) NOT NULL,
    mechanics VARCHAR(128) NOT NULL,
    description VARCHAR(500) NOT NULL,
    players VARCHAR(64) NOT NULL,
    usage_location VARCHAR(255) NOT NULL,
    required_materials VARCHAR(255) NOT NULL,
    duration INT NOT NULL,
    rule_instructions VARCHAR(2000) NOT NULL,
    image_path VARCHAR(255) NULL,
    activity_categoryids VARCHAR(32) NULL,
    advanced_config JSON NULL
) ENGINE=InnoDB;

CREATE TEMPORARY TABLE seed_merchant_game_b27_media (
    slug VARCHAR(64) NOT NULL PRIMARY KEY,
    image_path VARCHAR(255) NOT NULL UNIQUE
) ENGINE=InnoDB;

INSERT INTO seed_merchant_game_b27_media (slug, image_path) VALUES
    ('table_flavor_relay', 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/table-flavor-relay.jpg'),
    ('table_mission_bingo', 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/table-mission-bingo.jpg'),
    ('menu_co_creation', 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/menu-co-creation.jpg'),
    ('team_energy_relay', 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/team-energy-relay.jpg'),
    ('partner_training_relay', 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/partner-training-relay.jpg'),
    ('movement_bingo', 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/movement-bingo.jpg'),
    ('gym_charity_sprint', 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/gym-charity-sprint.jpg'),
    ('song_mood_relay', 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/song-mood-relay.jpg'),
    ('performance_bingo', 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/performance-bingo.jpg'),
    ('original_role_mystery', 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/original-role-mystery.jpg'),
    ('style_personality_lab', 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/style-personality-lab.jpg'),
    ('color_co_creation', 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/color-co-creation.jpg'),
    ('detail_observation_blitz', 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/detail-observation-blitz.jpg'),
    ('self_care_bingo', 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/self-care-bingo.jpg'),
    ('space_styling_challenge', 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/space-styling-challenge.jpg'),
    ('duo_memory_relay', 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/duo-memory-relay.jpg'),
    ('gift_match_guess', 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/gift-match-guess.jpg'),
    ('keepsake_treasure_clues', 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/keepsake-treasure-clues.jpg'),
    ('festival_story_puzzle', 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/festival-story-puzzle.jpg'),
    ('clothing_care_bingo', 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/clothing-care-bingo.jpg'),
    ('seasonal_storage_challenge', 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/seasonal-storage-challenge.jpg'),
    ('repair_tool_guess', 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/repair-tool-guess.jpg'),
    ('family_safety_check_relay', 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/family-safety-check-relay.jpg'),
    ('city_sidequest_observation', 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/city-sidequest-observation.jpg'),
    ('travel_partner_decisions', 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/travel-partner-decisions.jpg'),
    ('travel_safety_bingo', 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/travel-safety-bingo.jpg'),
    ('route_badge_story', 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/route-badge-story.jpg');

-- TEMPLATE table_flavor_relay|同桌口味接力|美食烹饪|random,branch,multiplayer
INSERT INTO seed_merchant_game_b27_config (slug, advanced_config)
VALUES ('table_flavor_relay', '{"schemaVersion":1,"random":{"enabled":true,"drawCount":1,"items":[{"id":"table_flavor_relay_a","label":"清爽词卡","weight":10,"content":"柑橘、花香、脆感任选一词描述"},{"id":"table_flavor_relay_b","label":"醇厚词卡","weight":10,"content":"可可、坚果、绵密任选一词描述"},{"id":"table_flavor_relay_c","label":"反差词卡","weight":10,"content":"用酸甜或冷热反差描述一道菜"}]},"branch":{"enabled":true,"startStepId":"start","steps":[{"id":"start","title":"同桌口味接力路径","body":"根据本轮题面选择最符合小队想法的方向","terminal":false,"options":[{"id":"path_a","label":"走清爽路线","nextStepId":"finish_a","score":10},{"id":"path_b","label":"走醇厚路线","nextStepId":"finish_b","score":10}]},{"id":"finish_a","title":"路线一结局","body":"你们拼出了本桌口味地图 选择：走清爽路线","outcomeCode":"finish_a","outcomeLabel":"路线一结局","terminal":true,"options":[]},{"id":"finish_b","title":"路线二结局","body":"你们拼出了本桌口味地图 选择：走醇厚路线","outcomeCode":"finish_b","outcomeLabel":"路线二结局","terminal":true,"options":[]}]},"multiplayer":{"enabled":true,"mode":"ROLE_BASED","minPlayers":2,"maxPlayers":4,"assignment":"AUTO","roles":[{"id":"starter","label":"风味描述者","min":1,"max":2},{"id":"finisher","label":"路线选择者","min":1,"max":2}],"turnOrder":["starter","finisher"],"requiredTurns":3,"unitScore":10}}');

-- TEMPLATE table_mission_bingo|一桌任务宾果|美食烹饪|random,multiplayer
INSERT INTO seed_merchant_game_b27_config (slug, advanced_config)
VALUES ('table_mission_bingo', '{"schemaVersion":1,"random":{"enabled":true,"drawCount":3,"items":[{"id":"table_mission_bingo_a","label":"菜单侦探","weight":10,"content":"找出菜单中一个重复出现的关键词"},{"id":"table_mission_bingo_b","label":"同桌推荐官","weight":10,"content":"向同伴推荐一个不以价格为理由的选择"},{"id":"table_mission_bingo_c","label":"一句话食评","weight":10,"content":"用十个字描述此刻最喜欢的味道"}]},"multiplayer":{"enabled":true,"mode":"ROLE_BASED","minPlayers":2,"maxPlayers":4,"assignment":"AUTO","roles":[{"id":"starter","label":"任务发起者","min":1,"max":2},{"id":"finisher","label":"宾果接力者","min":1,"max":2}],"turnOrder":["starter","finisher"],"requiredTurns":3,"unitScore":10}}');

-- TEMPLATE menu_co_creation|菜单共创接力|美食烹饪|random,branch,multiplayer
INSERT INTO seed_merchant_game_b27_config (slug, advanced_config)
VALUES ('menu_co_creation', '{"schemaVersion":1,"random":{"enabled":true,"drawCount":1,"items":[{"id":"menu_co_creation_a","label":"香气灵感","weight":10,"content":"从花香、烘烤或香料中选一个主角"},{"id":"menu_co_creation_b","label":"口感灵感","weight":10,"content":"从酥脆、绵密或清爽中选一个口感"},{"id":"menu_co_creation_c","label":"场景灵感","weight":10,"content":"为清晨、午后或夜晚设计限定组合"}]},"branch":{"enabled":true,"startStepId":"start","steps":[{"id":"start","title":"菜单共创接力路径","body":"根据本轮题面选择最符合小队想法的方向","terminal":false,"options":[{"id":"path_a","label":"先定风味主角","nextStepId":"finish_a","score":10},{"id":"path_b","label":"先定口感反差","nextStepId":"finish_b","score":10}]},{"id":"finish_a","title":"路线一结局","body":"本桌限定菜单故事已经完成 选择：先定风味主角","outcomeCode":"finish_a","outcomeLabel":"路线一结局","terminal":true,"options":[]},{"id":"finish_b","title":"路线二结局","body":"本桌限定菜单故事已经完成 选择：先定口感反差","outcomeCode":"finish_b","outcomeLabel":"路线二结局","terminal":true,"options":[]}]},"multiplayer":{"enabled":true,"mode":"ROLE_BASED","minPlayers":2,"maxPlayers":4,"assignment":"AUTO","roles":[{"id":"starter","label":"限定命名者","min":1,"max":2},{"id":"finisher","label":"风味搭配者","min":1,"max":2}],"turnOrder":["starter","finisher"],"requiredTurns":3,"unitScore":10}}');

-- TEMPLATE team_energy_relay|小队能量接力|运动健身|multiplayer,leaderboard
INSERT INTO seed_merchant_game_b27_config (slug, advanced_config)
VALUES ('team_energy_relay', '{"schemaVersion":1,"leaderboard":{"enabled":true,"metric":"ELAPSED_TIME","scope":"ACTIVITY","limit":50},"multiplayer":{"enabled":true,"mode":"ROLE_BASED","minPlayers":2,"maxPlayers":4,"assignment":"AUTO","roles":[{"id":"starter","label":"安全口令员","min":1,"max":2},{"id":"finisher","label":"能量接力员","min":1,"max":2}],"turnOrder":["starter","finisher"],"requiredTurns":3,"unitScore":10}}');

-- TEMPLATE partner_training_relay|搭子训练接力|运动健身|timer,multiplayer,leaderboard
INSERT INTO seed_merchant_game_b27_config (slug, advanced_config)
VALUES ('partner_training_relay', '{"schemaVersion":1,"timer":{"enabled":true,"durationSeconds":240,"timeoutResult":"FAILED"},"leaderboard":{"enabled":true,"metric":"ELAPSED_TIME","scope":"ACTIVITY","limit":50},"multiplayer":{"enabled":true,"mode":"ROLE_BASED","minPlayers":2,"maxPlayers":4,"assignment":"AUTO","roles":[{"id":"starter","label":"训练读题者","min":1,"max":2},{"id":"finisher","label":"安全判断者","min":1,"max":2}],"turnOrder":["starter","finisher"],"requiredTurns":3,"unitScore":10}}');

-- TEMPLATE movement_bingo|动作宾果|运动健身|random,multiplayer
INSERT INTO seed_merchant_game_b27_config (slug, advanced_config)
VALUES ('movement_bingo', '{"schemaVersion":1,"random":{"enabled":true,"drawCount":3,"items":[{"id":"movement_bingo_a","label":"热身格","weight":10,"content":"说出一个开始训练前的热身要点"},{"id":"movement_bingo_b","label":"器械格","weight":10,"content":"说出使用器械前的一项检查"},{"id":"movement_bingo_c","label":"恢复格","weight":10,"content":"说出运动后补水或休息的一项提示"}]},"multiplayer":{"enabled":true,"mode":"ROLE_BASED","minPlayers":2,"maxPlayers":4,"assignment":"AUTO","roles":[{"id":"starter","label":"动作抽卡者","min":1,"max":2},{"id":"finisher","label":"安全提示者","min":1,"max":2}],"turnOrder":["starter","finisher"],"requiredTurns":3,"unitScore":10}}');

-- TEMPLATE gym_charity_sprint|全馆公益热身接力|运动健身|timer,multiplayer,leaderboard
INSERT INTO seed_merchant_game_b27_config (slug, advanced_config)
VALUES ('gym_charity_sprint', '{"schemaVersion":1,"timer":{"enabled":true,"durationSeconds":300,"timeoutResult":"FAILED"},"leaderboard":{"enabled":true,"metric":"ELAPSED_TIME","scope":"ACTIVITY","limit":50},"multiplayer":{"enabled":true,"mode":"ROLE_BASED","minPlayers":2,"maxPlayers":4,"assignment":"AUTO","roles":[{"id":"starter","label":"公益口令员","min":1,"max":2},{"id":"finisher","label":"热身接力员","min":1,"max":2}],"turnOrder":["starter","finisher"],"requiredTurns":4,"unitScore":10}}');

-- TEMPLATE song_mood_relay|点歌情绪接力|娱乐达人|random,branch,multiplayer
INSERT INTO seed_merchant_game_b27_config (slug, advanced_config)
VALUES ('song_mood_relay', '{"schemaVersion":1,"random":{"enabled":true,"drawCount":1,"items":[{"id":"song_mood_relay_a","label":"热烈时刻","weight":10,"content":"用原创词描述一段适合全场合唱的气氛"},{"id":"song_mood_relay_b","label":"松弛时刻","weight":10,"content":"用原创词描述一段适合轻声哼唱的气氛"},{"id":"song_mood_relay_c","label":"反转时刻","weight":10,"content":"把忧郁场景改写成明亮结尾"}]},"branch":{"enabled":true,"startStepId":"start","steps":[{"id":"start","title":"点歌情绪接力路径","body":"根据本轮题面选择最符合小队想法的方向","terminal":false,"options":[{"id":"path_a","label":"选择热烈路线","nextStepId":"finish_a","score":10},{"id":"path_b","label":"选择松弛路线","nextStepId":"finish_b","score":10}]},{"id":"finish_a","title":"路线一结局","body":"本桌点歌氛围已经生成 选择：选择热烈路线","outcomeCode":"finish_a","outcomeLabel":"路线一结局","terminal":true,"options":[]},{"id":"finish_b","title":"路线二结局","body":"本桌点歌氛围已经生成 选择：选择松弛路线","outcomeCode":"finish_b","outcomeLabel":"路线二结局","terminal":true,"options":[]}]},"multiplayer":{"enabled":true,"mode":"ROLE_BASED","minPlayers":2,"maxPlayers":4,"assignment":"AUTO","roles":[{"id":"starter","label":"情绪描述者","min":1,"max":2},{"id":"finisher","label":"氛围选择者","min":1,"max":2}],"turnOrder":["starter","finisher"],"requiredTurns":3,"unitScore":10}}');

-- TEMPLATE performance_bingo|表演任务宾果|娱乐达人|random,multiplayer
INSERT INTO seed_merchant_game_b27_config (slug, advanced_config)
VALUES ('performance_bingo', '{"schemaVersion":1,"random":{"enabled":true,"drawCount":3,"items":[{"id":"performance_bingo_a","label":"一句台词","weight":10,"content":"用不同情绪说出同一句原创台词"},{"id":"performance_bingo_b","label":"四拍节奏","weight":10,"content":"用桌面轻拍一个原创四拍节奏"},{"id":"performance_bingo_c","label":"定格动作","weight":10,"content":"做一个三秒钟安全定格动作"}]},"multiplayer":{"enabled":true,"mode":"ROLE_BASED","minPlayers":2,"maxPlayers":4,"assignment":"AUTO","roles":[{"id":"starter","label":"舞台发起者","min":1,"max":2},{"id":"finisher","label":"表演接力者","min":1,"max":2}],"turnOrder":["starter","finisher"],"requiredTurns":3,"unitScore":10}}');

-- TEMPLATE original_role_mystery|原创角色协作局|娱乐达人|branch,multiplayer
INSERT INTO seed_merchant_game_b27_config (slug, advanced_config)
VALUES ('original_role_mystery', '{"schemaVersion":1,"branch":{"enabled":true,"startStepId":"start","steps":[{"id":"start","title":"原创角色协作局路径","body":"公开线索：前台钟比实际时间慢五分钟；蓝色票根标记19:05；玻璃杯在19:10才被移到窗边。问题：先核对时间线还是先核对物件位置，哪条路径更能解释票根出现顺序？","terminal":false,"options":[{"id":"path_a","label":"先核对时间线","nextStepId":"finish_a","score":10},{"id":"path_b","label":"先核对物件线索","nextStepId":"finish_b","score":10}]},{"id":"finish_a","title":"路线一结局","body":"时间线路径：校正前台钟后，票根早于玻璃杯移动出现，记录这一共同结论。","outcomeCode":"finish_a","outcomeLabel":"路线一结局","terminal":true,"options":[]},{"id":"finish_b","title":"路线二结局","body":"物件路径：杯子位置只能证明19:10后的现场，仍需用票根补全较早时间线。","outcomeCode":"finish_b","outcomeLabel":"路线二结局","terminal":true,"options":[]}]},"multiplayer":{"enabled":true,"mode":"ROLE_BASED","minPlayers":2,"maxPlayers":4,"assignment":"AUTO","roles":[{"id":"starter","label":"现场观察者","min":1,"max":2},{"id":"finisher","label":"线索记录者","min":1,"max":2}],"turnOrder":["starter","finisher"],"requiredTurns":3,"unitScore":10}}');

-- TEMPLATE style_personality_lab|风格人格实验室|生活美学|random,branch
INSERT INTO seed_merchant_game_b27_config (slug, advanced_config)
VALUES ('style_personality_lab', '{"schemaVersion":1,"random":{"enabled":true,"drawCount":1,"items":[{"id":"style_personality_lab_a","label":"留白关键词","weight":10,"content":"简洁、克制、清透"},{"id":"style_personality_lab_b","label":"层次关键词","weight":10,"content":"叠加、纹理、丰富"},{"id":"style_personality_lab_c","label":"亮点关键词","weight":10,"content":"挑一个最想被看见的细节"}]},"branch":{"enabled":true,"startStepId":"start","steps":[{"id":"start","title":"风格人格实验室路径","body":"根据本轮题面选择最符合小队想法的方向","terminal":false,"options":[{"id":"path_a","label":"选择克制留白","nextStepId":"finish_a","score":10},{"id":"path_b","label":"选择大胆亮点","nextStepId":"finish_b","score":10}]},{"id":"finish_a","title":"路线一结局","body":"你的当日风格人格已经生成 选择：选择克制留白","outcomeCode":"finish_a","outcomeLabel":"路线一结局","terminal":true,"options":[]},{"id":"finish_b","title":"路线二结局","body":"你的当日风格人格已经生成 选择：选择大胆亮点","outcomeCode":"finish_b","outcomeLabel":"路线二结局","terminal":true,"options":[]}]}}');

-- TEMPLATE color_co_creation|色彩共创接力|生活美学|random,branch,multiplayer
INSERT INTO seed_merchant_game_b27_config (slug, advanced_config)
VALUES ('color_co_creation', '{"schemaVersion":1,"random":{"enabled":true,"drawCount":1,"items":[{"id":"color_co_creation_a","label":"暖色词","weight":10,"content":"日落、琥珀、陶土"},{"id":"color_co_creation_b","label":"冷色词","weight":10,"content":"海面、雾蓝、薄荷"},{"id":"color_co_creation_c","label":"中性色词","weight":10,"content":"米白、岩灰、木色"}]},"branch":{"enabled":true,"startStepId":"start","steps":[{"id":"start","title":"色彩共创接力路径","body":"根据本轮题面选择最符合小队想法的方向","terminal":false,"options":[{"id":"path_a","label":"选择同色层次","nextStepId":"finish_a","score":10},{"id":"path_b","label":"选择对比碰撞","nextStepId":"finish_b","score":10}]},{"id":"finish_a","title":"路线一结局","body":"小队口头色板已经完成 选择：选择同色层次","outcomeCode":"finish_a","outcomeLabel":"路线一结局","terminal":true,"options":[]},{"id":"finish_b","title":"路线二结局","body":"小队口头色板已经完成 选择：选择对比碰撞","outcomeCode":"finish_b","outcomeLabel":"路线二结局","terminal":true,"options":[]}]},"multiplayer":{"enabled":true,"mode":"ROLE_BASED","minPlayers":2,"maxPlayers":4,"assignment":"AUTO","roles":[{"id":"starter","label":"色彩命名者","min":1,"max":2},{"id":"finisher","label":"搭配接力者","min":1,"max":2}],"turnOrder":["starter","finisher"],"requiredTurns":3,"unitScore":10}}');

-- TEMPLATE detail_observation_blitz|细节观察挑战|生活美学|timer,branch,leaderboard
INSERT INTO seed_merchant_game_b27_config (slug, advanced_config)
VALUES ('detail_observation_blitz', '{"schemaVersion":1,"timer":{"enabled":true,"durationSeconds":90,"timeoutResult":"FAILED"},"branch":{"enabled":true,"startStepId":"start","steps":[{"id":"start","title":"细节观察挑战路径","body":"根据本轮题面选择最符合小队想法的方向","terminal":false,"options":[{"id":"path_a","label":"先观察形状","nextStepId":"finish_a","score":10},{"id":"path_b","label":"先观察色彩","nextStepId":"finish_b","score":10}]},{"id":"finish_a","title":"路线一结局","body":"90 秒细节观察完成 选择：先观察形状","outcomeCode":"finish_a","outcomeLabel":"路线一结局","terminal":true,"options":[]},{"id":"finish_b","title":"路线二结局","body":"90 秒细节观察完成 选择：先观察色彩","outcomeCode":"finish_b","outcomeLabel":"路线二结局","terminal":true,"options":[]}]},"leaderboard":{"enabled":true,"metric":"ELAPSED_TIME","scope":"ACTIVITY","limit":50}}');

-- TEMPLATE self_care_bingo|日常护理宾果|生活美学|random,branch
INSERT INTO seed_merchant_game_b27_config (slug, advanced_config)
VALUES ('self_care_bingo', '{"schemaVersion":1,"random":{"enabled":true,"drawCount":3,"items":[{"id":"self_care_bingo_a","label":"敏感情境","weight":10,"content":"出现不适时先停止并观察"},{"id":"self_care_bingo_b","label":"清洁情境","weight":10,"content":"先确认产品说明和适用范围"},{"id":"self_care_bingo_c","label":"咨询情境","weight":10,"content":"不确定时向合格专业人员咨询"}]},"branch":{"enabled":true,"startStepId":"start","steps":[{"id":"start","title":"日常护理宾果路径","body":"根据本轮题面选择最符合小队想法的方向","terminal":false,"options":[{"id":"path_a","label":"选择温和观察","nextStepId":"finish_a","score":10},{"id":"path_b","label":"选择停止并求助","nextStepId":"finish_b","score":10}]},{"id":"finish_a","title":"路线一结局","body":"稳妥护理路径已经完成 选择：选择温和观察","outcomeCode":"finish_a","outcomeLabel":"路线一结局","terminal":true,"options":[]},{"id":"finish_b","title":"路线二结局","body":"稳妥护理路径已经完成 选择：选择停止并求助","outcomeCode":"finish_b","outcomeLabel":"路线二结局","terminal":true,"options":[]}]}}');

-- TEMPLATE space_styling_challenge|空间搭配挑战|生活美学|random,branch,leaderboard
INSERT INTO seed_merchant_game_b27_config (slug, advanced_config)
VALUES ('space_styling_challenge', '{"schemaVersion":1,"random":{"enabled":true,"drawCount":1,"items":[{"id":"space_styling_challenge_a","label":"留白空间","weight":10,"content":"找出一处让视线休息的空白"},{"id":"space_styling_challenge_b","label":"层次空间","weight":10,"content":"找出前中后三层陈列关系"},{"id":"space_styling_challenge_c","label":"焦点空间","weight":10,"content":"找出最先吸引目光的物件"}]},"branch":{"enabled":true,"startStepId":"start","steps":[{"id":"start","title":"空间搭配挑战路径","body":"根据本轮题面选择最符合小队想法的方向","terminal":false,"options":[{"id":"path_a","label":"选择留白路线","nextStepId":"finish_a","score":10},{"id":"path_b","label":"选择层次路线","nextStepId":"finish_b","score":10}]},{"id":"finish_a","title":"路线一结局","body":"空间搭配判断已经完成 选择：选择留白路线","outcomeCode":"finish_a","outcomeLabel":"路线一结局","terminal":true,"options":[]},{"id":"finish_b","title":"路线二结局","body":"空间搭配判断已经完成 选择：选择层次路线","outcomeCode":"finish_b","outcomeLabel":"路线二结局","terminal":true,"options":[]}]},"leaderboard":{"enabled":true,"metric":"ELAPSED_TIME","scope":"ACTIVITY","limit":50}}');

-- TEMPLATE duo_memory_relay|双人纪念故事接力|礼品纪念|random,multiplayer
INSERT INTO seed_merchant_game_b27_config (slug, advanced_config)
VALUES ('duo_memory_relay', '{"schemaVersion":1,"random":{"enabled":true,"drawCount":1,"items":[{"id":"duo_memory_relay_a","label":"第一次见面","weight":10,"content":"分享一个愿意公开的初见细节"},{"id":"duo_memory_relay_b","label":"一起完成","weight":10,"content":"分享一次共同完成小事的记忆"},{"id":"duo_memory_relay_c","label":"下次约定","weight":10,"content":"说一个轻松而具体的下次约定"}]},"multiplayer":{"enabled":true,"mode":"ROLE_BASED","minPlayers":2,"maxPlayers":4,"assignment":"AUTO","roles":[{"id":"starter","label":"记忆开场者","min":1,"max":2},{"id":"finisher","label":"故事接力者","min":1,"max":2}],"turnOrder":["starter","finisher"],"requiredTurns":3,"unitScore":10}}');

-- TEMPLATE gift_match_guess|礼物偏好猜想局|礼品纪念|branch,multiplayer
INSERT INTO seed_merchant_game_b27_config (slug, advanced_config)
VALUES ('gift_match_guess', '{"schemaVersion":1,"branch":{"enabled":true,"startStepId":"start","steps":[{"id":"start","title":"礼物偏好猜想局路径","body":"根据本轮题面选择最符合小队想法的方向","terminal":false,"options":[{"id":"path_a","label":"猜实用陪伴","nextStepId":"finish_a","score":10},{"id":"path_b","label":"猜惊喜仪式","nextStepId":"finish_b","score":10}]},{"id":"finish_a","title":"路线一结局","body":"搭档偏好已经当场揭晓 选择：猜实用陪伴","outcomeCode":"finish_a","outcomeLabel":"路线一结局","terminal":true,"options":[]},{"id":"finish_b","title":"路线二结局","body":"搭档偏好已经当场揭晓 选择：猜惊喜仪式","outcomeCode":"finish_b","outcomeLabel":"路线二结局","terminal":true,"options":[]}]},"multiplayer":{"enabled":true,"mode":"ROLE_BASED","minPlayers":2,"maxPlayers":4,"assignment":"AUTO","roles":[{"id":"starter","label":"礼物出题者","min":1,"max":2},{"id":"finisher","label":"偏好猜想者","min":1,"max":2}],"turnOrder":["starter","finisher"],"requiredTurns":3,"unitScore":10}}');

-- TEMPLATE keepsake_treasure_clues|纪念物线索寻宝|礼品纪念|timer,branch
INSERT INTO seed_merchant_game_b27_config (slug, advanced_config)
VALUES ('keepsake_treasure_clues', '{"schemaVersion":1,"timer":{"enabled":true,"durationSeconds":300,"timeoutResult":"FAILED"},"branch":{"enabled":true,"startStepId":"start","steps":[{"id":"start","title":"纪念物线索寻宝路径","body":"根据本轮题面选择最符合小队想法的方向","terminal":false,"options":[{"id":"path_a","label":"从材质线索找","nextStepId":"finish_a","score":10},{"id":"path_b","label":"从故事线索找","nextStepId":"finish_b","score":10}]},{"id":"finish_a","title":"路线一结局","body":"纪念物线索链已经闭合 选择：从材质线索找","outcomeCode":"finish_a","outcomeLabel":"路线一结局","terminal":true,"options":[]},{"id":"finish_b","title":"路线二结局","body":"纪念物线索链已经闭合 选择：从故事线索找","outcomeCode":"finish_b","outcomeLabel":"路线二结局","terminal":true,"options":[]}]}}');

-- TEMPLATE festival_story_puzzle|节日故事拼图|礼品纪念|random,branch,multiplayer
INSERT INTO seed_merchant_game_b27_config (slug, advanced_config)
VALUES ('festival_story_puzzle', '{"schemaVersion":1,"random":{"enabled":true,"drawCount":3,"items":[{"id":"festival_story_puzzle_a","label":"灯亮之前","weight":10,"content":"街角还留着一封没有寄出的祝福"},{"id":"festival_story_puzzle_b","label":"礼物之后","weight":10,"content":"包装里藏着下一段故事的方向"},{"id":"festival_story_puzzle_c","label":"重逢时刻","weight":10,"content":"两位同行人决定把结局讲完"}]},"branch":{"enabled":true,"startStepId":"start","steps":[{"id":"start","title":"节日故事拼图路径","body":"根据本轮题面选择最符合小队想法的方向","terminal":false,"options":[{"id":"path_a","label":"拼成温暖结局","nextStepId":"finish_a","score":10},{"id":"path_b","label":"拼成冒险结局","nextStepId":"finish_b","score":10}]},{"id":"finish_a","title":"路线一结局","body":"原创节日故事已经拼合 选择：拼成温暖结局","outcomeCode":"finish_a","outcomeLabel":"路线一结局","terminal":true,"options":[]},{"id":"finish_b","title":"路线二结局","body":"原创节日故事已经拼合 选择：拼成冒险结局","outcomeCode":"finish_b","outcomeLabel":"路线二结局","terminal":true,"options":[]}]},"multiplayer":{"enabled":true,"mode":"ROLE_BASED","minPlayers":2,"maxPlayers":4,"assignment":"AUTO","roles":[{"id":"starter","label":"片段排序者","min":1,"max":2},{"id":"finisher","label":"结局讲述者","min":1,"max":2}],"turnOrder":["starter","finisher"],"requiredTurns":3,"unitScore":10}}');

-- TEMPLATE clothing_care_bingo|衣物护理宾果|衣物洗护|random,branch
INSERT INTO seed_merchant_game_b27_config (slug, advanced_config)
VALUES ('clothing_care_bingo', '{"schemaVersion":1,"random":{"enabled":true,"drawCount":3,"items":[{"id":"clothing_care_bingo_a","label":"标签格","weight":10,"content":"先确认材质和洗涤标签"},{"id":"clothing_care_bingo_b","label":"温度格","weight":10,"content":"不确定时避免直接使用高温"},{"id":"clothing_care_bingo_c","label":"专业格","weight":10,"content":"贵重或复杂衣物交由专业人员判断"}]},"branch":{"enabled":true,"startStepId":"start","steps":[{"id":"start","title":"衣物护理宾果路径","body":"根据本轮题面选择最符合小队想法的方向","terminal":false,"options":[{"id":"path_a","label":"暂停自行处理","nextStepId":"finish_a","score":10},{"id":"path_b","label":"记录信息再咨询","nextStepId":"finish_b","score":10}]},{"id":"finish_a","title":"路线一结局","body":"衣物护理判断已经完成 选择：暂停自行处理","outcomeCode":"finish_a","outcomeLabel":"路线一结局","terminal":true,"options":[]},{"id":"finish_b","title":"路线二结局","body":"衣物护理判断已经完成 选择：记录信息再咨询","outcomeCode":"finish_b","outcomeLabel":"路线二结局","terminal":true,"options":[]}]}}');

-- TEMPLATE seasonal_storage_challenge|换季收纳挑战|衣物洗护|timer,branch,leaderboard
INSERT INTO seed_merchant_game_b27_config (slug, advanced_config)
VALUES ('seasonal_storage_challenge', '{"schemaVersion":1,"timer":{"enabled":true,"durationSeconds":180,"timeoutResult":"FAILED"},"branch":{"enabled":true,"startStepId":"start","steps":[{"id":"start","title":"换季收纳挑战路径","body":"根据本轮题面选择最符合小队想法的方向","terminal":false,"options":[{"id":"path_a","label":"先清洁再收纳","nextStepId":"finish_a","score":10},{"id":"path_b","label":"先按材质分类","nextStepId":"finish_b","score":10}]},{"id":"finish_a","title":"路线一结局","body":"换季收纳顺序判断完成 选择：先清洁再收纳","outcomeCode":"finish_a","outcomeLabel":"路线一结局","terminal":true,"options":[]},{"id":"finish_b","title":"路线二结局","body":"换季收纳顺序判断完成 选择：先按材质分类","outcomeCode":"finish_b","outcomeLabel":"路线二结局","terminal":true,"options":[]}]},"leaderboard":{"enabled":true,"metric":"ELAPSED_TIME","scope":"ACTIVITY","limit":50}}');

-- TEMPLATE repair_tool_guess|维修工具盲猜|家政维修|timer,random,leaderboard
INSERT INTO seed_merchant_game_b27_config (slug, advanced_config)
VALUES ('repair_tool_guess', '{"schemaVersion":1,"timer":{"enabled":true,"durationSeconds":180,"timeoutResult":"FAILED"},"random":{"enabled":true,"drawCount":3,"items":[{"id":"repair_tool_guess_a","label":"测量工具","weight":10,"content":"用于确认尺寸而不是带电检测"},{"id":"repair_tool_guess_b","label":"紧固工具","weight":10,"content":"选择与螺丝规格匹配的工具"},{"id":"repair_tool_guess_c","label":"防护工具","weight":10,"content":"开始操作前先确认护具和断电"}]},"leaderboard":{"enabled":true,"metric":"ELAPSED_TIME","scope":"ACTIVITY","limit":50}}');

-- TEMPLATE family_safety_check_relay|家庭安全检查接力|家政维修|random,multiplayer
INSERT INTO seed_merchant_game_b27_config (slug, advanced_config)
VALUES ('family_safety_check_relay', '{"schemaVersion":1,"random":{"enabled":true,"drawCount":3,"items":[{"id":"family_safety_check_relay_a","label":"用电观察","weight":10,"content":"发现异常发热或破损先停止使用"},{"id":"family_safety_check_relay_b","label":"燃气观察","weight":10,"content":"发现异常气味先离开并在安全处求助"},{"id":"family_safety_check_relay_c","label":"通道观察","weight":10,"content":"保持逃生和通行区域不被占用"}]},"multiplayer":{"enabled":true,"mode":"ROLE_BASED","minPlayers":2,"maxPlayers":4,"assignment":"AUTO","roles":[{"id":"starter","label":"安全观察者","min":1,"max":2},{"id":"finisher","label":"求助方案者","min":1,"max":2}],"turnOrder":["starter","finisher"],"requiredTurns":3,"unitScore":10}}');

-- TEMPLATE city_sidequest_observation|城市支线观察局|出行服务|random,branch
INSERT INTO seed_merchant_game_b27_config (slug, advanced_config)
VALUES ('city_sidequest_observation', '{"schemaVersion":1,"random":{"enabled":true,"drawCount":1,"items":[{"id":"city_sidequest_observation_a","label":"建筑线索","weight":10,"content":"观察一处公开建筑的形状或年代提示"},{"id":"city_sidequest_observation_b","label":"人文线索","weight":10,"content":"记录一块门牌或公共文字"},{"id":"city_sidequest_observation_c","label":"声音线索","weight":10,"content":"在安全位置描述一种城市声音"}]},"branch":{"enabled":true,"startStepId":"start","steps":[{"id":"start","title":"城市支线观察局路径","body":"根据本轮题面选择最符合小队想法的方向","terminal":false,"options":[{"id":"path_a","label":"选择建筑支线","nextStepId":"finish_a","score":10},{"id":"path_b","label":"选择人文支线","nextStepId":"finish_b","score":10}]},{"id":"finish_a","title":"路线一结局","body":"城市支线观察已经完成 选择：选择建筑支线","outcomeCode":"finish_a","outcomeLabel":"路线一结局","terminal":true,"options":[]},{"id":"finish_b","title":"路线二结局","body":"城市支线观察已经完成 选择：选择人文支线","outcomeCode":"finish_b","outcomeLabel":"路线二结局","terminal":true,"options":[]}]}}');

-- TEMPLATE travel_partner_decisions|旅伴决策接力|出行服务|branch,multiplayer
INSERT INTO seed_merchant_game_b27_config (slug, advanced_config)
VALUES ('travel_partner_decisions', '{"schemaVersion":1,"branch":{"enabled":true,"startStepId":"start","steps":[{"id":"start","title":"旅伴决策接力路径","body":"根据本轮题面选择最符合小队想法的方向","terminal":false,"options":[{"id":"path_a","label":"选择稳妥预案","nextStepId":"finish_a","score":10},{"id":"path_b","label":"选择灵活调整","nextStepId":"finish_b","score":10}]},{"id":"finish_a","title":"路线一结局","body":"旅伴决策风格已经生成 选择：选择稳妥预案","outcomeCode":"finish_a","outcomeLabel":"路线一结局","terminal":true,"options":[]},{"id":"finish_b","title":"路线二结局","body":"旅伴决策风格已经生成 选择：选择灵活调整","outcomeCode":"finish_b","outcomeLabel":"路线二结局","terminal":true,"options":[]}]},"multiplayer":{"enabled":true,"mode":"ROLE_BASED","minPlayers":2,"maxPlayers":4,"assignment":"AUTO","roles":[{"id":"starter","label":"行程发起者","min":1,"max":2},{"id":"finisher","label":"预案接力者","min":1,"max":2}],"turnOrder":["starter","finisher"],"requiredTurns":3,"unitScore":10}}');

-- TEMPLATE travel_safety_bingo|安全观察宾果|出行服务|random,branch
INSERT INTO seed_merchant_game_b27_config (slug, advanced_config)
VALUES ('travel_safety_bingo', '{"schemaVersion":1,"random":{"enabled":true,"drawCount":3,"items":[{"id":"travel_safety_bingo_a","label":"路线格","weight":10,"content":"出发前确认官方路线和末班时间"},{"id":"travel_safety_bingo_b","label":"集合格","weight":10,"content":"约定清晰集合点和失联处理方式"},{"id":"travel_safety_bingo_c","label":"证件格","weight":10,"content":"检查必要证件并保护个人信息"}]},"branch":{"enabled":true,"startStepId":"start","steps":[{"id":"start","title":"安全观察宾果路径","body":"根据本轮题面选择最符合小队想法的方向","terminal":false,"options":[{"id":"path_a","label":"确认后继续","nextStepId":"finish_a","score":10},{"id":"path_b","label":"暂停并向官方求助","nextStepId":"finish_b","score":10}]},{"id":"finish_a","title":"路线一结局","body":"出发前安全检查完成 选择：确认后继续","outcomeCode":"finish_a","outcomeLabel":"路线一结局","terminal":true,"options":[]},{"id":"finish_b","title":"路线二结局","body":"出发前安全检查完成 选择：暂停并向官方求助","outcomeCode":"finish_b","outcomeLabel":"路线二结局","terminal":true,"options":[]}]}}');

-- TEMPLATE route_badge_story|路线纪念章故事局|出行服务|random,branch,multiplayer
INSERT INTO seed_merchant_game_b27_config (slug, advanced_config)
VALUES ('route_badge_story', '{"schemaVersion":1,"random":{"enabled":true,"drawCount":1,"items":[{"id":"route_badge_story_a","label":"街巷词卡","weight":10,"content":"为一段城市街巷命名"},{"id":"route_badge_story_b","label":"自然词卡","weight":10,"content":"为一处山水风景命名"},{"id":"route_badge_story_c","label":"同行词卡","weight":10,"content":"为同行人写一句纪念章题词"}]},"branch":{"enabled":true,"startStepId":"start","steps":[{"id":"start","title":"路线纪念章故事局路径","body":"根据本轮题面选择最符合小队想法的方向","terminal":false,"options":[{"id":"path_a","label":"选择城市路线","nextStepId":"finish_a","score":10},{"id":"path_b","label":"选择自然路线","nextStepId":"finish_b","score":10}]},{"id":"finish_a","title":"路线一结局","body":"虚拟路线纪念章故事已经完成 选择：选择城市路线","outcomeCode":"finish_a","outcomeLabel":"路线一结局","terminal":true,"options":[]},{"id":"finish_b","title":"路线二结局","body":"虚拟路线纪念章故事已经完成 选择：选择自然路线","outcomeCode":"finish_b","outcomeLabel":"路线二结局","terminal":true,"options":[]}]},"multiplayer":{"enabled":true,"mode":"ROLE_BASED","minPlayers":2,"maxPlayers":4,"assignment":"AUTO","roles":[{"id":"starter","label":"纪念章命名者","min":1,"max":2},{"id":"finisher","label":"路线讲述者","min":1,"max":2}],"turnOrder":["starter","finisher"],"requiredTurns":3,"unitScore":10}}');

INSERT INTO seed_merchant_game_b27_spec
    (slug, title, category_name, mechanics, description, players, usage_location,
     required_materials, duration, rule_instructions)
VALUES
    ('table_flavor_relay', '同桌口味接力', '美食烹饪', 'random,branch,multiplayer', '同桌轮流抽取风味词并完成一道选择，让一桌人共同拼出今晚的口味地图。', '2-4人同桌', '餐饮/咖啡馆/烘焙门店', '真实菜单或风味说明', 8, '抽一张风味词卡，由发起者描述线索、接力者选择清爽或醇厚路径，完成三轮后共同解锁口味结论。'),
    ('table_mission_bingo', '一桌任务宾果', '美食烹饪', 'random,multiplayer', '随机抽取三项轻量同桌任务，适合等餐和聚会破冰。', '2-4人同桌', '餐厅/咖啡馆/茶饮店', '桌面与真实菜单', 8, '系统抽取三项任务，队员按角色轮流完成观察、推荐和一句话分享；不要求额外消费。'),
    ('menu_co_creation', '菜单共创接力', '美食烹饪', 'random,branch,multiplayer', '从真实菜单抽取风味灵感，小队接力设计一份虚构限定组合。', '2-4人小队', '餐饮/咖啡馆/烘焙门店', '真实菜单', 10, '抽取一张风味灵感，由命名者和搭配者轮流选择主角与口感分支，完成后获得本桌限定菜单故事。'),
    ('team_energy_relay', '小队能量接力', '运动健身', 'multiplayer,leaderboard', '不用传感器的小队知识与口令接力，按单场完成用时排名。', '2-4人小队', '健身房/运动场馆/瑜伽馆', '场馆安全提示', 8, '队员依次完成热身知识口令和安全观察，完成规定轮次后按单场用时进入榜单。不记录体重 BMI 或热量。'),
    ('partner_training_relay', '搭子训练接力', '运动健身', 'timer,multiplayer,leaderboard', '搭子在倒计时内轮流完成安全知识与动作名称接龙。', '2-4人小队', '健身房/运动场馆/瑜伽馆', '教练审核的安全题', 8, '启动倒计时，发起者读题、接力者选择安全做法，完成三轮后按单场完成用时排名。不记录体重 BMI 或热量。'),
    ('movement_bingo', '动作宾果', '运动健身', 'random,multiplayer', '随机抽取低风险动作名称和安全提示，组成小队宾果。', '2-4人小队', '健身房/运动场馆/瑜伽馆', '教练审核的动作卡', 8, '抽取三张动作卡，队员轮流说出动作要点或安全提醒；只做知识互动，不识别动作质量。不记录体重 BMI 或热量。'),
    ('gym_charity_sprint', '全馆公益热身接力', '运动健身', 'timer,multiplayer,leaderboard', '以单场小队热身知识接力模拟公益挑战，不累计真实里程或热量。', '2-4人小队', '健身房/运动场馆', '场馆安全提示', 8, '在倒计时内完成四轮安全热身知识接力，按单场完成用时排名。公益主题只用于本局叙事，不记录体重 BMI 或热量。'),
    ('song_mood_relay', '点歌情绪接力', '娱乐达人', 'random,branch,multiplayer', '不用歌曲版权素材，以原创情绪词卡完成点歌氛围接力。', '2-4人小队', 'KTV/酒吧/音乐餐厅', '无需歌曲采样', 10, '抽取情绪词卡，由描述者说出场景、选择者决定热烈或松弛路径，完成三轮后生成本桌氛围结局。'),
    ('performance_bingo', '表演任务宾果', '娱乐达人', 'random,multiplayer', '随机抽取无门槛表演任务，适合包厢和聚会快速破冰。', '2-4人小队', 'KTV/桌游馆/派对空间', '安全空地', 8, '抽取三项表演任务，队员按角色轮流完成一句台词、一个节拍或一个定格动作；允许跳过不舒适任务。'),
    ('original_role_mystery', '原创角色协作局', '娱乐达人', 'branch,multiplayer', '公开角色分工的原创协作推理，不冒充暗置角色剧本杀。', '2-4人小队', '桌游馆/剧本馆/书店', '一张桌子', 12, '系统分配观察者与记录者，所有线索公开；小队共同选择调查路径并抵达结局，不包含暗置角色。'),
    ('style_personality_lab', '风格人格实验室', '生活美学', 'random,branch', '抽取风格关键词，通过两条路径生成当日风格人格。', '单人/双人', '美发/美甲/美容/摄影门店', '门店风格样卡', 6, '抽取风格词卡，再选择克制或大胆路径，生成仅用于本局的风格结果；不推断身份、收入或健康状态。'),
    ('color_co_creation', '色彩共创接力', '生活美学', 'random,branch,multiplayer', '多人轮流抽色彩词并选择搭配路径，共创一张口头色板。', '2-4人小队', '美发/美甲/摄影/花店', '门店色卡', 10, '抽取色彩词卡，发起者描述氛围、接力者选择对比或同色路径，完成后生成小队色彩故事。'),
    ('detail_observation_blitz', '细节观察挑战', '生活美学', 'timer,branch,leaderboard', '90 秒观察门店公开细节，通过分支判断完成挑战。', '单人/双人', '摄影/美发/美甲/家居门店', '稳定陈列细节', 5, '启动 90 秒倒计时，观察一个公开陈列细节并选择形状或色彩路径，完成后按单场用时排名。'),
    ('self_care_bingo', '日常护理宾果', '生活美学', 'random,branch', '随机护理常识卡配合情境分支，强调舒适和风险边界。', '单人/双人', '美容/美发/美甲门店', '商家审核的护理提示', 6, '抽取护理情境卡，选择温和观察或停止求助路径；不做医疗诊断，不承诺效果。'),
    ('space_styling_challenge', '空间搭配挑战', '生活美学', 'random,branch,leaderboard', '随机空间关键词加搭配分支，按单场完成用时形成轻榜单。', '单人/双人', '家居/花店/摄影空间', '公开陈列区', 8, '抽取空间关键词，选择留白或层次路径并完成搭配判断，按单场完成用时排名，不改动真实陈列。'),
    ('duo_memory_relay', '双人纪念故事接力', '礼品纪念', 'random,multiplayer', '双人抽取安全话题词，轮流讲一句共同记忆。', '2-4人小队', '礼品店/花店/文创店', '无需私人照片', 8, '抽取一张轻话题卡，发起者和接力者各说一句可公开的记忆；任何人都可跳过私人问题。'),
    ('gift_match_guess', '礼物偏好猜想局', '礼品纪念', 'branch,multiplayer', '搭档公开选择实用或惊喜路径，看看彼此的礼物偏好是否合拍。', '2-4人小队', '礼品店/花店/文创店', '门店公开商品分类', 8, '发起者选择礼物情境，接力者猜实用或惊喜路径，答案当场公开并进入共同结局，不保存私人偏好。'),
    ('keepsake_treasure_clues', '纪念物线索寻宝', '礼品纪念', 'timer,branch', '在稳定陈列中完成一条五分钟纪念物线索链。', '单人/双人', '礼品店/文创店/博物商店', '稳定陈列线索', 8, '启动五分钟倒计时，从材质或故事分支寻找指定纪念物；线索只能使用公开且稳定的陈列。'),
    ('festival_story_puzzle', '节日故事拼图', '礼品纪念', 'random,branch,multiplayer', '抽取节日故事片段，小队轮流选择顺序并拼出原创结局。', '2-4人小队', '礼品店/花店/文创店', '无需节日版权素材', 10, '抽取原创节日片段，由排序者和讲述者轮流选择温暖或冒险路径，完成后解锁小队结局。'),
    ('clothing_care_bingo', '衣物护理宾果', '衣物洗护', 'random,branch', '抽取材质与护理情境卡，选择稳妥处理路径。', '单人/双人', '洗衣店/衣物洗护门店', '公开洗护标签示意', 6, '抽取护理卡，先看标签，再选择暂停处理或咨询专业人员路径；不对真实贵重衣物作结果承诺。'),
    ('seasonal_storage_challenge', '换季收纳挑战', '衣物洗护', 'timer,branch,leaderboard', '倒计时内完成换季收纳顺序判断，按单场用时排名。', '单人/双人', '洗衣店/衣物洗护/家政门店', '收纳示意卡', 6, '启动倒计时，依次判断清洁、干燥和分类路径，完成后按单场用时排名。'),
    ('repair_tool_guess', '维修工具盲猜', '家政维修', 'timer,random,leaderboard', '随机抽取工具用途卡，在倒计时内完成安全常识盲猜。', '单人/双人', '家政维修/五金/装修门店', '工具图片或安全示意卡', 5, '抽取三张工具用途卡，在三分钟内完成判断并按单场完成用时排名；不接触带电或危险工具。'),
    ('family_safety_check_relay', '家庭安全检查接力', '家政维修', 'random,multiplayer', '家庭成员轮流抽安全检查卡，完成公开知识接力。', '2-4人小队', '家政维修/物业/装修门店', '商家审核的安全提示', 8, '抽取三张安全检查卡，队员依次说出观察点和求助方式；不进入真实危险现场，不做远程诊断。'),
    ('city_sidequest_observation', '城市支线观察局', '出行服务', 'random,branch', '抽取城市观察词，在门店附近选择建筑或人文分支。', '单人/双人', '旅行社/酒店/租车/交通服务点', '公开地图与安全提示', 8, '抽取观察词卡，选择建筑或人文路径完成一项公开观察；不引导进入封闭或危险区域。'),
    ('travel_partner_decisions', '旅伴决策接力', '出行服务', 'branch,multiplayer', '旅伴轮流处理天气、集合和休息等公开旅行情境。', '2-4人小队', '旅行社/酒店/租车服务点', '公开行程提示', 10, '发起者读取情境，接力者选择稳妥或灵活路径，完成三轮后获得小队旅行风格；不以消费额排名。'),
    ('travel_safety_bingo', '安全观察宾果', '出行服务', 'random,branch', '随机安全观察卡配合情境分支，适合出发前破冰。', '单人/双人', '旅行社/酒店/交通服务点', '公开安全须知', 8, '抽取三张安全卡，选择确认路线或暂停求助路径；不替代官方交通和应急信息。'),
    ('route_badge_story', '路线纪念章故事局', '出行服务', 'random,branch,multiplayer', '抽取路线主题词，小队接力写出一枚虚拟纪念章的故事。', '2-4人小队', '旅行社/酒店/租车服务点', '公开地图', 10, '抽取路线词卡，由命名者和讲述者选择城市或自然路径，共同完成虚拟纪念章故事；不以消费额排名。');

UPDATE seed_merchant_game_b27_spec s
  JOIN seed_merchant_game_b27_media m ON m.slug = s.slug
   SET s.image_path = m.image_path
 WHERE s.image_path IS NULL;

-- 每个语义品类必须在当前环境唯一存在 否则利用 guard 主键冲突整笔回滚
INSERT INTO seed_merchant_game_b27_guard (id)
SELECT 1
 WHERE EXISTS (
       SELECT s.category_name
         FROM seed_merchant_game_b27_spec s
         LEFT JOIN sys_category c
           ON c.category_name = s.category_name
          AND c.type = 1
          AND c.status = 1
          AND c.del_flag = 0
        GROUP BY s.category_name
       HAVING COUNT(DISTINCT c.id) <> 1
 );

UPDATE seed_merchant_game_b27_spec s
   SET s.activity_categoryids = CAST((
           SELECT MIN(c.id)
             FROM sys_category c
            WHERE c.category_name = s.category_name
              AND c.type = 1
              AND c.status = 1
              AND c.del_flag = 0) AS CHAR)
 WHERE s.activity_categoryids IS NULL;

UPDATE seed_merchant_game_b27_config c
  JOIN seed_merchant_game_b27_spec s ON s.slug = c.slug
   SET s.advanced_config = c.advanced_config
 WHERE s.advanced_config IS NULL;

INSERT INTO seed_merchant_game_b27_guard (id)
SELECT 1
 WHERE EXISTS (
       SELECT category_name
         FROM seed_merchant_game_b27_spec
        GROUP BY category_name
       HAVING COALESCE(MIN(activity_categoryids), '') = ''
 );

INSERT INTO seed_merchant_game_b27_guard (id)
SELECT 1
 WHERE EXISTS (
       SELECT 1
         FROM seed_merchant_game_b27_spec
        WHERE advanced_config IS NULL
 );

-- 同名存量只能是本迁移已写入的唯一一行
INSERT INTO seed_merchant_game_b27_guard (id)
SELECT 1
 WHERE EXISTS (
       SELECT l.title
         FROM cms_template_library l
         JOIN seed_merchant_game_b27_spec s ON s.title = l.title
        WHERE l.member_id = 100000
          AND l.del_flag = 0
        GROUP BY l.title
       HAVING COUNT(*) <> 1
           OR COALESCE(MIN(create_by), '') <> 'seed_merchant_game_b27_20260823'
 );

INSERT INTO cms_template_library (
    member_id, member_template_id, title, description, img_url, players,
    usage_location, required_materials, duration, difficulty, rule_instructions,
    validation_method, feedback_text, story_text, story_img, preference_json, advanced_config_json,
    activity_categoryids, status, publish_status, is_sync, use_num, del_flag,
    create_by, create_time
)
SELECT 100000, NULL, s.title, s.description, s.image_path, s.players,
       s.usage_location, s.required_materials, s.duration, '入门', s.rule_instructions,
       0, CONCAT(s.title, '完成。把本轮结果讲给同伴听。'),
       CONCAT('这是一场围绕', s.category_name, '设计的单场内互动。每一次选择都只影响本局结果。'),
       s.image_path, NULL, s.advanced_config, s.activity_categoryids,
       1, 1, 0, 0, 0, 'seed_merchant_game_b27_20260823', NOW()
  FROM seed_merchant_game_b27_spec s
 WHERE NOT EXISTS (
       SELECT 1
         FROM cms_template_library l
        WHERE l.member_id = 100000
          AND l.title = s.title
          AND l.del_flag = 0
 );

-- 幂等重放时同步修复品类和高级玩法配置
UPDATE cms_template_library l
  JOIN seed_merchant_game_b27_spec s ON s.title = l.title
   SET l.activity_categoryids = s.activity_categoryids,
       l.advanced_config_json = s.advanced_config,
       l.img_url = s.image_path,
       l.story_img = s.image_path
 WHERE l.member_id = 100000
   AND l.del_flag = 0
   AND l.create_by = 'seed_merchant_game_b27_20260823';

-- 最终业务不变量 不成立时整笔回滚
INSERT INTO seed_merchant_game_b27_guard (id)
SELECT 1
 WHERE (SELECT COUNT(*)
          FROM cms_template_library
         WHERE member_id = 100000
           AND del_flag = 0
           AND create_by = 'seed_merchant_game_b27_20260823') <> 27
    OR EXISTS (
       SELECT 1
         FROM seed_merchant_game_b27_spec s
         LEFT JOIN cms_template_library l
           ON l.member_id = 100000
          AND l.title = s.title
          AND l.del_flag = 0
        WHERE l.id IS NULL
           OR COALESCE(l.status, -1) <> 1
           OR COALESCE(l.publish_status, -1) <> 1
           OR COALESCE(l.validation_method, -1) <> 0
           OR l.advanced_config_json IS NULL
           OR COALESCE(l.activity_categoryids, '') <> s.activity_categoryids
           OR s.image_path IS NULL
           OR COALESCE(l.img_url, '') <> s.image_path
           OR COALESCE(l.story_img, '') <> s.image_path
           OR COALESCE(l.create_by, '') <> 'seed_merchant_game_b27_20260823'
 );

-- 回滚前必须确认没有商家采用副本和运行会话
-- 满足条件后只删除 create_by=seed_merchant_game_b27_20260823 的 27 行
