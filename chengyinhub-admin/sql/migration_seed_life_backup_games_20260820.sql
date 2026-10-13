-- runner: requires=transaction
-- ============================================================================
-- 《生活不掉线》G0—G6 七个即插即用游戏落位
--
-- 补 migration_seed_topic_template_shelf_20260726.sql「未落位内容清单」第 1 条:
-- 主题本体 2026-07 已落库(cms_topic id=990012,1 主题/4 章节/4 章节元数据),
-- 但 games[] 恒空 —— cms_topic_node 为 0,G0—G6 在 cms_member_template 一行都没有。
--
-- 内容真源(逐字段可回溯):
--   ~/Documents/城瘾/文档整理_20260711/05-参考资料/归档_20260803/状态终审_原04/
--   Questify_生活不掉线_大众即插即用主题与游戏模板_20260724.md §5 / §6
--
-- 实现口径(2026-08-20 现码核实,推翻 07-26 种子的枚举顾虑):
--   · interaction_type 留 NULL —— 线上 20 个存量玩法模板全部为 NULL,该字段只服务
--     商家点亮城市节点语义,普通模板不用;不扩枚举、零 DDL。
--   · validation_method = 0(到点即完成/无判分)—— §5.1 统一合同「人工判分/解释:0」,
--     G0—G5 本就没有对错(结果=玩家自己的优先级/清单/意图);玩家端 pages/play 对 vm=0 现成渲染。
--   · 三张表都写:
--     cms_member_template(member_id=100000 平台自营)= 主题详情 games[] 的数据链
--       (cms_topic_node.template_id → cms_member_template,见 buildTopicTemplateDetail);
--     cms_template_library = 小程序公共模板库(/api/template/list),归「世界一刻」(18) tab;
--     cms_topic_node = 按 §6 角色×游戏匹配接线:
--       吃饭(主 G3)+G0 入口+G6 终局 / 衣物(主 G4)+可选 G5+G6 / 家里(主 G1)+G6 / 出行(主 G1)+可选 G2+G6。
--   · 节点无坐标、无打卡码、无营业时间 —— §4.4 不要求到店,§P0-5 不得用 GPS/扫码当通关条件。
--   · main_skeleton_template_id 从 NULL 补指 G0(07-26 留空只因当时 G0 不存在,现在有真实行)。
--
-- ★ 幂等:INSERT ... SELECT ... WHERE NOT EXISTS;模板幂等键=(member_id=100000, title),
--   节点幂等键=(chapter_id, name),UPDATE 仅在 main_skeleton_template_id IS NULL 时生效。
-- ★ 纯 DML(INSERT/UPDATE),全表 InnoDB;逐行单表锚点 / 单 JOIN 形状,
--   完全落在 --transaction 白名单内 —— 必须带 --transaction 执行,整批原子。
-- ============================================================================


-- ======================= 只读预检(执行前人工跑) =======================
-- 1) 主题与四章在位:
--   SELECT id FROM cms_topic WHERE name='生活不掉线' AND del_flag=0;                -- 期望 1 行
--   SELECT ch.name FROM cms_topic_chapter ch JOIN cms_topic t ON t.id=ch.topic_id
--    WHERE t.name='生活不掉线' ORDER BY ch.sort_id;                                 -- 期望 4 行
-- 2) 游戏未落位(本文件未跑时):
--   SELECT COUNT(*) FROM cms_member_template WHERE member_id=100000 AND title LIKE 'G_｜%';  -- 期望 0
--   SELECT COUNT(*) FROM cms_topic_node n JOIN cms_topic t ON t.id=n.topic_id
--    WHERE t.name='生活不掉线';                                                     -- 期望 0


-- ======================= 一、七个玩法模板(cms_member_template) =======================
-- 公共列:平台自营 100000;players='1人';不到店不扫码;is_sync=1;审核/上架/发布全通过。

INSERT INTO cms_member_template
       (member_id, title, description, players, usage_location, required_materials,
        duration, difficulty, is_sync, rule_instructions, validation_method,
        status, publish_status, draft_status, check_status, del_flag, create_by, create_time)
