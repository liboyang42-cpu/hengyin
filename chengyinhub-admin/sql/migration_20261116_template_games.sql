-- ============================================================================
-- W7 x-template-games:货架上 19 个官方玩法的内容修订(审查见 docs/roadmap/x-template-games/REVIEW.md)
--
-- 回滚说明(本文件不带 rollback_*.sql):
--   每一步都只改「仍是种子原文」的行(WHERE 钉住 create_by + 原文特征),人工改过的行不动。
--   如需回退,按 seed_template_library_12packs_20260820 / seed_life_backup_games_20260820 的原文字段
--   逐列写回(validation_method、rule_instructions、merchant_guide、preference_json、
--   question_a..d、correct_answer、hint1、answer_reveal、photo_review、advanced_config_json)。
--
-- 做了什么:
--   R3 十二个通用玩法 rule_instructions 末尾的「(运营提示:…)」是给商家看的,玩家到站后原样可见。
--      搬进 merchant_guide(玩家侧任何端点都不下发),规则改成只给玩家看的话。
--      已采用、且规则仍逐字等于库行原文的商家副本一起改(只在副本 merchant_guide 为空时)。
--   R1 新品盲测局:原来是「正确答案」模型,玩家照实选了「口感」会被判「答案不正确」。
--      改成偏好题组(validation_method=6),四个选项各自出一张结果卡,没有对错。
--   R6 生活不掉线 G1/G2/G5 写着「三选一」却没有任何选项(vm=0 到点即完成):货架行改成偏好题组。
--      G0/G3/G4/G6 的机制(时间拼图、卡片三选、30 秒切备选)没有实现:规则改成如实的「规则卡」说明。
--      只改货架行(cms_template_library);《生活不掉线》主题读的官方正本(cms_member_template)不动,
--      主题内的完整流程另行决定(见 RESULT.md)。
--   R4 城市取景任务:人工审核 2026-09-17 已取消,photo_review 置 0,删掉「等待收入影集/评选」的承诺。
--      四个拍照玩法都写明「照片不做自动识别」。
--   R5 今日角色任务:加一副随机角色卡(advanced random,每人抽 1 张),两端都已支持随机卡。
--   R7 小队竞速点亮:写明要在活动场次里组队才能进(0826 已开 multiplayer,自玩进不去),排行榜按小队用时。
--   R8 城市接力棒:写明接力顺序由领队现场安排,系统只记每队这一棒。
--
-- 偏好题组配置由 TemplateGamesSeedContractTest 逐条读出来跑 PreferenceResultEvaluator;
-- 随机角色卡由同一测试跑 AdvancedGameConfigValidator。
-- 不走 --transaction:用了多表 UPDATE ... JOIN(与 20260825_named_mechanic_advanced_config 同理)。
-- 每条 UPDATE 都带原文守卫,中途失败重跑即收敛。
-- ⚠️ 本文件只准备,不执行。生产库写操作由总控在用户点头后执行(chengyin-db-migrate)。
-- ============================================================================

-- 预检(执行前人工跑):期望 12 行 has_ops_note=1
--   SELECT title, rule_instructions LIKE '%(运营提示:%' AS has_ops_note, validation_method, photo_review
--     FROM cms_template_library
--    WHERE member_id = 100000 AND del_flag = 0 AND create_by = 'seed_library_12packs_20260820';

