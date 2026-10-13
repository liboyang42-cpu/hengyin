-- ============================================================================
-- 商家互动游戏 A 档首发种子 8 款
--
-- 落点是 cms_template_library 公共节点玩法库
-- 商家品类按 sys_category.category_name 语义解析 不写死环境数字 id
-- 题面图为 Pexels 真实摄影素材 节奏音频为本次程序合成
-- 图片许可 作品页 摄影者与全部媒体 SHA-256 见 docs/research/merchant-game-media-provenance-20260823.md
--
-- 前置依赖
--   migration_level_editor_fields.sql
--   migration_level_editor_story_photo_review.sql
--   migration_question_media_20260816.sql
--   migration_20260822_advanced_game_runtime.sql
--   migration_seed_life_backup_categories_20260820.sql
--
-- 本文件只随代码交付 不自动执行
-- 生产执行必须走 chengyin-db-migrate 并做落库后业务回读
-- runner: requires=transaction
-- ============================================================================

CREATE TEMPORARY TABLE seed_merchant_game_a8_guard (
    id INT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;
INSERT INTO seed_merchant_game_a8_guard (id) VALUES (1);

CREATE TEMPORARY TABLE seed_merchant_game_a8_expected (
    title VARCHAR(64) NOT NULL PRIMARY KEY,
    validation_method INT NOT NULL
) ENGINE=InnoDB;
INSERT INTO seed_merchant_game_a8_expected (title, validation_method)
VALUES
    ('招牌菜限时盲猜', 3),
    ('咖啡风味人格', 6),
    ('隐藏菜单线索链', 3),
    ('60秒健身知识反应赛', 3),
    ('节奏盲猜夜', 3),
    ('分支故事接力', 0),
    ('污渍侦探', 3),
    ('家居安全排雷', 3);

CREATE TEMPORARY TABLE seed_merchant_game_a8_config (
    slug VARCHAR(64) NOT NULL PRIMARY KEY,
    advanced_config JSON NOT NULL,
    preference_config JSON NULL
) ENGINE=InnoDB;

CREATE TEMPORARY TABLE seed_merchant_game_a8_category (
    slug VARCHAR(64) NOT NULL PRIMARY KEY,
    title VARCHAR(64) NOT NULL UNIQUE,
    category_name VARCHAR(64) NOT NULL,
    category_id INT NULL,
    activity_categoryids VARCHAR(32) NULL,
    advanced_config JSON NULL
) ENGINE=InnoDB;
INSERT INTO seed_merchant_game_a8_category (slug, title, category_name)
VALUES
    ('signature_dish_blitz', '招牌菜限时盲猜', '美食烹饪'),
    ('coffee_flavor_personality', '咖啡风味人格', '美食烹饪'),
    ('hidden_menu_clue_chain', '隐藏菜单线索链', '美食烹饪'),
    ('fitness_reaction_60', '60秒健身知识反应赛', '运动健身'),
    ('rhythm_blind_guess', '节奏盲猜夜', '娱乐达人'),
    ('branch_story_relay', '分支故事接力', '娱乐达人'),
    ('stain_detective', '污渍侦探', '衣物洗护'),
    ('home_safety_mines', '家居安全排雷', '家政维修');

-- 每个语义品类必须在当前环境唯一存在 否则整笔回滚
INSERT INTO seed_merchant_game_a8_guard (id)
SELECT 1
 WHERE EXISTS (
       SELECT r.category_name
         FROM seed_merchant_game_a8_category r
         LEFT JOIN sys_category c
           ON c.category_name = r.category_name
          AND c.type = 1
          AND c.status = 1
          AND c.del_flag = 0
        GROUP BY r.category_name
       HAVING COUNT(DISTINCT c.id) <> 1
 );

UPDATE seed_merchant_game_a8_category r
   SET r.category_id = (
           SELECT MIN(c.id)
             FROM sys_category c
            WHERE c.category_name = r.category_name
              AND c.type = 1 AND c.status = 1 AND c.del_flag = 0),
       r.activity_categoryids = CAST((
           SELECT MIN(c.id)
             FROM sys_category c
            WHERE c.category_name = r.category_name
              AND c.type = 1 AND c.status = 1 AND c.del_flag = 0) AS CHAR)
 WHERE r.category_id IS NULL
   AND r.activity_categoryids IS NULL;

INSERT INTO seed_merchant_game_a8_guard (id)
SELECT 1
 WHERE EXISTS (
       SELECT slug
         FROM seed_merchant_game_a8_category
        GROUP BY slug
       HAVING COALESCE(MIN(category_id), 0) = 0
          OR COALESCE(MIN(activity_categoryids), '') = ''
 );

-- 同名存量只能是本迁移已经写入的唯一一行
-- 发现业务同名或重复数据时利用主键冲突 fail closed
INSERT INTO seed_merchant_game_a8_guard (id)
SELECT 1
 WHERE EXISTS (
           SELECT 1
             FROM cms_template_library
            WHERE member_id = 100000
              AND del_flag = 0
              AND title IN ('招牌菜限时盲猜','咖啡风味人格','隐藏菜单线索链','60秒健身知识反应赛',
                            '节奏盲猜夜','分支故事接力','污渍侦探','家居安全排雷')
            GROUP BY title
           HAVING COUNT(*) <> 1
               OR COALESCE(MIN(create_by), '') <> 'seed_merchant_game_a8_20260823'
       );

-- TEMPLATE signature_dish_blitz|招牌菜限时盲猜|3|餐饮,咖啡馆,烘焙甜品|timer,leaderboard
INSERT INTO seed_merchant_game_a8_config (slug, advanced_config, preference_config)
VALUES ('signature_dish_blitz', '{"schemaVersion":1,"timer":{"enabled":true,"durationSeconds":60,"timeoutResult":"FAILED"},"leaderboard":{"enabled":true,"metric":"ELAPSED_TIME","scope":"ACTIVITY","limit":50}}', NULL);
INSERT INTO cms_template_library (member_id, member_template_id, title, description, players,
        usage_location, required_materials, duration, difficulty, rule_instructions,
        validation_method, question_name, question_a, question_b, question_c, question_d,
        correct_answer, hint1, hint2, answer_reveal, feedback_text, story_text,
        preference_json, advanced_config_json,
        status, publish_status, is_sync, use_num, del_flag, create_by, create_time)
SELECT 100000, NULL, '招牌菜限时盲猜', '60 秒看线索猜招牌 适合餐饮 咖啡馆和烘焙甜品店', '单人/双人/不限',
       '餐厅/咖啡馆/烘焙门店', '一份可被现场观察的招牌商品 商家确认答案', 3, '入门',
       '1. 先观察桌面或展示柜中的招牌线索。2. 启动 60 秒倒计时。3. 在四个选项中选出本店招牌。4. 用时更短的玩家进入本场榜单。商家采用后必须把题目和四个选项改成本店真实商品。',
       (SELECT validation_method FROM seed_merchant_game_a8_expected WHERE title = '招牌菜限时盲猜'),
       '哪一项最像这家店今天的招牌主角?', '店员重点推荐的当日招牌', '菜单里最贵的一项', '颜色最鲜艳的一项', '名字最长的一项',
       'A', '先找菜单或展示柜里的推荐标识', '答案必须能从现场稳定找到',
       '答案是店员重点推荐的当日招牌。采用模板后请替换为门店真实答案。',
       '猜中了。你的招牌雷达已经启动。',
       '真正的招牌不一定最贵 却一定藏着一家店最想被记住的味道。倒计时开始 从香气 菜单和陈列里找出它。',
       NULL, (SELECT advanced_config FROM seed_merchant_game_a8_config WHERE slug = 'signature_dish_blitz'),
       1, 1, 0, 0, 0, 'seed_merchant_game_a8_20260823', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_template_library
                    WHERE member_id = 100000 AND title = '招牌菜限时盲猜' AND del_flag = 0);