SELECT 100000, 'G0｜30秒生活掉线测试',
       '平台入口:从四个轻情境中选择「此刻最不想发生哪一种」,再选常活动片区、可达时间和首要考虑,产出 AREA/RADIUS/TIME_WINDOW/PRIORITY 初值。',
       '1人', '本地生活圈(线上完成,不要求到店)', '无需任何物料',
       1, '简单', 1,
       '玩家动作:选情境→选片区→选可达时间→选首要考虑。商家配置:无。员工动作:零。限制:不制造灾难恐惧,不宣称预测风险;片区默认模糊到街区,不要求精确住址。',
       0, 1, 1, 1, 1, 0, 'seed_life_backup_games_20260820', NOW()
  FROM cms_topic t
 WHERE t.name = '生活不掉线' AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_member_template m
        WHERE m.member_id = 100000 AND m.title = 'G0｜30秒生活掉线测试' AND m.del_flag = 0);

INSERT INTO cms_member_template
       (member_id, title, description, players, usage_location, required_materials,
        duration, difficulty, is_sync, rule_instructions, validation_method,
        status, publish_status, draft_status, check_status, del_flag, create_by, create_time)
SELECT 100000, 'G1｜现在出状况,你先选什么',
       '看到一个与角色有关的日常情境(午休剩40分钟/明早要穿的外套需要处理/周末小型清洁/通勤车胎异常),从三种处理方式中选择,形成速度、价格、距离或服务方式的优先级。',
       '1人', '本地生活圈(线上完成,不要求到店)', '无需任何物料',
       3, '简单', 1,
       '玩家动作:读情境→三选一。商家资产:现有服务、营业时间、处理方式(商家只确认平台生成的三个真实选项)。员工动作:零。结果只表示玩家自己的优先级,系统不判对错。',
       0, 1, 1, 1, 1, 0, 'seed_life_backup_games_20260820', NOW()
  FROM cms_topic t
 WHERE t.name = '生活不掉线' AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_member_template m
        WHERE m.member_id = 100000 AND m.title = 'G1｜现在出状况,你先选什么' AND m.del_flag = 0);

INSERT INTO cms_member_template
       (member_id, title, description, players, usage_location, required_materials,
        duration, difficulty, is_sync, rule_instructions, validation_method,
        status, publish_status, draft_status, check_status, del_flag, create_by, create_time)
SELECT 100000, 'G2｜我的底线三选一',
       '从「更近、更快、更省、更专业、可取送/上门」中保留三个,再选一个最不能妥协的,形成本角色 PRIORITY。',
       '1人', '本地生活圈(线上完成,不要求到店)', '无需任何物料',
       2, '简单', 1,
       '玩家动作:五留三→三选一。商家资产:真实价格带、服务时长、距离和方式(商家只确认本店确实能提供哪些条件)。员工动作:零。',
       0, 1, 1, 1, 1, 0, 'seed_life_backup_games_20260820', NOW()
  FROM cms_topic t
 WHERE t.name = '生活不掉线' AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_member_template m
        WHERE m.member_id = 100000 AND m.title = 'G2｜我的底线三选一' AND m.del_flag = 0);

INSERT INTO cms_member_template
       (member_id, title, description, players, usage_location, required_materials,
        duration, difficulty, is_sync, rule_instructions, validation_method,
        status, publish_status, draft_status, check_status, del_flag, create_by, create_time)
SELECT 100000, 'G3｜营业时间拼图',
       '把自己的可用时间放进商家真实营业与接单时段,看哪些选择真正可用,形成 TIME_WINDOW 与可用性结论。',
       '1人', '本地生活圈(线上完成,不要求到店)', '无需任何物料',
       3, '简单', 1,
       '玩家动作:摆时间窗→看重叠。商家资产:营业时间、截单时间、休息日、预约要求(商家只确认营业和服务时段)。员工动作:零。营业时间不等于即时库存/接单能力,出发前确认。',
       0, 1, 1, 1, 1, 0, 'seed_life_backup_games_20260820', NOW()
  FROM cms_topic t
 WHERE t.name = '生活不掉线' AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_member_template m
        WHERE m.member_id = 100000 AND m.title = 'G3｜营业时间拼图' AND m.del_flag = 0);

