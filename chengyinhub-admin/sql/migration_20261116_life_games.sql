-- ============================================================================
-- W7 x-life-games R12:《生活不掉线》G0/G3/G4/G6 与《今天值得庆祝》G0—G7 的货架行,给出真机制
-- (设计见 docs/roadmap/x-life-games/RESULT.md 开头)
--
-- 回滚说明(本文件不带 rollback_*.sql):
--   只改「仍是种子原文或 x-template-games 规则卡原文」的货架行(cms_template_library),
--   WHERE 钉住 create_by + validation_method=0 + 没有偏好题/正确答案 + 规则原文特征;商家改过的行不动。
--   如需回退,按 seed_life_backup_games_20260820 / seed_celebrate_games_20260821 / 20261116_template_games
--   的原文把 validation_method 写回 0,并把 description、rule_instructions、merchant_guide、
--   question_name、question_a..d、correct_answer、hint1、answer_reveal、feedback_text、preference_json 写回;
--   两个 G6 的 advanced_config_json 写回 NULL。
--
-- 做了什么(全部用两端已能玩的现成引擎,不加新玩法段):
--   · 没有对错的 → 偏好题组(validation_method=6,PreferenceResultEvaluator):
--     生活 G0(四步)、生活 G4(四张卡去掉一张)、庆祝 G0(两步)、G1(两步)、G2、G7(两步)。
--   · 有唯一正确答案的 → A—D 选项题(validation_method=3,服务端按 correct_answer 判):
--     生活 G3(营业时间重叠)、生活 G6(还来得及的备选)、庆祝 G3(三小时排程)、G4(预算)、
--     G5(每段步行 ≤10 分钟)、G6(时间/预算/不吵三条都成立的替代)。
--   · 计时:沿用 20260825 已配的 30 秒/3 小时/10 分钟/60 秒;两个 G6 新配 30 秒计时(只在还没配高级玩法时写)。
--
-- 只改货架行。两个主题自己的官方正本(cms_member_template member_id=100000)等业主拍板,
-- 要改的字段与本文件逐字相同,清单见 RESULT.md「主题正本待定」。
--
-- 偏好题组与计时配置由 LifeGamesSeedContractTest 逐条读出来跑 PreferenceResultEvaluator /
-- AdvancedGameConfigValidator;选项题的正确答案由同一测试按题面数字重算。
-- 不走 --transaction:用了多表 UPDATE ... JOIN(与 20261116_template_games 同理)。
-- 每条 UPDATE 都带原文守卫,中途失败重跑即收敛。
-- ⚠️ 本文件只准备,不执行。生产库写操作由总控在用户点头后执行(chengyin-db-migrate)。
-- ============================================================================

-- 预检(执行前人工跑):期望 12 行,validation_method 均为 0,preference_json 均为 NULL
--   SELECT title, create_by, validation_method, preference_json IS NULL AS no_pref, advanced_config_json
--     FROM cms_template_library
--    WHERE member_id = 100000 AND del_flag = 0
--      AND ((create_by = 'seed_life_backup_games_20260820' AND title REGEXP '^G[0346]｜')
--        OR create_by = 'seed_celebrate_games_20260821');