-- TEMPLATE coffee_flavor_personality|咖啡风味人格|6|咖啡馆,茶饮,烘焙甜品|random,branch
INSERT INTO seed_merchant_game_a8_config (slug, advanced_config, preference_config)
VALUES ('coffee_flavor_personality', '{"schemaVersion":1,"random":{"enabled":true,"drawCount":1,"items":[{"id":"citrus_card","label":"柑橘线索卡","weight":10,"content":"先寻找明亮和清爽的描述"},{"id":"cocoa_card","label":"可可线索卡","weight":10,"content":"先寻找醇厚和温暖的描述"},{"id":"floral_card","label":"花香线索卡","weight":8,"content":"先寻找轻盈和香气层次"}]},"branch":{"enabled":true,"startStepId":"result","steps":[{"id":"result","title":"带着人格去点单","body":"按偏好题生成的人格选择对应体验路径","terminal":false,"options":[{"id":"bright_path","label":"我是明亮探索家","nextStepId":"bright_finish","score":10},{"id":"mellow_path","label":"我是醇厚治愈派","nextStepId":"mellow_finish","score":10}]},{"id":"bright_finish","title":"明亮路径","body":"请店员推荐一款清爽果香或轻盈茶感","outcomeCode":"bright_finish","outcomeLabel":"明亮路径","terminal":true,"options":[]},{"id":"mellow_finish","title":"醇厚路径","body":"请店员推荐一款坚果可可或圆润奶香","outcomeCode":"mellow_finish","outcomeLabel":"醇厚路径","terminal":true,"options":[]}]}}', '{"steps":[{"key":"first_sip","type":"single","title":"第一口你更想要什么感觉?","options":[{"key":"A","text":"明亮清爽","scores":{"bright":2,"mellow":0}},{"key":"B","text":"醇厚温暖","scores":{"bright":0,"mellow":2}}]},{"key":"afternoon","type":"single","title":"下午三点你更需要哪种陪伴?","options":[{"key":"A","text":"提神和新鲜感","scores":{"bright":2,"mellow":0}},{"key":"B","text":"安稳和包裹感","scores":{"bright":0,"mellow":2}}]}],"dimensions":["bright","mellow"],"results":{"bright":{"title":"明亮探索家","body":"因为你选择了{{choices}} 你更适合清爽果香或轻盈茶感","nextStep":"请店员推荐一款明亮风味","nextStepDays":7},"mellow":{"title":"醇厚治愈派","body":"因为你选择了{{choices}} 你更适合坚果可可或圆润奶香","nextStep":"请店员推荐一款醇厚风味","nextStepDays":7}},"tiebreak":{"key":"last_choice","type":"single","title":"最后一票投给哪种余韵?","options":[{"key":"A","text":"清爽回甘","scores":{"bright":1}},{"key":"B","text":"绵长厚实","scores":{"mellow":1}}]}}');
INSERT INTO cms_template_library (member_id, member_template_id, title, description, players,
        usage_location, required_materials, duration, difficulty, rule_instructions,
        validation_method, feedback_text, story_text, preference_json, advanced_config_json,
        status, publish_status, is_sync, use_num, del_flag, create_by, create_time)