INSERT INTO cms_member_template
       (member_id, title, description, players, usage_location, required_materials,
        duration, difficulty, is_sync, rule_instructions, validation_method,
        status, publish_status, draft_status, check_status, del_flag, create_by, create_time)
SELECT 100000, 'G4｜去之前准备什么',
       '从平台卡片中选出使用该服务前真正需要准备的三项(如衣物材质标签、尺寸信息、车辆型号、服务地址范围),生成私人准备清单。',
       '1人', '本地生活圈(线上完成,不要求到店)', '无需任何物料',
       3, '简单', 1,
       '玩家动作:卡片中选三项。商家资产:服务前提、所需物品、不可服务范围(商家确认平台行业模板,不写教程)。员工动作:零。不要求上传敏感照片。',
       0, 1, 1, 1, 1, 0, 'seed_life_backup_games_20260820', NOW()
  FROM cms_topic t
 WHERE t.name = '生活不掉线' AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_member_template m
        WHERE m.member_id = 100000 AND m.title = 'G4｜去之前准备什么' AND m.del_flag = 0);

INSERT INTO cms_member_template
       (member_id, title, description, players, usage_location, required_materials,
        duration, difficulty, is_sync, rule_instructions, validation_method,
        status, publish_status, draft_status, check_status, del_flag, create_by, create_time)
SELECT 100000, 'G5｜值不值得现在处理',
       '根据商家公开的起步价、时间、服务范围和替代方案,选择「现在处理、先咨询、先保存」。结果只表示玩家当前意图。',
       '1人', '本地生活圈(线上完成,不要求到店)', '无需任何物料',
       2, '简单', 1,
       '玩家动作:三选一表达意图。商家资产:透明价格、处理时长、包含/不含项(商家只确认价格与边界)。员工动作:零。不构成专业诊断或报价。',
       0, 1, 1, 1, 1, 0, 'seed_life_backup_games_20260820', NOW()
  FROM cms_topic t
 WHERE t.name = '生活不掉线' AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_member_template m
        WHERE m.member_id = 100000 AND m.title = 'G5｜值不值得现在处理' AND m.del_flag = 0);

INSERT INTO cms_member_template
       (member_id, title, description, players, usage_location, required_materials,
        duration, difficulty, is_sync, rule_instructions, validation_method,
        status, publish_status, draft_status, check_status, del_flag, create_by, create_time)
SELECT 100000, 'G6｜首选失效怎么办',
       '首局终局演练:系统模拟首选暂停、闭店或不可达,玩家要在30秒内利用营业、路线、服务边界和准备条件找到可执行替代。成功才生成「本角色已备份」,同时保存 PRIMARY 与 BACKUP。',
       '1人', '本地生活圈(线上完成,不要求到店)', '无需任何物料',
       1, '简单', 1,
       '玩家动作:30秒内切到备选。商家资产:状态、营业、服务范围和替代条件。商家配置:无。员工动作:零。失败时明确告诉用户缺的不是积分,而是哪一项真实条件。',
       0, 1, 1, 1, 1, 0, 'seed_life_backup_games_20260820', NOW()
  FROM cms_topic t
 WHERE t.name = '生活不掉线' AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_member_template m
        WHERE m.member_id = 100000 AND m.title = 'G6｜首选失效怎么办' AND m.del_flag = 0);


-- ======================= 二、小程序公共模板库(cms_template_library) =======================
-- 文案从 cms_member_template 同源复制;category_id=18 / activity_categoryids='18'
-- (世界一刻 tab,现有分类中最贴近的泛生活分类;后台可随时重新归类)。
-- status=1 publish_status=1 即在 /api/template/list 可见。