CREATE TEMPORARY TABLE life_games_mechanics (
    create_by   VARCHAR(64)  NOT NULL,
    title       VARCHAR(255) NOT NULL,
    vm          INT          NOT NULL,
    description VARCHAR(500) NOT NULL,
    rule_text   TEXT         NOT NULL,
    guide_text  TEXT         NOT NULL,
    question    VARCHAR(500) NULL,
    option_a    VARCHAR(255) NULL,
    option_b    VARCHAR(255) NULL,
    option_c    VARCHAR(255) NULL,
    option_d    VARCHAR(255) NULL,
    correct     VARCHAR(8)   NULL,
    hint1       VARCHAR(500) NULL,
    reveal      VARCHAR(500) NULL,
    feedback    VARCHAR(500) NULL,
    preference  JSON         NULL,
    timer       JSON         NULL,
    PRIMARY KEY (create_by, title)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

INSERT INTO life_games_mechanics (create_by, title, vm, description, rule_text, guide_text,
        question, option_a, option_b, option_c, option_d, correct, hint1, reveal, feedback, preference, timer) VALUES
    ('seed_life_backup_games_20260820', 'G0｜30秒生活掉线测试', 6, '30 秒里选情境、片区、时间和首要考虑，得到你的生活备份卡', '1. 开始后计时 30 秒。2. 依次选：最不想发生的情境、常活动的片区、腾得出手的时间、遇事先看什么。3. 选完生成你的生活备份卡。没有对错，片区只到街区，不问住址。', '商家配置：无。员工动作：零。不制造灾难恐惧，不宣称预测风险。', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, '{"dimensions":["near","fast","cheap","pro"],"steps":[{"key":"situation","type":"single","title":"此刻最不想发生哪一种？","options":[{"key":"clothes","text":"明早要穿的衣服还没洗","scores":{}},{"key":"lunch","text":"午休只剩 40 分钟还没吃饭","scores":{}},{"key":"home","text":"周末家里乱到不想回","scores":{}},{"key":"tyre","text":"通勤路上车胎没气","scores":{}}]},{"key":"area","type":"single","title":"你平时最常在哪一片活动？","options":[{"key":"home","text":"家附近","scores":{}},{"key":"work","text":"公司或学校附近","scores":{}},{"key":"commute","text":"通勤路上","scores":{}},{"key":"mall","text":"常逛的商圈","scores":{}}]},{"key":"time","type":"single","title":"你一般什么时候腾得出手？","options":[{"key":"noon","text":"工作日午休","scores":{}},{"key":"evening","text":"工作日下班后","scores":{}},{"key":"weekend","text":"周末白天","scores":{}},{"key":"any","text":"说不准，随时都可能","scores":{}}]},{"key":"concern","type":"single","title":"遇到这种事，你先看什么？","options":[{"key":"near","text":"离得近","scores":{"near":2}},{"key":"fast","text":"来得快","scores":{"fast":2}},{"key":"cheap","text":"花得少","scores":{"cheap":2}},{"key":"pro","text":"做得好","scores":{"pro":2}}]}],"tiebreak":{"key":"tiebreak","type":"single","title":"如果只能留一个，你最后看哪一点？","options":[{"key":"near","text":"离得近","scores":{"near":1}},{"key":"fast","text":"来得快","scores":{"fast":1}},{"key":"cheap","text":"花得少","scores":{"cheap":1}},{"key":"pro","text":"做得好","scores":{"pro":1}}]},"results":{"near":{"title":"你先看距离","body":"你选了：{{choices}}。顺路最重要，先在这一片找一家备用的。","nextStep":"在常活动的片区存一家备用店","nextStepDays":7},"fast":{"title":"你先看速度","body":"你选了：{{choices}}。来得及最重要，先找一家当天能处理的。","nextStep":"记下一家当天能处理的店","nextStepDays":7},"cheap":{"title":"你先看价格","body":"你选了：{{choices}}。价格清楚最重要，先比一比两家。","nextStep":"比较两家店的公开价格","nextStepDays":30},"pro":{"title":"你先看手艺","body":"你选了：{{choices}}。交给懂行的人最重要，先找一家口碑好的。","nextStep":"找一家口碑好的店存进收藏","nextStepDays":30}}}', NULL),
    ('seed_life_backup_games_20260820', 'G3｜营业时间拼图', 3, '把你的出门时间放进几家店的营业时间里，找出都开着的那一段', '1. 看三家店今天的营业时间。2. 从四个时间里选出三家都开着的那个。3. 选对就完成，选错可以再选。营业时间不等于一定能接单，出发前再确认。', '采用后可以把三家店的营业时间换成你店和附近店的真实时间，记得同时改正确选项。员工动作：零。', '今天你只能出门一次，要去三家店：洗衣店 9:00–18:00，修鞋铺 11:00–20:00，药店 14:00–22:00。哪个时间去，三家都开着？', '10:00', '13:00', '15:30', '19:00', 'C', '先找三家都开门之后、最早关门之前的那一段。', '三家都开着的是 14:00–18:00，只有 15:30 落在里面。', '时间对上了，把这一段记进你的可用时间。', NULL, NULL),
    ('seed_life_backup_games_20260820', 'G4｜去之前准备什么', 6, '四张准备卡去掉一张，剩下三张就是你的准备清单', '1. 看四张准备卡。2. 去掉一张你觉得不用准备的。3. 剩下三张就是你的准备清单。没有对错，不需要上传照片。', '商家资产：服务前提、所需物品、不可服务范围。采用后可以把四张卡换成你店里真正需要顾客准备的东西。员工动作：零。', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, '{"dimensions":["label","stain","pocket","pickup"],"steps":[{"key":"drop","type":"discard","title":"把一件大衣送去干洗前，去掉一张你觉得不用准备的卡","options":[{"key":"label","text":"看一眼洗标上的材质","scores":{"label":2}},{"key":"stain","text":"记下污渍在哪儿","scores":{"stain":2}},{"key":"pocket","text":"清空所有口袋","scores":{"pocket":2}},{"key":"pickup","text":"想好哪天来取","scores":{"pickup":2}}]}],"tiebreak":{"key":"tiebreak","type":"single","title":"如果只能先做一件，你先做哪件？","options":[{"key":"label","text":"看一眼洗标上的材质","scores":{"label":1}},{"key":"stain","text":"记下污渍在哪儿","scores":{"stain":1}},{"key":"pocket","text":"清空所有口袋","scores":{"pocket":1}},{"key":"pickup","text":"想好哪天来取","scores":{"pickup":1}}]},"results":{"label":{"title":"你的准备清单","body":"你去掉了：{{choices}}。剩下的三件：记下污渍在哪儿、清空所有口袋、想好哪天来取。出门前对一遍。","nextStep":"下次送洗前照这张清单准备","nextStepDays":30},"stain":{"title":"你的准备清单","body":"你去掉了：{{choices}}。剩下的三件：看一眼洗标上的材质、清空所有口袋、想好哪天来取。出门前对一遍。","nextStep":"下次送洗前照这张清单准备","nextStepDays":30},"pocket":{"title":"你的准备清单","body":"你去掉了：{{choices}}。剩下的三件：看一眼洗标上的材质、记下污渍在哪儿、想好哪天来取。出门前对一遍。","nextStep":"下次送洗前照这张清单准备","nextStepDays":30},"pickup":{"title":"你的准备清单","body":"你去掉了：{{choices}}。剩下的三件：看一眼洗标上的材质、记下污渍在哪儿、清空所有口袋。出门前对一遍。","nextStep":"下次送洗前照这张清单准备","nextStepDays":30}}}', NULL),
    ('seed_life_backup_games_20260820', 'G6｜首选失效怎么办', 3, '常去的店突然关门，30 秒内找一个还来得及的备选', '1. 开始后计时 30 秒。2. 看清首选为什么去不了、你什么时候要用。3. 选出还来得及的备选，选对就完成。超时可以重来。', '商家资产：状态、营业、服务范围和替代条件。采用后可以把四个备选换成你店附近的真实情况，记得同时改正确选项。员工动作：零。', '现在是 17:00。你常去的洗衣店今天临时关门，可大衣明早 8:00 要穿。选一个还来得及的备选：', '同一条街的另一家：营业到 18:00，最快后天取', '两站外的一家：营业到 21:00，加急 3 小时可取', '上门取送：明天下午送回', '等原来那家明天 10:00 开门', 'B', '先看哪一家能在明早 8:00 前把衣服交到你手上。', '只有两站外那家今晚就能取回，赶得上明早。把它存成你的备选。', '备选找到了：首选去不了时，你知道去哪。', NULL, '{"schemaVersion":1,"timer":{"enabled":true,"durationSeconds":30,"timeoutResult":"EXPIRED"}}'),
    ('seed_celebrate_games_20260821', 'G0｜30秒今天值得什么', 6, '30 秒里选出今天想庆祝什么、想怎么庆祝', '1. 开始后计时 30 秒。2. 选今天想庆祝的事，再选想要的庆祝方式。3. 选完生成你的庆祝卡。不用写单位、成绩或其他私事，卡片只归你自己。', '商家配置：无。员工动作：零。', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, '{"dimensions":["lively","quiet","craft","feast"],"steps":[{"key":"reason","type":"single","title":"今天想庆祝哪一种？","options":[{"key":"done","text":"终于完成了一件事","scores":{}},{"key":"first","text":"第一次做到","scores":{}},{"key":"through","text":"熬过了一关","scores":{}},{"key":"meet","text":"和重要的人重新见面","scores":{}},{"key":"start","text":"开启新阶段","scores":{}},{"key":"self","text":"只想认真夸自己一次","scores":{}}]},{"key":"style","type":"single","title":"想用哪种方式庆祝？","options":[{"key":"lively","text":"热闹一点","scores":{"lively":2}},{"key":"quiet","text":"安静一点","scores":{"quiet":2}},{"key":"craft","text":"动手做点什么","scores":{"craft":2}},{"key":"feast","text":"好好吃一顿","scores":{"feast":2}}]}],"tiebreak":{"key":"tiebreak","type":"single","title":"如果只能选一种，你更想要？","options":[{"key":"lively","text":"热闹一点","scores":{"lively":1}},{"key":"quiet","text":"安静一点","scores":{"quiet":1}},{"key":"craft","text":"动手做点什么","scores":{"craft":1}},{"key":"feast","text":"好好吃一顿","scores":{"feast":1}}]},"results":{"lively":{"title":"热闹庆祝","body":"你选了：{{choices}}。找一处能一起玩、出声也没关系的地方。","nextStep":"约上想见的人，定一个时间","nextStepDays":7},"quiet":{"title":"安静庆祝","body":"你选了：{{choices}}。找一处能坐下来、慢慢说话的地方。","nextStep":"给自己留出一段不被打扰的时间","nextStepDays":7},"craft":{"title":"动手庆祝","body":"你选了：{{choices}}。做一件能带走的小东西，留作纪念。","nextStep":"找一家能体验手作的店","nextStepDays":7},"feast":{"title":"吃一顿好的","body":"你选了：{{choices}}。好好吃一顿，就是最直接的庆祝。","nextStep":"挑一家一直想去的店","nextStepDays":7}}}', NULL),
    ('seed_celebrate_games_20260821', 'G1｜每人一个不能', 6, '选一个一定要有的、一个不能接受的，得到这次庆祝的硬条件', '1. 选一个这次一定要有的。2. 再选一个最不能接受的。3. 生成你的硬条件卡。同行的人各玩一遍，再把卡片对一对，留下大家都能接受的。系统不会替别人作答。', '一个人玩时只记自己的选择；系统不合并多人答案，也不展示谁否决了什么。员工动作：零。', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, '{"dimensions":["talk","food","hands","noqueue"],"steps":[{"key":"must","type":"single","title":"这次庆祝，你一定要有的是？","options":[{"key":"talk","text":"能坐下来好好聊天","scores":{"talk":2}},{"key":"food","text":"有好吃的","scores":{"food":2}},{"key":"hands","text":"能一起动手","scores":{"hands":2}},{"key":"noqueue","text":"不用排队","scores":{"noqueue":2}}]},{"key":"nogo","type":"single","title":"你最不能接受的是？","options":[{"key":"noisy","text":"太吵","scores":{}},{"key":"far","text":"要走很远","scores":{}},{"key":"pricey","text":"太贵","scores":{}},{"key":"crowd","text":"人太多","scores":{}}]}],"tiebreak":{"key":"tiebreak","type":"single","title":"如果只能保住一条，你保哪一条？","options":[{"key":"talk","text":"能坐下来好好聊天","scores":{"talk":1}},{"key":"food","text":"有好吃的","scores":{"food":1}},{"key":"hands","text":"能一起动手","scores":{"hands":1}},{"key":"noqueue","text":"不用排队","scores":{"noqueue":1}}]},"results":{"talk":{"title":"你的硬条件","body":"你选了：{{choices}}。这就是你这次的硬条件，拿去和同行的人对一对。","nextStep":"把这张卡发给要一起庆祝的人","nextStepDays":7},"food":{"title":"你的硬条件","body":"你选了：{{choices}}。这就是你这次的硬条件，拿去和同行的人对一对。","nextStep":"把这张卡发给要一起庆祝的人","nextStepDays":7},"hands":{"title":"你的硬条件","body":"你选了：{{choices}}。这就是你这次的硬条件，拿去和同行的人对一对。","nextStep":"把这张卡发给要一起庆祝的人","nextStepDays":7},"noqueue":{"title":"你的硬条件","body":"你选了：{{choices}}。这就是你这次的硬条件，拿去和同行的人对一对。","nextStep":"把这张卡发给要一起庆祝的人","nextStepDays":7}}}', NULL),
    ('seed_celebrate_games_20260821', 'G2｜气氛卡碰撞', 6, '挑一张今天想要的气氛卡，看看什么样的真实项目配得上它', '1. 从五张气氛卡里挑一张。2. 看结果卡给出的项目类型。3. 挑地方时按真实条件对照：噪音、能不能互动、几个人能玩。没有对错。', '商家资产：项目内容、噪音、互动方式、人数。采用后可以在结果里写上你店里对应的真实项目。员工动作：零。', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, '{"dimensions":["lively","relaxed","hands","new","quiet"],"steps":[{"key":"mood","type":"single","title":"今天你想要哪张气氛卡？","options":[{"key":"lively","text":"热闹","scores":{"lively":2}},{"key":"relaxed","text":"松弛","scores":{"relaxed":2}},{"key":"hands","text":"动手","scores":{"hands":2}},{"key":"new","text":"新鲜","scores":{"new":2}},{"key":"quiet","text":"安静","scores":{"quiet":2}}]}],"tiebreak":{"key":"tiebreak","type":"single","title":"如果只能再留一张，你留哪张？","options":[{"key":"lively","text":"热闹","scores":{"lively":1}},{"key":"relaxed","text":"松弛","scores":{"relaxed":1}},{"key":"hands","text":"动手","scores":{"hands":1}},{"key":"new","text":"新鲜","scores":{"new":1}},{"key":"quiet","text":"安静","scores":{"quiet":1}}]},"results":{"lively":{"title":"气氛卡：热闹","body":"你选了：{{choices}}。配得上它的是能一起玩出声的项目，比如桌游、合唱或小型派对包间。","nextStep":"按这张卡挑一个真实项目","nextStepDays":7},"relaxed":{"title":"气氛卡：松弛","body":"你选了：{{choices}}。配得上它的是能随时坐下、不赶时间的项目，比如喝茶、露台或公园野餐。","nextStep":"按这张卡挑一个真实项目","nextStepDays":7},"hands":{"title":"气氛卡：动手","body":"你选了：{{choices}}。配得上它的是能亲手做出东西的项目，比如陶艺、烘焙或花艺。","nextStep":"按这张卡挑一个真实项目","nextStepDays":7},"new":{"title":"气氛卡：新鲜","body":"你选了：{{choices}}。配得上它的是你们没试过的项目，比如一家新开的店或一种没玩过的体验。","nextStep":"按这张卡挑一个真实项目","nextStepDays":7},"quiet":{"title":"气氛卡：安静","body":"你选了：{{choices}}。配得上它的是噪音小、人不多的项目，比如书店、展览或安静的小馆。","nextStep":"按这张卡挑一个真实项目","nextStepDays":7}}}', NULL),
    ('seed_celebrate_games_20260821', 'G3｜三小时时间方块', 3, '把手作和晚餐排进三小时，找出不用空等的开始时间', '1. 看手作工作室、餐厅的时间和两地步行时间。2. 选出能在三小时内排完、中途不空等的开始时间。3. 选对就完成，选错可以再选。', '商家资产：营业时间、截单、项目时长、预约提前量。采用后可以换成你店和邻店的真实时间，记得同时改正确选项。员工动作：零。', '手作工作室 15:00 开门，最后一节 17:00 开始，一节 60 分钟；餐厅 17:30 开始营业，晚餐约 90 分钟；两地步行 15 分钟。先手作再吃饭，三小时内排完、中途不空等，几点开始手作？', '14:00', '15:00', '16:15', '17:30', 'C', '从餐厅开门的 17:30 往回推：走路 15 分钟，手作 60 分钟。', '16:15 开始手作，17:15 结束，走 15 分钟正好 17:30 进餐厅，19:00 吃完，一共 2 小时 45 分钟。14:00 工作室还没开，15:00 要空等一个多小时，17:30 已经过了最后一节。', '时间方块拼好了。', NULL, NULL),
    ('seed_celebrate_games_20260821', 'G4｜每人预算筹码', 3, '三段安排各有价格，找出不超每人预算的那一种', '1. 看每人预算和三段安排的人均价格。2. 选出不超预算的那一种安排。3. 选对就完成，选错可以再选。贵不代表更好。', '商家资产：价格带、计价单位、服务费、起订人数。采用后可以换成你店的真实价格，记得同时改正确选项。员工动作：零。', '每人预算 200 元。晚餐人均 120 元，手作人均 98 元，合影打印人均 30 元。哪一种安排不超预算？', '晚餐 + 手作', '晚餐 + 合影打印', '三样都要', '手作 + 合影打印 + 换成人均 88 元的晚餐', 'B', '把每种安排的人均价格加起来，和 200 比一比。', '晚餐加合影打印是 150 元，在 200 元以内。晚餐加手作 218 元，三样都要 248 元，换便宜的晚餐再加两样也要 216 元，都超了。', '预算守住了。', NULL, NULL),
    ('seed_celebrate_games_20260821', 'G5｜十分钟步行上限', 3, '先不看店名，只看每段步行时间，挑出每段都不超过 10 分钟的路线', '1. 四条路线先不告诉你店名，只给每段步行时间。2. 选出每一段都不超过 10 分钟的那条。3. 选对就揭晓。', '商家资产：真实路线时间、无障碍、天气条件。采用后可以换成你店附近的真实路线，并在揭晓里写上店名，记得同时改正确选项。员工动作：零。', '你们约好每段步行不超过 10 分钟。四条路线先不告诉你店名，只给两段步行时间，哪一条每段都不超？', '8 分钟 + 12 分钟', '9 分钟 + 6 分钟', '15 分钟 + 3 分钟', '11 分钟 + 9 分钟', 'B', '不看总时间，只看最长的那一段。', '只有 9 分钟加 6 分钟这条每段都在 10 分钟以内。总时间再短，只要有一段超了就不选。', '路线筛好了。', NULL, NULL),
    ('seed_celebrate_games_20260821', 'G6｜主选突然掉线', 3, '订好的地方突然满员，30 秒内找一个不破坏原计划的替代', '1. 开始后计时 30 秒。2. 看清原计划的时间、预算和硬条件。3. 选出三样都还成立的替代，选对就完成。超时可以重来，选错会告诉你哪一条没满足。', '采用后可以把四个替代换成你店附近的真实选择，记得同时改正确选项。员工动作：零。', '你们订好 18:00 的餐厅临时满员。原计划：18:00 开吃，人均不超过 120 元，不能太吵。选一个替代：', '隔壁烧烤：人均 90 元，18:00 有位，很热闹', '两条街外的家常菜：人均 110 元，安静，19:00 才有位', '楼上的小馆：人均 115 元，安静，18:00 有位，走 2 分钟', '新开的餐厅：人均 180 元，安静，18:00 有位', 'C', '时间、预算、不能太吵，三条要同时满足。', '楼上的小馆 18:00 有位、人均 115 元、安静，三条都满足。烧烤太吵，家常菜要等到 19:00，新开的那家超预算。', '计划修好了。', NULL, '{"schemaVersion":1,"timer":{"enabled":true,"durationSeconds":30,"timeoutResult":"EXPIRED"}}'),
    ('seed_celebrate_games_20260821', 'G7｜60秒共同落款', 6, '60 秒里选一个提示和一张短句卡，合成你的落款卡', '1. 开始后计时 60 秒。2. 选一个提示，再选一张短句卡。3. 生成你的落款卡。落款只留给你自己，不发给商家，不用公开，也不换券。', '落款不发送给商家、不要求公开分享、不换券。员工动作：零。', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, '{"dimensions":["remember","say","next"],"steps":[{"key":"prompt","type":"single","title":"选一个提示","options":[{"key":"remember","text":"今天最值得记住的是","scores":{"remember":2}},{"key":"say","text":"我想认真说一句","scores":{"say":2}},{"key":"next","text":"下次想继续","scores":{"next":2}}]},{"key":"phrase","type":"single","title":"选一张短句卡","options":[{"key":"together","text":"和你们一起","scores":{}},{"key":"made","text":"我做到了","scores":{}},{"key":"slow","text":"慢慢来也很好","scores":{}},{"key":"meal","text":"这一顿饭","scores":{}}]}],"tiebreak":{"key":"tiebreak","type":"single","title":"如果只能留一个提示，你留哪个？","options":[{"key":"remember","text":"今天最值得记住的是","scores":{"remember":1}},{"key":"say","text":"我想认真说一句","scores":{"say":1}},{"key":"next","text":"下次想继续","scores":{"next":1}}]},"results":{"remember":{"title":"今天的落款","body":"你的落款：{{choices}}。这张卡只留给你自己。","nextStep":"一个月后翻出来再看看","nextStepDays":30},"say":{"title":"想说的话","body":"你的落款：{{choices}}。想说的话，可以当面说给那个人听。","nextStep":"找个机会把这句话说出口","nextStepDays":7},"next":{"title":"下次见","body":"你的落款：{{choices}}。把下次想做的事记下来。","nextStep":"约好下一次的时间","nextStepDays":30}}}', NULL);

-- ① 两个 G6 的 30 秒计时:只在货架行还没配高级玩法、且仍是 vm=0 原文时写
UPDATE cms_template_library l
  JOIN life_games_mechanics c ON c.create_by = l.create_by AND c.title = l.title
   SET l.advanced_config_json = c.timer
 WHERE l.member_id = 100000
   AND l.del_flag = 0
   AND c.timer IS NOT NULL
   AND l.advanced_config_json IS NULL
   AND l.validation_method = 0;

-- ② 货架行:换成真机制。守卫 = 仍是 vm=0、没有偏好题和正确答案、规则仍是种子原文或规则卡原文
UPDATE cms_template_library l
  JOIN life_games_mechanics c ON c.create_by = l.create_by AND c.title = l.title
   SET l.validation_method = c.vm,
       l.description = c.description,
       l.rule_instructions = c.rule_text,
       l.merchant_guide = c.guide_text,
       l.question_name = c.question,
       l.question_a = c.option_a, l.question_b = c.option_b,
       l.question_c = c.option_c, l.question_d = c.option_d,
       l.correct_answer = c.correct,
       l.hint1 = c.hint1, l.hint2 = NULL,
       l.answer_reveal = c.reveal,
       l.feedback_text = c.feedback,
       l.preference_json = c.preference,
       l.update_by = 'life_games_20261116',
       l.update_time = NOW()
 WHERE l.member_id = 100000
   AND l.del_flag = 0
   AND l.validation_method = 0
   AND l.preference_json IS NULL
   AND (l.correct_answer IS NULL OR l.correct_answer = '')
   AND (l.rule_instructions LIKE '玩家动作%' OR l.rule_instructions LIKE '这是一张规则卡%');

DROP TEMPORARY TABLE life_games_mechanics;

-- 执行后断言:十二个货架行不再是「到点即完成」的规则卡。
-- runner: assert=scalar-true
SELECT (
  (SELECT COUNT(*) FROM cms_template_library
    WHERE member_id = 100000 AND del_flag = 0 AND validation_method = 0
      AND ((create_by = 'seed_life_backup_games_20260820'
            AND title IN ('G0｜30秒生活掉线测试','G3｜营业时间拼图','G4｜去之前准备什么','G6｜首选失效怎么办'))
        OR create_by = 'seed_celebrate_games_20260821')) = 0
) AS life_games_mechanics_live;