SELECT 100000, NULL, '咖啡风味人格', '两道选择题生成风味人格 再抽一张原创风味线索卡', '单人/双人',
       '咖啡馆/茶饮店/烘焙门店', '真实菜单 店员可解释的风味描述', 5, '入门',
       '1. 抽取一张风味线索卡。2. 回答两道偏好题。3. 并列时完成最后取舍。4. 获得明亮探索家或醇厚治愈派结果。5. 选择对应体验分支并请店员推荐。结果仅用于本局展示 不写入跨节点偏好标签。商家采用后可改结果文案 但不得把偏好推断为健康或消费能力。',
       (SELECT validation_method FROM seed_merchant_game_a8_expected WHERE title = '咖啡风味人格'),
       '风味人格已经生成 带着它去和店员交换一杯更懂你的推荐。',
       '你不是在考试 也没有标准答案。每一次选择只是在描出今天想喝的那一杯。',
       c.preference_config, c.advanced_config,
       1, 1, 0, 0, 0, 'seed_merchant_game_a8_20260823', NOW()
  FROM seed_merchant_game_a8_config c
 WHERE c.slug = 'coffee_flavor_personality'
   AND NOT EXISTS (SELECT 1 FROM cms_template_library
                    WHERE member_id = 100000 AND title = '咖啡风味人格' AND del_flag = 0);

-- TEMPLATE hidden_menu_clue_chain|隐藏菜单线索链|3|餐饮,咖啡馆,酒吧|timer,branch
INSERT INTO seed_merchant_game_a8_config (slug, advanced_config, preference_config)
VALUES ('hidden_menu_clue_chain', '{"schemaVersion":1,"timer":{"enabled":true,"durationSeconds":300,"timeoutResult":"FAILED"},"branch":{"enabled":true,"startStepId":"start","steps":[{"id":"start","title":"第一条线索","body":"从菜单封面和店内陈列里选一个方向","terminal":false,"options":[{"id":"menu","label":"先看菜单封面","nextStepId":"menu_path","score":10},{"id":"display","label":"先看店内陈列","nextStepId":"display_path","score":10}]},{"id":"menu_path","title":"菜单路径","body":"找出菜单里被重复出现的关键词","terminal":false,"options":[{"id":"unlock_menu","label":"我找到了重复词","nextStepId":"finish","score":20}]},{"id":"display_path","title":"陈列路径","body":"找出陈列中与菜单同色的物件","terminal":false,"options":[{"id":"unlock_display","label":"我找到了同色物件","nextStepId":"finish","score":20}]},{"id":"finish","title":"隐藏口令","body":"两条路径都指向同一个隐藏口令","outcomeCode":"finish","outcomeLabel":"隐藏口令","terminal":true,"options":[]}]}}', NULL);
INSERT INTO cms_template_library (member_id, member_template_id, title, description, players,
        usage_location, required_materials, duration, difficulty, rule_instructions,
        validation_method, question_name, question_a, question_b, question_c, question_d,
        correct_answer, hint1, hint2, answer_reveal, feedback_text, story_text,
        preference_json, advanced_config_json,
        status, publish_status, is_sync, use_num, del_flag, create_by, create_time)