INSERT INTO cms_template_library
       (member_id, category_id, title, description, players, duration, difficulty,
        rule_instructions, validation_method, activity_categoryids,
        status, publish_status, del_flag, create_by, create_time)
SELECT 100000, 18, m.title, m.description, m.players, m.duration, m.difficulty,
       m.rule_instructions, 0, '18', 1, 1, 0, 'seed_life_backup_games_20260820', NOW()
  FROM cms_member_template m
 WHERE m.member_id = 100000 AND m.title = 'G0｜30秒生活掉线测试' AND m.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_template_library l
        WHERE l.member_id = 100000 AND l.title = 'G0｜30秒生活掉线测试' AND l.del_flag = 0);

INSERT INTO cms_template_library
       (member_id, category_id, title, description, players, duration, difficulty,
        rule_instructions, validation_method, activity_categoryids,
        status, publish_status, del_flag, create_by, create_time)
SELECT 100000, 18, m.title, m.description, m.players, m.duration, m.difficulty,
       m.rule_instructions, 0, '18', 1, 1, 0, 'seed_life_backup_games_20260820', NOW()
  FROM cms_member_template m
 WHERE m.member_id = 100000 AND m.title = 'G1｜现在出状况,你先选什么' AND m.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_template_library l
        WHERE l.member_id = 100000 AND l.title = 'G1｜现在出状况,你先选什么' AND l.del_flag = 0);

INSERT INTO cms_template_library
       (member_id, category_id, title, description, players, duration, difficulty,
        rule_instructions, validation_method, activity_categoryids,
        status, publish_status, del_flag, create_by, create_time)
SELECT 100000, 18, m.title, m.description, m.players, m.duration, m.difficulty,
       m.rule_instructions, 0, '18', 1, 1, 0, 'seed_life_backup_games_20260820', NOW()
  FROM cms_member_template m
 WHERE m.member_id = 100000 AND m.title = 'G2｜我的底线三选一' AND m.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_template_library l
        WHERE l.member_id = 100000 AND l.title = 'G2｜我的底线三选一' AND l.del_flag = 0);

INSERT INTO cms_template_library
       (member_id, category_id, title, description, players, duration, difficulty,
        rule_instructions, validation_method, activity_categoryids,
        status, publish_status, del_flag, create_by, create_time)
SELECT 100000, 18, m.title, m.description, m.players, m.duration, m.difficulty,
       m.rule_instructions, 0, '18', 1, 1, 0, 'seed_life_backup_games_20260820', NOW()
  FROM cms_member_template m
 WHERE m.member_id = 100000 AND m.title = 'G3｜营业时间拼图' AND m.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_template_library l
        WHERE l.member_id = 100000 AND l.title = 'G3｜营业时间拼图' AND l.del_flag = 0);

INSERT INTO cms_template_library
       (member_id, category_id, title, description, players, duration, difficulty,
        rule_instructions, validation_method, activity_categoryids,
        status, publish_status, del_flag, create_by, create_time)
SELECT 100000, 18, m.title, m.description, m.players, m.duration, m.difficulty,
       m.rule_instructions, 0, '18', 1, 1, 0, 'seed_life_backup_games_20260820', NOW()
  FROM cms_member_template m
 WHERE m.member_id = 100000 AND m.title = 'G4｜去之前准备什么' AND m.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_template_library l
        WHERE l.member_id = 100000 AND l.title = 'G4｜去之前准备什么' AND l.del_flag = 0);

INSERT INTO cms_template_library
       (member_id, category_id, title, description, players, duration, difficulty,
        rule_instructions, validation_method, activity_categoryids,
        status, publish_status, del_flag, create_by, create_time)
SELECT 100000, 18, m.title, m.description, m.players, m.duration, m.difficulty,
       m.rule_instructions, 0, '18', 1, 1, 0, 'seed_life_backup_games_20260820', NOW()
  FROM cms_member_template m
 WHERE m.member_id = 100000 AND m.title = 'G5｜值不值得现在处理' AND m.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_template_library l
        WHERE l.member_id = 100000 AND l.title = 'G5｜值不值得现在处理' AND l.del_flag = 0);