CREATE TEMPORARY TABLE template_games_copy (
    title       VARCHAR(255) NOT NULL PRIMARY KEY,
    description VARCHAR(500) NULL,
    rule_text   TEXT NOT NULL,
    guide_text  TEXT NULL,
    feedback    VARCHAR(500) NULL,
    photo_desc  VARCHAR(500) NULL,
    hint1       VARCHAR(500) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

INSERT INTO template_games_copy (title, description, rule_text, guide_text, feedback, photo_desc, hint1) VALUES
    ('今晚的暗号', NULL,
     '1. 到达指定门店。2. 向店员要今日暗号。3. 在这里输入暗号，完成任务。',
     '暗号每天可换一次。采用后请把暗号换成你店里的，并提前告诉店员暗号和奖励，否则现场体验会断。',
     NULL, NULL, '暗号要向店员要，不在屏幕里'),
    ('老板的秘密题', NULL,
     '1. 阅读店铺故事或观察现场线索。2. 回答老板留下的问题。3. 答对后点亮店铺故事徽章。',
     '题目必须能从现场找到答案，不要出只有老板知道、玩家猜不到的问题。采用后请把题目和四个选项换成你店里的真实细节。',
     NULL, NULL, NULL),
    ('拍下这一刻', NULL,
     '1. 找到店内指定拍照点。2. 拍下符合要求的照片。3. 上传后完成任务。照片不做自动识别，请按要求拍。',
     '拍照要求必须具体，例如「包含店铺招牌」或「拍下今日限定甜品」，不要写「拍一张好看的照片」。照片不做自动识别，提交即完成。想按到店发券，请改用扫码或店员核销。',
     NULL, '拍下你和店内指定元素的合影，画面需包含店铺标识或指定商品（照片不做自动识别）', NULL),
    ('收集这枚风味印章', NULL,
     '1. 到达合作门店。2. 扫店内的码，或请店员核销。3. 点亮这枚风味印章。',
     '必须提前定义集几枚章可兑换什么，建议 3 家给基础奖励，5 家给进阶奖励。二维码在路线或点位层绑定。',
     NULL, NULL, NULL),
    ('新品盲测局', NULL,
     '1. 到店领取新品体验。2. 完成试吃、试喝或试用。3. 选出最打动你的一点。没有对错，你的选择会作为反馈交给店家。',
     '这是偏好题，没有正确答案。四个选项可以换成你最想了解的维度。每位玩家的选择按站汇总，在商家端「偏好结果」里查看。',
     '体验官反馈已交给店家。', NULL, NULL),
    ('今日搭配任务', NULL,
     '1. 按任务要求选择商品或套餐组合。2. 拍下你的搭配成果。3. 上传后完成任务。照片不做自动识别，请按要求拍。',
     '商家要提前准备 2-3 套推荐搭配，降低用户选择成本。照片不做自动识别，提交即完成。',
     NULL, '拍下你的搭配成果，画面需包含至少 2 件指定商品或套餐组合（照片不做自动识别）', NULL),
    ('集合点亮', '到达集合点，扫码签到出发',
     '1. 到达集合点。2. 扫领队手里的集合码签到。3. 签到完成，准备出发。',
     '默认放在俱乐部主题的第一个节点，解决迟到、散乱、陌生人尴尬。小规模熟人局可改为无需验证。想给每人发今日身份，请配合「今日角色任务」。',
     NULL, NULL, NULL),
    ('小队竞速点亮', '和你的小队抢先点亮目标点',
     '1. 报名活动场次并加入一支小队，一个人自玩时进不了这一关。2. 小队一起出发，90 分钟内到达目标点 50 米内。3. 点亮后看本站排行榜，按小队用时排名。',
     '本玩法要活动场次和队伍，自玩场景进不去。坐标在路线或点位层绑定，GPS 偏差大的点位可改扫码。排行榜只按用时排，不计任务数。',
     '目标点已点亮，小队用时已记入本站排行榜。', NULL, NULL),
    ('街角谜题', NULL,
     '1. 到达指定地点。2. 观察现场细节，找到线索。3. 输入答案，解锁下一段故事。',
     '谜题答案必须稳定存在，不要依赖临时海报、会被遮挡的物件或容易变化的菜单。采用后请把题目和答案换成你选的地点。',
     NULL, NULL, NULL),
    ('城市取景任务', NULL,
     '1. 阅读今日拍摄主题。2. 在指定区域内寻找画面。3. 上传照片，完成取景任务。照片不做自动识别，提交即完成。',
     '系统不做评选。如要评选，请由主办方在活动外自行评选，并提前说明按主题契合、构图、故事感评分。',
     '作品已收进你的城市相册。', '拍下一个符合主题的城市画面：红色门牌、老窗、街角光影或一处生活瞬间（照片不做自动识别）', NULL),
    ('城市接力棒', '完成你的线索，交出这一棒',
     '1. 完成本队线索，得到接力暗号。2. 在这里输入暗号，本队这一棒就记下了。3. 接力顺序由领队现场安排，等领队示意下一队出发。',
     '队伍顺序由领队线下控制，系统只记录每队这一棒是否完成。采用后请把接力暗号换成你自己的。',
     '这一棒已记录。等领队示意下一队出发。', NULL, NULL),
    ('今日角色任务', NULL,
     '1. 每位队员先在这里抽一张角色卡。2. 按角色分工完成任务。3. 拍下小队完成任务的证据照片并上传。照片不做自动识别。',
     '角色由系统随机发，每人一张：导航员、观察员、记录员、交涉员。小队证据照不做自动识别。',
     NULL, '拍下小队完成角色任务的证据，画面需包含至少 2 名队员和任务地点（照片不做自动识别）', NULL),
    ('G0｜30秒生活掉线测试', NULL,
     '这是一张规则卡：读完后到点即完成，这里没有选择界面。完整的选情境、选片区、选时间流程在《生活不掉线》主题里进行。',
     '商家配置：无。员工动作：零。不制造灾难恐惧，不宣称预测风险，片区默认模糊到街区，不要求精确住址。',
     NULL, NULL, NULL),
    ('G1｜现在出状况,你先选什么', '看一个日常小状况，从三种处理方式里选一种，得到你的优先级卡',
     '读一个日常小状况，从三种处理方式里选一种。没有对错，结果只表示你自己的优先级。',
     '商家只确认平台生成的三个真实选项，以及现有服务、营业时间和处理方式。员工动作：零。',
     NULL, NULL, NULL),
    ('G2｜我的底线三选一', '从五个条件里选出你最不能妥协的一个',
     '从更近、更快、更省、更专业、可上门或取送里，选出你最不能妥协的一个。没有对错。',
     '商家只确认本店确实能提供哪些条件：真实价格带、服务时长、距离和方式。员工动作：零。',
     NULL, NULL, NULL),
    ('G3｜营业时间拼图', NULL,
     '这是一张规则卡：读完后到点即完成，这里没有拼图界面。出发前请自己核对营业时间，营业时间不等于一定能接单。',
     '商家资产：营业时间、截单时间、休息日、预约要求。员工动作：零。',
     NULL, NULL, NULL),
    ('G4｜去之前准备什么', NULL,
     '这是一张规则卡：读完后到点即完成，这里没有选卡界面。使用服务前，想想要带的三样东西，例如衣物标签、尺寸、车辆型号。不需要上传照片。',
     '商家资产：服务前提、所需物品、不可服务范围。员工动作：零。',
     NULL, NULL, NULL),
    ('G5｜值不值得现在处理', '看清价格和时间后，选现在处理、先咨询，还是先存起来',
     '看清起步价、时间和服务范围后，选现在处理、先咨询，还是先存起来。结果只表示你此刻的打算，不构成专业诊断或报价。',
     '商家只确认价格与边界：透明价格、处理时长、包含与不含项。员工动作：零。',
     NULL, NULL, NULL),
    ('G6｜首选失效怎么办', NULL,
     '这是一张规则卡：读完后到点即完成，这里没有计时演练。想一想：常去的那家关门了，你的第二选择是哪家？',
     '商家资产：状态、营业、服务范围和替代条件。员工动作：零。',
     NULL, NULL, NULL);

-- ① 已采用的商家副本:规则仍逐字等于库行原文(带运营提示)、商家也没写过 merchant_guide 才改
UPDATE cms_member_template m
  JOIN cms_template_library l ON l.id = m.original_template_id
  JOIN template_games_copy c ON c.title = l.title
   SET m.rule_instructions = c.rule_text,
       m.merchant_guide = c.guide_text
 WHERE l.member_id = 100000
   AND l.create_by = 'seed_library_12packs_20260820'
   AND l.rule_instructions LIKE '%(运营提示:%'
   AND m.rule_instructions = l.rule_instructions
   AND (m.merchant_guide IS NULL OR m.merchant_guide = '')
   AND m.del_flag = 0;

-- ② 货架库行:十二个通用玩法(原文仍带运营提示)
UPDATE cms_template_library l
  JOIN template_games_copy c ON c.title = l.title
   SET l.rule_instructions = c.rule_text,
       l.merchant_guide = c.guide_text,
       l.description = COALESCE(c.description, l.description),
       l.feedback_text = COALESCE(c.feedback, l.feedback_text),
       l.photo_require_desc = COALESCE(c.photo_desc, l.photo_require_desc),
       l.hint1 = COALESCE(c.hint1, l.hint1),
       l.update_by = 'template_games_20261116',
       l.update_time = NOW()
 WHERE l.member_id = 100000
   AND l.del_flag = 0
   AND l.create_by = 'seed_library_12packs_20260820'
   AND l.rule_instructions LIKE '%(运营提示:%';

-- ③ 货架库行:生活不掉线七个(原文仍带「商家资产/商家配置」字样、还没改过)
UPDATE cms_template_library l
  JOIN template_games_copy c ON c.title = l.title
   SET l.rule_instructions = c.rule_text,
       l.merchant_guide = c.guide_text,
       l.description = COALESCE(c.description, l.description),
       l.update_by = 'template_games_20261116',
       l.update_time = NOW()
 WHERE l.member_id = 100000
   AND l.del_flag = 0
   AND l.create_by = 'seed_life_backup_games_20260820'
   AND (l.rule_instructions LIKE '%商家资产%' OR l.rule_instructions LIKE '%商家配置%');

DROP TEMPORARY TABLE template_games_copy;

-- ④ 偏好题组:新品盲测局(原是「正确答案 A」)与 G1/G2/G5(原是 vm=0 没有选项)
CREATE TEMPORARY TABLE template_games_preference (
    title  VARCHAR(255) NOT NULL PRIMARY KEY,
    config JSON NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

INSERT INTO template_games_preference (title, config) VALUES
    ('新品盲测局', '{"dimensions":["aroma","texture","look","value"],"steps":[{"key":"pick","type":"single","title":"试完之后，最打动你的是哪一点？","options":[{"key":"aroma","text":"香气","scores":{"aroma":2}},{"key":"texture","text":"口感","scores":{"texture":2}},{"key":"look","text":"造型","scores":{"look":2}},{"key":"value","text":"性价比","scores":{"value":2}}]}],"tiebreak":{"key":"tiebreak","type":"single","title":"如果只能再推荐一点给朋友，你会说哪一点？","options":[{"key":"aroma","text":"香气","scores":{"aroma":1}},{"key":"texture","text":"口感","scores":{"texture":1}},{"key":"look","text":"造型","scores":{"look":1}},{"key":"value","text":"性价比","scores":{"value":1}}]},"results":{"aroma":{"title":"香气派体验官","body":"你选了：{{choices}}。店家会知道这款新品的香气留住了你。","nextStep":"下次路过，试试同系列的另一款","nextStepDays":7},"texture":{"title":"口感派体验官","body":"你选了：{{choices}}。店家会知道这款新品的口感最打动你。","nextStep":"下次路过，试试同系列的另一款","nextStepDays":7},"look":{"title":"颜值派体验官","body":"你选了：{{choices}}。店家会知道这款新品的造型先抓住了你。","nextStep":"下次路过，试试同系列的另一款","nextStepDays":7},"value":{"title":"实在派体验官","body":"你选了：{{choices}}。店家会知道你最看重这款新品的性价比。","nextStep":"下次路过，试试同系列的另一款","nextStepDays":7}}}'),
    ('G1｜现在出状况,你先选什么', '{"dimensions":["speed","price","distance"],"steps":[{"key":"pick","type":"single","title":"明早要穿的外套脏了，需要处理。你先选哪一种？","options":[{"key":"speed","text":"今天就能取的，贵一点也行","scores":{"speed":2}},{"key":"price","text":"最便宜的，多等两天也行","scores":{"price":2}},{"key":"distance","text":"离家最近的，顺路就去","scores":{"distance":2}}]}],"tiebreak":{"key":"tiebreak","type":"single","title":"如果三家都差不多，最后让你拍板的是？","options":[{"key":"speed","text":"今天就能取的，贵一点也行","scores":{"speed":1}},{"key":"price","text":"最便宜的，多等两天也行","scores":{"price":1}},{"key":"distance","text":"离家最近的，顺路就去","scores":{"distance":1}}]},"results":{"speed":{"title":"你先看速度","body":"你选了：{{choices}}。遇到急事，你愿意为快多花一点。","nextStep":"把一家当天能取的店存进收藏","nextStepDays":7},"price":{"title":"你先看价格","body":"你选了：{{choices}}。不急的事，你更愿意省一点。","nextStep":"比较两家常用店的价格","nextStepDays":30},"distance":{"title":"你先看距离","body":"你选了：{{choices}}。顺路对你最重要。","nextStep":"找一家回家路上的店存进收藏","nextStepDays":7}}}'),
    ('G2｜我的底线三选一', '{"dimensions":["near","fast","cheap","pro","pickup"],"steps":[{"key":"pick","type":"single","title":"这五个条件里，你最不能妥协的是哪一个？","options":[{"key":"near","text":"更近","scores":{"near":2}},{"key":"fast","text":"更快","scores":{"fast":2}},{"key":"cheap","text":"更省","scores":{"cheap":2}},{"key":"pro","text":"更专业","scores":{"pro":2}},{"key":"pickup","text":"能上门或取送","scores":{"pickup":2}}]}],"tiebreak":{"key":"tiebreak","type":"single","title":"如果只能再保留一个，你留哪一个？","options":[{"key":"near","text":"更近","scores":{"near":1}},{"key":"fast","text":"更快","scores":{"fast":1}},{"key":"cheap","text":"更省","scores":{"cheap":1}},{"key":"pro","text":"更专业","scores":{"pro":1}},{"key":"pickup","text":"能上门或取送","scores":{"pickup":1}}]},"results":{"near":{"title":"底线：近","body":"你选了：{{choices}}。离得远的再好，你也不太会去。","nextStep":"在常活动的片区里找一家备选","nextStepDays":30},"fast":{"title":"底线：快","body":"你选了：{{choices}}。等不了，是你最先排除选项的理由。","nextStep":"记下一家当天能处理的店","nextStepDays":7},"cheap":{"title":"底线：省","body":"你选了：{{choices}}。价格清楚、不乱加钱，你才放心。","nextStep":"记下一家价格公开的店","nextStepDays":30},"pro":{"title":"底线：专业","body":"你选了：{{choices}}。交给懂行的人，你才安心。","nextStep":"找一家有专业资质的店存进收藏","nextStepDays":30},"pickup":{"title":"底线：省事","body":"你选了：{{choices}}。能上门或取送，能省下你最缺的时间。","nextStep":"查一家支持取送的店","nextStepDays":7}}}'),
    ('G5｜值不值得现在处理', '{"dimensions":["now","ask","later"],"steps":[{"key":"pick","type":"single","title":"看完价格、时间和服务范围，你现在打算怎么办？","options":[{"key":"now","text":"现在就处理","scores":{"now":2}},{"key":"ask","text":"先咨询一下","scores":{"ask":2}},{"key":"later","text":"先存起来，以后再说","scores":{"later":2}}]}],"tiebreak":{"key":"tiebreak","type":"single","title":"如果今天只能做一件事，你选？","options":[{"key":"now","text":"现在就处理","scores":{"now":1}},{"key":"ask","text":"先咨询一下","scores":{"ask":1}},{"key":"later","text":"先存起来，以后再说","scores":{"later":1}}]},"results":{"now":{"title":"现在处理","body":"你选了：{{choices}}。这件事对你已经够急了。","nextStep":"出发前确认营业时间","nextStepDays":7},"ask":{"title":"先问清楚","body":"你选了：{{choices}}。先把价格和边界问清楚，再决定。","nextStep":"这周内问一家店的报价","nextStepDays":7},"later":{"title":"先存起来","body":"你选了：{{choices}}。不急，但别忘了它。","nextStep":"一个月后回来看看这件事","nextStepDays":30}}}');

UPDATE cms_template_library l
  JOIN template_games_preference p ON p.title = l.title
   SET l.validation_method = 6,
       l.preference_json = p.config,
       l.question_name = NULL,
       l.question_a = NULL, l.question_b = NULL, l.question_c = NULL, l.question_d = NULL,
       l.correct_answer = NULL,
       l.hint1 = NULL, l.hint2 = NULL,
       l.answer_reveal = NULL,
       l.update_by = 'template_games_20261116',
       l.update_time = NOW()
 WHERE l.member_id = 100000
   AND l.del_flag = 0
   AND l.preference_json IS NULL
   AND ((l.create_by = 'seed_library_12packs_20260820' AND l.validation_method = 3 AND l.correct_answer = 'A')
     OR (l.create_by = 'seed_life_backup_games_20260820' AND l.validation_method = 0));

DROP TEMPORARY TABLE template_games_preference;

-- ⑤ 城市取景任务:人工审核已于 2026-09-17 取消,审核位如实归零
UPDATE cms_template_library
   SET photo_review = 0
 WHERE member_id = 100000 AND del_flag = 0
   AND create_by = 'seed_library_12packs_20260820'
   AND title = '城市取景任务' AND photo_review = 1;

-- ⑥ 今日角色任务:随机角色卡(每人抽 1 张),只在还没配高级玩法时写
UPDATE cms_template_library
   SET advanced_config_json = '{"schemaVersion":1,"random":{"enabled":true,"deckName":"今日角色","drawCount":1,"items":[{"id":"navigator","label":"导航员","weight":1,"content":"你负责看路，带小队安全走到任务地点。"},{"id":"observer","label":"观察员","weight":1,"content":"你负责找线索：门牌、招牌、墙角和旧物。"},{"id":"recorder","label":"记录员","weight":1,"content":"你负责留下证据：拍下小队完成任务的那一刻。"},{"id":"negotiator","label":"交涉员","weight":1,"content":"你负责开口：和店员或路人打招呼、问路、问答案。"}]}}'
 WHERE member_id = 100000 AND del_flag = 0
   AND create_by = 'seed_library_12packs_20260820'
   AND title = '今日角色任务' AND advanced_config_json IS NULL;

-- 执行后断言:货架上的十二个通用玩法不再向玩家展示运营提示;新品盲测局已是偏好题组。
-- runner: assert=scalar-true
SELECT (
  (SELECT COUNT(*) FROM cms_template_library
    WHERE member_id = 100000 AND del_flag = 0
      AND create_by = 'seed_library_12packs_20260820'
      AND rule_instructions LIKE '%运营提示%') = 0
  AND
  (SELECT COUNT(*) FROM cms_template_library
    WHERE member_id = 100000 AND del_flag = 0
      AND create_by = 'seed_library_12packs_20260820'
      AND title = '新品盲测局' AND validation_method = 3) = 0
) AS template_games_revised;