SELECT 100000, NULL, '隐藏菜单线索链', '五分钟分支寻线 解锁门店隐藏口令', '单人/双人/小队',
       '餐厅/咖啡馆/酒吧', '稳定存在的菜单和陈列线索 店员知道最终口令', 8, '普通',
       '1. 启动五分钟倒计时。2. 从菜单路径或陈列路径选一条。3. 按分支找到隐藏口令。4. 回答最终验证题。商家采用后必须现场走通两条路径 且不能依赖临时海报。',
       (SELECT validation_method FROM seed_merchant_game_a8_expected WHERE title = '隐藏菜单线索链'),
       '两条线索最后共同指向什么?', '本店隐藏口令', '收银台金额', '营业时间', '店员工号',
       'A', '先完成一条分支路径', '口令必须由菜单和陈列共同验证',
       '答案是本店隐藏口令。采用模板后请替换成真实口令和线索。',
       '线索链闭合 隐藏菜单向你打开。',
       '菜单没有写完的那一页 被拆成了两条现场线索。选一条路径 在时间结束前把它重新拼起来。',
       NULL, (SELECT advanced_config FROM seed_merchant_game_a8_config WHERE slug = 'hidden_menu_clue_chain'),
       1, 1, 0, 0, 0, 'seed_merchant_game_a8_20260823', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_template_library
                    WHERE member_id = 100000 AND title = '隐藏菜单线索链' AND del_flag = 0);

-- TEMPLATE fitness_reaction_60|60秒健身知识反应赛|3|健身房,运动场馆,瑜伽馆|timer,leaderboard
INSERT INTO seed_merchant_game_a8_config (slug, advanced_config, preference_config)
VALUES ('fitness_reaction_60', '{"schemaVersion":1,"timer":{"enabled":true,"durationSeconds":60,"timeoutResult":"FAILED"},"leaderboard":{"enabled":true,"metric":"ELAPSED_TIME","scope":"ACTIVITY","limit":50}}', NULL);
INSERT INTO cms_template_library (member_id, member_template_id, title, description, players,
        usage_location, required_materials, duration, difficulty, rule_instructions,
        validation_method, question_name, question_a, question_b, question_c, question_d,
        correct_answer, hint1, hint2, answer_reveal, feedback_text, story_text,
        preference_json, advanced_config_json,
        status, publish_status, is_sync, use_num, del_flag, create_by, create_time)
SELECT 100000, NULL, '60秒健身知识反应赛', '不采集运动传感器的 60 秒安全知识反应赛', '单人/双人/不限',
       '健身房/运动场馆/瑜伽馆', '门店安全须知 教练审核题目', 3, '入门',
       '1. 阅读现场安全提示。2. 启动 60 秒倒计时。3. 选出正确做法。4. 按正确完成用时进入榜单。本玩法只做知识反应 不宣称识别动作质量或运动消耗。',
       (SELECT validation_method FROM seed_merchant_game_a8_expected WHERE title = '60秒健身知识反应赛'),
       '开始训练前 哪个做法更稳妥?', '先确认器械和身体状态再开始', '直接使用最大重量', '忽略教练和场馆提示', '不热身马上冲刺',
       'A', '答案必须来自场馆公开安全提示', '题目不应替代专业医疗建议',
       '先确认器械和身体状态再开始。',
       '答对了 快不等于冒险 安全判断也是反应力。',
       '真正厉害的反应不是盲目冲刺 而是在一秒内知道什么值得做 什么应该停。',
       NULL, (SELECT advanced_config FROM seed_merchant_game_a8_config WHERE slug = 'fitness_reaction_60'),
       1, 1, 0, 0, 0, 'seed_merchant_game_a8_20260823', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_template_library
                    WHERE member_id = 100000 AND title = '60秒健身知识反应赛' AND del_flag = 0);