INSERT INTO cms_template_library
       (member_id, category_id, title, description, players, duration, difficulty,
        rule_instructions, validation_method, activity_categoryids,
        status, publish_status, del_flag, create_by, create_time)
SELECT 100000, 18, m.title, m.description, m.players, m.duration, m.difficulty,
       m.rule_instructions, 0, '18', 1, 1, 0, 'seed_life_backup_games_20260820', NOW()
  FROM cms_member_template m
 WHERE m.member_id = 100000 AND m.title = 'G6｜首选失效怎么办' AND m.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_template_library l
        WHERE l.member_id = 100000 AND l.title = 'G6｜首选失效怎么办' AND l.del_flag = 0);


-- ======================= 三、章节节点接线(cms_topic_node) =======================
-- §6 角色与游戏匹配:EAT 主=G3 / WEAR 主=G4 / HOME 主=G1 / MOVE 主=G1;G6 统一终局;
-- G0 平台入口挂第一章 sort 0;可选插件 G5→衣物、G2→出行(§6 各角色可选列均含)。
-- 无坐标(路线剪影自动跳过)、无打卡码、无营业时间 —— 线上完成,不要求到店。

INSERT INTO cms_topic_node
       (topic_id, chapter_id, name, description, node_time, template_id,
        hook_text, status, sort_id, create_by, create_time)
SELECT ch.topic_id, ch.id, '入口|30秒生活掉线测试',
       '选定此刻最不想发生的情境与生活锚点、可达时间、首要考虑。', 1,
       (SELECT m.id FROM cms_member_template m
         WHERE m.member_id = 100000 AND m.title = 'G0｜30秒生活掉线测试' AND m.del_flag = 0
         ORDER BY m.id LIMIT 1),
       '先做一次30秒压力测试,看你哪一项最容易掉线。', 1, 0,
       'seed_life_backup_games_20260820', NOW()
  FROM cms_topic_chapter ch
  JOIN cms_topic t ON t.id = ch.topic_id
 WHERE t.name = '生活不掉线' AND t.del_flag = 0 AND ch.name = '吃饭不掉线'
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n
        WHERE n.chapter_id = ch.id AND n.name = '入口|30秒生活掉线测试');

INSERT INTO cms_topic_node
       (topic_id, chapter_id, name, description, node_time, template_id,
        hook_text, status, sort_id, create_by, create_time)
SELECT ch.topic_id, ch.id, '主游戏|营业时间拼图',
       '把午休/晚间可用时间放进候选商家真实营业与截单时段,找出真正可用的一餐。', 3,
       (SELECT m.id FROM cms_member_template m
         WHERE m.member_id = 100000 AND m.title = 'G3｜营业时间拼图' AND m.del_flag = 0
         ORDER BY m.id LIMIT 1),
       '常去的那家关门时,你的时间窗里还有谁开着?', 1, 1,
       'seed_life_backup_games_20260820', NOW()
  FROM cms_topic_chapter ch
  JOIN cms_topic t ON t.id = ch.topic_id
 WHERE t.name = '生活不掉线' AND t.del_flag = 0 AND ch.name = '吃饭不掉线'
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n
        WHERE n.chapter_id = ch.id AND n.name = '主游戏|营业时间拼图');

INSERT INTO cms_topic_node
       (topic_id, chapter_id, name, description, node_time, template_id,
        hook_text, status, sort_id, create_by, create_time)
SELECT ch.topic_id, ch.id, '终局|首选失效演练',
       '模拟首选暂停/闭店/不可达,30秒内切到备选,成功即获「本角色已备份」。', 1,
       (SELECT m.id FROM cms_member_template m
         WHERE m.member_id = 100000 AND m.title = 'G6｜首选失效怎么办' AND m.del_flag = 0
         ORDER BY m.id LIMIT 1),
       '首选突然熄灯,30秒内把这一餐接回来。', 1, 2,
       'seed_life_backup_games_20260820', NOW()
  FROM cms_topic_chapter ch
  JOIN cms_topic t ON t.id = ch.topic_id
 WHERE t.name = '生活不掉线' AND t.del_flag = 0 AND ch.name = '吃饭不掉线'
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n
        WHERE n.chapter_id = ch.id AND n.name = '终局|首选失效演练');

INSERT INTO cms_topic_node
       (topic_id, chapter_id, name, description, node_time, template_id,
        hook_text, status, sort_id, create_by, create_time)
SELECT ch.topic_id, ch.id, '主游戏|去之前准备什么',
       '选出送洗/修补前真正需要准备的三项(材质标签、尺寸、取送条件),生成私人准备清单。', 3,
       (SELECT m.id FROM cms_member_template m
         WHERE m.member_id = 100000 AND m.title = 'G4｜去之前准备什么' AND m.del_flag = 0
         ORDER BY m.id LIMIT 1),
       '别白跑一趟:去之前该带什么、问什么?', 1, 0,
       'seed_life_backup_games_20260820', NOW()
  FROM cms_topic_chapter ch
  JOIN cms_topic t ON t.id = ch.topic_id
 WHERE t.name = '生活不掉线' AND t.del_flag = 0 AND ch.name = '衣物不掉线'
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n
        WHERE n.chapter_id = ch.id AND n.name = '主游戏|去之前准备什么');

INSERT INTO cms_topic_node
       (topic_id, chapter_id, name, description, node_time, template_id,
        hook_text, status, sort_id, create_by, create_time)
SELECT ch.topic_id, ch.id, '可选|值不值得现在处理',
       '按起步价、时长、服务边界选择「现在处理、先咨询、先保存」。', 2,
       (SELECT m.id FROM cms_member_template m
         WHERE m.member_id = 100000 AND m.title = 'G5｜值不值得现在处理' AND m.del_flag = 0
         ORDER BY m.id LIMIT 1),
       '这双鞋值得现在修吗?先看价格和边界再决定。', 1, 1,
       'seed_life_backup_games_20260820', NOW()
  FROM cms_topic_chapter ch
  JOIN cms_topic t ON t.id = ch.topic_id
 WHERE t.name = '生活不掉线' AND t.del_flag = 0 AND ch.name = '衣物不掉线'
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n
        WHERE n.chapter_id = ch.id AND n.name = '可选|值不值得现在处理');

INSERT INTO cms_topic_node
       (topic_id, chapter_id, name, description, node_time, template_id,
        hook_text, status, sort_id, create_by, create_time)
SELECT ch.topic_id, ch.id, '终局|首选失效演练',
       '模拟首选暂停/闭店/不可达,30秒内切到备选,成功即获「本角色已备份」。', 1,
       (SELECT m.id FROM cms_member_template m
         WHERE m.member_id = 100000 AND m.title = 'G6｜首选失效怎么办' AND m.del_flag = 0
         ORDER BY m.id LIMIT 1),
       '常去的洗衣店歇业了,备选还接单吗?', 1, 2,
       'seed_life_backup_games_20260820', NOW()
  FROM cms_topic_chapter ch
  JOIN cms_topic t ON t.id = ch.topic_id
 WHERE t.name = '生活不掉线' AND t.del_flag = 0 AND ch.name = '衣物不掉线'
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n
        WHERE n.chapter_id = ch.id AND n.name = '终局|首选失效演练');

INSERT INTO cms_topic_node
       (topic_id, chapter_id, name, description, node_time, template_id,
        hook_text, status, sort_id, create_by, create_time)