-- TEMPLATE rhythm_blind_guess|节奏盲猜夜|3|KTV,酒吧,音乐餐厅,咖啡馆|timer,random,leaderboard
INSERT INTO seed_merchant_game_a8_config (slug, advanced_config, preference_config)
VALUES ('rhythm_blind_guess', '{"schemaVersion":1,"timer":{"enabled":true,"durationSeconds":180,"timeoutResult":"FAILED"},"random":{"enabled":true,"drawCount":3,"items":[{"id":"beat_one","label":"第 1 轮","weight":10,"content":"播放合成节奏音频后轻拍复刻","audioUrl":"/audio/merchant-games/rhythm-one.mp3"},{"id":"beat_two","label":"第 2 轮","weight":10,"content":"播放合成节奏音频后轻拍复刻","audioUrl":"/audio/merchant-games/rhythm-two.mp3"},{"id":"beat_three","label":"第 3 轮","weight":10,"content":"播放合成节奏音频后轻拍复刻","audioUrl":"/audio/merchant-games/rhythm-three.mp3"}]},"leaderboard":{"enabled":true,"metric":"ELAPSED_TIME","scope":"ACTIVITY","limit":50}}', NULL);
INSERT INTO cms_template_library (member_id, member_template_id, title, description, players,
        usage_location, required_materials, duration, difficulty, rule_instructions,
        validation_method, question_name, question_a, question_b, question_c, question_d,
        correct_answer, hint1, hint2, answer_reveal, feedback_text, story_text,
        preference_json, advanced_config_json,
        status, publish_status, is_sync, use_num, del_flag, create_by, create_time)
SELECT 100000, NULL, '节奏盲猜夜', '三轮合成节奏音频盲猜 按完赛用时进榜', '双人/小队/不限',
       'KTV/酒吧/音乐餐厅/咖啡馆', '可拍桌面的安静区域 模板自带三段原创节奏音频', 5, '普通',
       '1. 依次抽出三轮节奏并点击播放。2. 队员根据听到的节拍轻拍桌面。3. 播放并复刻完一段后再抽下一轮。4. 三轮结束后按完赛用时进榜。模板音频为程序合成击打音 不含歌曲采样。',
       (SELECT validation_method FROM seed_merchant_game_a8_expected WHERE title = '节奏盲猜夜'),
       '第 3 轮音频对应哪段节奏?', '咚 咚 啪 停', '啪 咚 啪 咚', '咚 停 咚咚 啪', '咚 啪 咚 啪',
       'C', '先找停顿 再找连续的两个咚', '题目只使用模板内原创节奏文字',
       '答案是 咚 停 咚咚 啪。',
       '节拍对上了 今晚你们共享同一条节奏线。',
       '不必知道歌名 也不用抢麦。四个字的节拍足够让陌生人同时敲下第一拍。',
       NULL, (SELECT advanced_config FROM seed_merchant_game_a8_config WHERE slug = 'rhythm_blind_guess'),
       1, 1, 0, 0, 0, 'seed_merchant_game_a8_20260823', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_template_library
                    WHERE member_id = 100000 AND title = '节奏盲猜夜' AND del_flag = 0);

-- TEMPLATE branch_story_relay|分支故事接力|0|桌游馆,剧本馆,书店,俱乐部|random,branch,multiplayer
INSERT INTO seed_merchant_game_a8_config (slug, advanced_config, preference_config)
VALUES ('branch_story_relay', '{"schemaVersion":1,"random":{"enabled":true,"drawCount":1,"items":[{"id":"lost_letter","label":"遗失的信","weight":10,"content":"一封没有署名的信出现在桌上"},{"id":"silent_phone","label":"无声来电","weight":10,"content":"一部旧电话亮起却没有声音"},{"id":"wrong_key","label":"错误的钥匙","weight":10,"content":"一把钥匙插进了不属于它的锁"}]},"branch":{"enabled":true,"startStepId":"opening","steps":[{"id":"opening","title":"故事开场","body":"根据抽到的原创开场卡决定第一步","terminal":false,"options":[{"id":"open","label":"立刻接触线索","nextStepId":"letter","score":20},{"id":"observe","label":"先观察周围细节","nextStepId":"envelope","score":30}]},{"id":"letter","title":"直接路径","body":"线索要求你们从后门离开","terminal":false,"options":[{"id":"follow","label":"按线索中的路线行动","nextStepId":"ending","score":20}]},{"id":"envelope","title":"观察路径","body":"角落里藏着一个反向箭头","terminal":false,"options":[{"id":"reverse","label":"沿反方向寻找答案","nextStepId":"ending","score":30}]},{"id":"ending","title":"故事结局","body":"队伍完成四轮接力后揭晓结局","outcomeCode":"ending","outcomeLabel":"故事结局","terminal":true,"options":[]}]},"multiplayer":{"enabled":true,"mode":"ROLE_BASED","minPlayers":2,"maxPlayers":4,"assignment":"AUTO","roles":[{"id":"storyteller","label":"讲述者","min":1,"max":2},{"id":"chooser","label":"选择者","min":1,"max":2}],"turnOrder":["storyteller","chooser"],"requiredTurns":4,"unitScore":10}}', NULL);
INSERT INTO cms_template_library (member_id, member_template_id, title, description, players,
        usage_location, required_materials, duration, difficulty, rule_instructions,
        validation_method, feedback_text, story_text, preference_json, advanced_config_json,
        status, publish_status, is_sync, use_num, del_flag, create_by, create_time)