SELECT ch.topic_id, ch.id, '主游戏|现在出状况你先选什么',
       '周末家里需要一次小型清洁/小故障,从三种处理方式中选择,形成速度、价格或上门方式优先级。', 3,
       (SELECT m.id FROM cms_member_template m
         WHERE m.member_id = 100000 AND m.title = 'G1｜现在出状况,你先选什么' AND m.del_flag = 0
         ORDER BY m.id LIMIT 1),
       '钥匙、水电、清洁——出状况时你先找谁?', 1, 0,
       'seed_life_backup_games_20260820', NOW()
  FROM cms_topic_chapter ch
  JOIN cms_topic t ON t.id = ch.topic_id
 WHERE t.name = '生活不掉线' AND t.del_flag = 0 AND ch.name = '家里不掉线'
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n
        WHERE n.chapter_id = ch.id AND n.name = '主游戏|现在出状况你先选什么');

INSERT INTO cms_topic_node
       (topic_id, chapter_id, name, description, node_time, template_id,
        hook_text, status, sort_id, create_by, create_time)
SELECT ch.topic_id, ch.id, '终局|首选失效演练',
       '模拟首选暂停/闭店/不可达,30秒内切到备选,成功即获「本角色已备份」。', 1,
       (SELECT m.id FROM cms_member_template m
         WHERE m.member_id = 100000 AND m.title = 'G6｜首选失效怎么办' AND m.del_flag = 0
         ORDER BY m.id LIMIT 1),
       '会修的那家不接单了,这个区域还有谁覆盖你家?', 1, 1,
       'seed_life_backup_games_20260820', NOW()
  FROM cms_topic_chapter ch
  JOIN cms_topic t ON t.id = ch.topic_id
 WHERE t.name = '生活不掉线' AND t.del_flag = 0 AND ch.name = '家里不掉线'
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n
        WHERE n.chapter_id = ch.id AND n.name = '终局|首选失效演练');

INSERT INTO cms_topic_node
       (topic_id, chapter_id, name, description, node_time, template_id,
        hook_text, status, sort_id, create_by, create_time)
SELECT ch.topic_id, ch.id, '主游戏|现在出状况你先选什么',
       '通勤工具在早上失灵,从三种处理方式(含公共交通与步行方案)中选择。', 3,
       (SELECT m.id FROM cms_member_template m
         WHERE m.member_id = 100000 AND m.title = 'G1｜现在出状况,你先选什么' AND m.del_flag = 0
         ORDER BY m.id LIMIT 1),
       '车胎早上瘪了,你的备用路线是什么?', 1, 0,
       'seed_life_backup_games_20260820', NOW()
  FROM cms_topic_chapter ch
  JOIN cms_topic t ON t.id = ch.topic_id
 WHERE t.name = '生活不掉线' AND t.del_flag = 0 AND ch.name = '出行不掉线'
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n
        WHERE n.chapter_id = ch.id AND n.name = '主游戏|现在出状况你先选什么');

INSERT INTO cms_topic_node
       (topic_id, chapter_id, name, description, node_time, template_id,
        hook_text, status, sort_id, create_by, create_time)
SELECT ch.topic_id, ch.id, '可选|我的底线三选一',
       '从更近、更快、更省、更专业、可取送中保留三个,再选一个最不能妥协的,形成 MOVE 优先级。', 2,
       (SELECT m.id FROM cms_member_template m
         WHERE m.member_id = 100000 AND m.title = 'G2｜我的底线三选一' AND m.del_flag = 0
         ORDER BY m.id LIMIT 1),
       '备用出行方案里,你最不能妥协的是什么?', 1, 1,
       'seed_life_backup_games_20260820', NOW()
  FROM cms_topic_chapter ch
  JOIN cms_topic t ON t.id = ch.topic_id
 WHERE t.name = '生活不掉线' AND t.del_flag = 0 AND ch.name = '出行不掉线'
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n
        WHERE n.chapter_id = ch.id AND n.name = '可选|我的底线三选一');

INSERT INTO cms_topic_node
       (topic_id, chapter_id, name, description, node_time, template_id,
        hook_text, status, sort_id, create_by, create_time)