SELECT 100000, NULL, '分支故事接力', '2 至 4 人按角色轮流推进分支故事', '2-4人小队',
       '桌游馆/剧本馆/书店/俱乐部空间', '一张桌子 队员共同阅读题面', 12, '挑战',
       '1. 抽取一个原创故事开场。2. 系统自动分配讲述者与选择者。3. 按角色顺序完成四轮接力。4. 队伍共同选择故事分支并解锁结局。本模板是公开信息的协作接力 不冒充完整隐藏身份推理。',
       (SELECT validation_method FROM seed_merchant_game_a8_expected WHERE title = '分支故事接力'),
       '故事接力完成 你们共同写出了这一局的结局。',
       '故事不是被一个人讲完的。有人负责读出线索 有人负责做决定 每一轮都会把结局推向不同方向。',
       NULL, (SELECT advanced_config FROM seed_merchant_game_a8_config WHERE slug = 'branch_story_relay'),
       1, 1, 0, 0, 0, 'seed_merchant_game_a8_20260823', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_template_library
                    WHERE member_id = 100000 AND title = '分支故事接力' AND del_flag = 0);

-- TEMPLATE stain_detective|污渍侦探|3|洗衣店,衣物洗护,家政|timer,branch
INSERT INTO seed_merchant_game_a8_config (slug, advanced_config, preference_config)
VALUES ('stain_detective', '{"schemaVersion":1,"timer":{"enabled":true,"durationSeconds":180,"timeoutResult":"FAILED"},"branch":{"enabled":true,"startStepId":"observe","steps":[{"id":"observe","title":"观察污渍","body":"先判断污渍是油性还是水性线索","terminal":false,"options":[{"id":"oil","label":"更像油性污渍","nextStepId":"oil_path","score":20},{"id":"water","label":"更像水性污渍","nextStepId":"water_path","score":20}]},{"id":"oil_path","title":"油性路径","body":"不要先用热水让污渍固着","terminal":false,"options":[{"id":"record_oil","label":"记录材质并交给专业人员","nextStepId":"finish","score":30}]},{"id":"water_path","title":"水性路径","body":"先确认材质和洗涤标签","terminal":false,"options":[{"id":"record_water","label":"记录标签并交给专业人员","nextStepId":"finish","score":30}]},{"id":"finish","title":"侦探结论","body":"完成判断但不自行承诺清洁结果","outcomeCode":"finish","outcomeLabel":"侦探结论","terminal":true,"options":[]}]}}', NULL);
INSERT INTO cms_template_library (member_id, member_template_id, title, description, players,
        usage_location, required_materials, duration, difficulty, rule_instructions,
        validation_method, question_name, question_a, question_b, question_c, question_d,
        correct_answer, hint1, hint2, answer_reveal, feedback_text, story_text,
        preference_json, advanced_config_json,
        status, publish_status, is_sync, use_num, del_flag, create_by, create_time)
SELECT 100000, NULL, '污渍侦探', '三分钟观察分支 学会先看材质和洗涤标签', '单人/双人',
       '洗衣店/衣物洗护门店/家政服务点', '商家提供无隐私的示例衣物或示意卡', 5, '入门',
       '1. 启动三分钟倒计时。2. 观察示例污渍。3. 选择油性或水性线索分支。4. 在最终题中选出稳妥做法。本玩法只做常识互动 不承诺清洁效果 真实贵重衣物应交由专业人员判断。',
       (SELECT validation_method FROM seed_merchant_game_a8_expected WHERE title = '污渍侦探'),
       '面对不确定污渍 最稳妥的第一步是什么?', '先确认衣物材质和洗涤标签', '直接用最高温热水', '反复大力摩擦', '把多种清洁剂混合',
       'A', '先保护衣物 再处理污渍', '不确定时请交给专业人员',
       '先确认衣物材质和洗涤标签。',
       '判断完成 真正的污渍侦探先收集证据。',
       '一块污渍像一个现场。颜色 位置 材质和标签都是证据 贸然动手反而可能毁掉线索。',
       NULL, (SELECT advanced_config FROM seed_merchant_game_a8_config WHERE slug = 'stain_detective'),
       1, 1, 0, 0, 0, 'seed_merchant_game_a8_20260823', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_template_library
                    WHERE member_id = 100000 AND title = '污渍侦探' AND del_flag = 0);