SELECT ch.topic_id, ch.id, '终局|首选失效演练',
       '模拟首选暂停/闭店/不可达,30秒内切到备选;公共方案不因未付费被隐藏。', 1,
       (SELECT m.id FROM cms_member_template m
         WHERE m.member_id = 100000 AND m.title = 'G6｜首选失效怎么办' AND m.del_flag = 0
         ORDER BY m.id LIMIT 1),
       '地铁停运的那个早上,你还有第二条路吗?', 1, 2,
       'seed_life_backup_games_20260820', NOW()
  FROM cms_topic_chapter ch
  JOIN cms_topic t ON t.id = ch.topic_id
 WHERE t.name = '生活不掉线' AND t.del_flag = 0 AND ch.name = '出行不掉线'
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n
        WHERE n.chapter_id = ch.id AND n.name = '终局|首选失效演练');


-- ======================= 四、主骨架补指 G0 =======================
UPDATE cms_topic_template_meta m
  JOIN cms_topic t ON t.id = m.topic_id
   SET m.main_skeleton_template_id = (
       SELECT tpl.id FROM cms_member_template tpl
        WHERE tpl.member_id = 100000 AND tpl.title = 'G0｜30秒生活掉线测试' AND tpl.del_flag = 0
        ORDER BY tpl.id LIMIT 1)
 WHERE t.name = '生活不掉线' AND t.del_flag = 0
   AND m.main_skeleton_template_id IS NULL;


-- ======================= 落库后回读验证(「回执 ≠ 观测」) =======================
-- 1) 幂等证据 —— 重复执行后四个数字都不能变:
--   SELECT (SELECT COUNT(*) FROM cms_member_template WHERE member_id=100000
--            AND create_by='seed_life_backup_games_20260820' AND del_flag=0)        AS games,
--          (SELECT COUNT(*) FROM cms_template_library WHERE member_id=100000
--            AND create_by='seed_life_backup_games_20260820' AND del_flag=0)        AS lib_games,
--          (SELECT COUNT(*) FROM cms_topic_node n JOIN cms_topic t ON t.id=n.topic_id
--            WHERE t.name='生活不掉线')                                              AS nodes,
--          (SELECT COUNT(*) FROM cms_topic_node n JOIN cms_topic t ON t.id=n.topic_id
--            WHERE t.name='生活不掉线' AND (n.template_id IS NULL OR n.template_id=0)) AS dangling;
--   -- 期望:7 / 7 / 11 / 0。dangling>0 = 节点建了但模板没接上,立即排查。
-- 2) 每章节点齐(3/3/2/3)且 G6 四章都有:
--   SELECT ch.name, COUNT(*) FROM cms_topic_node n
--     JOIN cms_topic_chapter ch ON ch.id=n.chapter_id
--     JOIN cms_topic t ON t.id=n.topic_id
--    WHERE t.name='生活不掉线' GROUP BY ch.name ORDER BY MIN(ch.sort_id);
-- 3) 主骨架已指 G0:
--   SELECT m.main_skeleton_template_id, tpl.title
--     FROM cms_topic_template_meta m
--     JOIN cms_topic t ON t.id=m.topic_id
--     LEFT JOIN cms_member_template tpl ON tpl.id=m.main_skeleton_template_id
--    WHERE t.name='生活不掉线';                                  -- 期望:非 NULL,title=G0
-- 4) 端到端(唯一算数):
--   POST /api/template/topic-template/info(id=990012)⇒ games[] 含 7 个玩法,不再为空;
--   小程序模板库(/api/template/list, category_id=18)⇒ 出现 G0—G6 七张卡。


-- ======================= 回滚 =======================
-- DELETE FROM cms_topic_node WHERE create_by='seed_life_backup_games_20260820';
-- UPDATE cms_topic_template_meta m JOIN cms_topic t ON t.id=m.topic_id
--    SET m.main_skeleton_template_id=NULL WHERE t.name='生活不掉线';
-- DELETE FROM cms_template_library WHERE create_by='seed_life_backup_games_20260820';
-- DELETE FROM cms_member_template WHERE create_by='seed_life_backup_games_20260820';