-- TEMPLATE home_safety_mines|家居安全排雷|3|家政维修,装修,物业|branch
INSERT INTO seed_merchant_game_a8_config (slug, advanced_config, preference_config)
VALUES ('home_safety_mines', '{"schemaVersion":1,"branch":{"enabled":true,"startStepId":"scene","steps":[{"id":"scene","title":"发现异常","body":"家中出现陌生气味并伴随设备异常","terminal":false,"options":[{"id":"power","label":"疑似电器异常","nextStepId":"power_path","score":20},{"id":"gas","label":"疑似燃气异常","nextStepId":"gas_path","score":20}]},{"id":"power_path","title":"电器路径","body":"在确保人身安全前提下远离异常设备并求助专业人员","terminal":false,"options":[{"id":"call_power","label":"停止自行拆修并联系专业人员","nextStepId":"finish","score":30}]},{"id":"gas_path","title":"燃气路径","body":"避免产生火花并迅速离开危险区域","terminal":false,"options":[{"id":"call_gas","label":"到安全处联系紧急服务和专业人员","nextStepId":"finish","score":30}]},{"id":"finish","title":"排雷完成","body":"安全优先 不在游戏内远程诊断真实故障","outcomeCode":"finish","outcomeLabel":"排雷完成","terminal":true,"options":[]}]}}', NULL);
INSERT INTO cms_template_library (member_id, member_template_id, title, description, players,
        usage_location, required_materials, duration, difficulty, rule_instructions,
        validation_method, question_name, question_a, question_b, question_c, question_d,
        correct_answer, hint1, hint2, answer_reveal, feedback_text, story_text,
        preference_json, advanced_config_json,
        status, publish_status, is_sync, use_num, del_flag, create_by, create_time)
SELECT 100000, NULL, '家居安全排雷', '安全情境分支 练习先避险再求助', '单人/家庭/小队',
       '家政维修门店/装修展厅/物业活动区', '商家审核的公开安全提示 不使用真实故障现场', 5, '入门',
       '1. 阅读安全情境 不设置速度奖励。2. 选择电器或燃气分支。3. 完成避险顺序。4. 回答最终安全题。本玩法不远程诊断真实故障 遇到危险应立即离开并联系当地紧急服务或合格专业人员。',
       (SELECT validation_method FROM seed_merchant_game_a8_expected WHERE title = '家居安全排雷'),
       '面对可能危及人身安全的家居异常 首要原则是什么?', '先远离危险并在安全处求助', '自行拆开设备研究', '继续使用观察变化', '在现场反复开关电源',
       'A', '先保证人身安全', '游戏题不能替代专业现场判断',
       '先远离危险并在安全处求助。',
       '排雷完成 最重要的答案永远是先保护人。',
       '真正的排雷不是逞强修好一切 而是在异常出现时知道哪一步该停 哪一步该交给专业的人。',
       NULL, (SELECT advanced_config FROM seed_merchant_game_a8_config WHERE slug = 'home_safety_mines'),
       1, 1, 0, 0, 0, 'seed_merchant_game_a8_20260823', NOW()
 WHERE NOT EXISTS (SELECT 1 FROM cms_template_library
                    WHERE member_id = 100000 AND title = '家居安全排雷' AND del_flag = 0);

-- 让已执行过早版种子的环境也能在幂等重放时获得品类 媒体和新配置
-- 事务 runner 只放行单次 INNER JOIN先把配置并入品类临时表
UPDATE seed_merchant_game_a8_category c
  JOIN seed_merchant_game_a8_config g ON g.slug = c.slug
   SET c.advanced_config = g.advanced_config
 WHERE c.category_id IS NOT NULL;

UPDATE cms_template_library l
  JOIN seed_merchant_game_a8_category c ON c.title = l.title
   SET l.activity_categoryids = c.activity_categoryids,
       l.advanced_config_json = c.advanced_config,
       l.img_url = CASE l.title
           WHEN '招牌菜限时盲猜' THEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/signature-dish-blitz.jpg'
           WHEN '咖啡风味人格' THEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/coffee-flavor-personality.jpg'
           WHEN '隐藏菜单线索链' THEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/hidden-menu-clue-chain.jpg'
           WHEN '60秒健身知识反应赛' THEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/fitness-reaction-60.jpg'
           WHEN '节奏盲猜夜' THEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/rhythm-blind-guess.jpg'
           WHEN '分支故事接力' THEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/branch-story-relay.jpg'
           WHEN '污渍侦探' THEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/stain-detective.jpg'
           WHEN '家居安全排雷' THEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/home-safety-mines.jpg'
           ELSE l.img_url END,
       l.story_img = CASE l.title
           WHEN '招牌菜限时盲猜' THEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/signature-dish-blitz.jpg'
           WHEN '咖啡风味人格' THEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/coffee-flavor-personality.jpg'
           WHEN '隐藏菜单线索链' THEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/hidden-menu-clue-chain.jpg'
           WHEN '60秒健身知识反应赛' THEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/fitness-reaction-60.jpg'
           WHEN '节奏盲猜夜' THEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/rhythm-blind-guess.jpg'
           WHEN '分支故事接力' THEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/branch-story-relay.jpg'
           WHEN '污渍侦探' THEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/stain-detective.jpg'
           WHEN '家居安全排雷' THEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/home-safety-mines.jpg'
           ELSE l.story_img END,
       l.question_img = CASE l.title
           WHEN '招牌菜限时盲猜' THEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/signature-dish-blitz.jpg'
           WHEN '污渍侦探' THEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/stain-detective.jpg'
           WHEN '家居安全排雷' THEN 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/home-safety-mines.jpg'
           ELSE l.question_img END,
       l.question_audio = CASE l.title
           WHEN '节奏盲猜夜' THEN '/audio/merchant-games/rhythm-three.mp3'
           ELSE l.question_audio END
 WHERE l.member_id = 100000
   AND l.del_flag = 0
   AND l.create_by = 'seed_merchant_game_a8_20260823';

-- 事务 runner 不允许裸 SELECT
-- 断言不成立时向 guard 再插入主键 1 让整笔事务失败回滚
INSERT INTO seed_merchant_game_a8_guard (id)
SELECT 1
 WHERE NOT (
    (SELECT COUNT(*)
       FROM cms_template_library
      WHERE member_id = 100000
        AND del_flag = 0
        AND title IN ('招牌菜限时盲猜','咖啡风味人格','隐藏菜单线索链','60秒健身知识反应赛',
                      '节奏盲猜夜','分支故事接力','污渍侦探','家居安全排雷')) = 8
    AND NOT EXISTS (
        SELECT 1
          FROM seed_merchant_game_a8_expected e
          LEFT JOIN cms_template_library l
            ON l.member_id = 100000
           AND l.title = e.title
           AND l.del_flag = 0
          LEFT JOIN seed_merchant_game_a8_category c
            ON c.title = e.title
         WHERE l.id IS NULL
            OR COALESCE(l.validation_method, -1) <> e.validation_method
            OR COALESCE(l.status, -1) <> 1
            OR COALESCE(l.publish_status, -1) <> 1
            OR l.advanced_config_json IS NULL
            OR COALESCE(l.activity_categoryids, '') <> c.activity_categoryids
            OR COALESCE(l.create_by, '') <> 'seed_merchant_game_a8_20260823'
    )
    AND (SELECT COUNT(*) FROM cms_template_library
          WHERE member_id = 100000 AND del_flag = 0
            AND ((title = '招牌菜限时盲猜' AND question_img = 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/signature-dish-blitz.jpg')
              OR (title = '污渍侦探' AND question_img = 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/stain-detective.jpg')
              OR (title = '家居安全排雷' AND question_img = 'https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/home-safety-mines.jpg'))) = 3
    AND (SELECT COUNT(*) FROM cms_template_library
          WHERE member_id = 100000 AND del_flag = 0
            AND img_url LIKE '/images/merchant-games/%.jpg'
            AND story_img = img_url
            AND title IN ('招牌菜限时盲猜','咖啡风味人格','隐藏菜单线索链','60秒健身知识反应赛',
                          '节奏盲猜夜','分支故事接力','污渍侦探','家居安全排雷')) = 8
    AND (SELECT COUNT(*) FROM cms_template_library
          WHERE member_id = 100000 AND del_flag = 0 AND title = '节奏盲猜夜'
            AND question_audio = '/audio/merchant-games/rhythm-three.mp3'
            AND JSON_UNQUOTE(JSON_EXTRACT(advanced_config_json, '$.leaderboard.metric')) = 'ELAPSED_TIME'
            AND JSON_UNQUOTE(JSON_EXTRACT(advanced_config_json, '$.random.items[0].audioUrl')) = '/audio/merchant-games/rhythm-one.mp3'
            AND JSON_UNQUOTE(JSON_EXTRACT(advanced_config_json, '$.random.items[1].audioUrl')) = '/audio/merchant-games/rhythm-two.mp3'
            AND JSON_UNQUOTE(JSON_EXTRACT(advanced_config_json, '$.random.items[2].audioUrl')) = '/audio/merchant-games/rhythm-three.mp3') = 1
);

-- 回滚说明
-- 生产回滚前必须确认尚无采用副本和运行会话
-- 满足条件后仅删除 create_by=seed_merchant_game_a8_20260823 的八行
