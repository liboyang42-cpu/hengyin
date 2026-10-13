-- ============================================================================
-- 《预制人生 · 四站试玩版》种子:1 主题 / 4 章 / 5 节点
--
-- 目标(2026-09-18 用户裁决):把《预制人生》二创分支剧本落成一个**用户本人
-- 能立刻自己开走试玩、但不对公众可见**的主题。剧本真源:
--   ~/Documents/城瘾/文档整理_20260711/04-未开始/城瘾_预制人生二创分支剧本_20260917.md
-- 四站 = 上海城市规划展示馆(序章/出生/学习)、人民广场站(工作/系统)、
--        第一百货(命数/升级)、南京东路(共处/终章);第 2 站 2 个节点,
--        才排得出玩法拆分文档 §4 的「玩 → 读 → 玩 → 梦」。
--
-- ★ 为什么主题名不是「预制人生」:
--   2026-08-22 的 migration_seed_shanghai_solo_topics_20260822.sql 已经在
--   cms_topic(member_id=100000, name='预制人生') 建过一条**主题模板**记录
--   (product_type=2 / is_template=1 / user_status=0 / 4 节点 / EXPERIMENTAL meta)。
--   本文件的幂等键与全仓种子一致是 (member_id, name),沿用「预制人生」会命中旧行、
--   不新建,还会把新章节/新节点挂到那条模板主题上。故本轮用独立名字,旧记录一字不动。
--
-- ★ 玩法一次写全(施工契约:城瘾_预制人生5kit+3编辑能力_施工契约_20260917.md):
--   5 个节点模板的 advanced_config_json 合起来一次写全 11 段 —— 4 段新玩法
--   (profile / photoCheck / note / typeIn)+ 7 项今天就能玩的旧玩法
--   (steps / qa.mode=SHOT / qa.mode=PICK / estimate / quietHold / branch / check)。
--   分布:①PL01 profile+qa(SHOT)+estimate ②PL02 steps+qa(PICK) ③PL03 check+typeIn
--        ④PL04 photoCheck+note+quietHold ⑤PL05 branch。
--   今天生产跑的是老 jar:老 validator 不认这 4 段但**不拒绝未知顶层键**,老
--   hasEnabledMechanic 只认旧 7 项 ⇒ 四段被静默忽略、七项当场可玩;后端部署后
--   同一份数据自动全亮,不需要再配第二遍。
--   ⚠️ 每段字段名严格照契约,一个字都不能改。
--
-- ★ 故事变量(契约 §3.1):正文里写 {name|兜底} / {job|兜底}。
--   老后端不下发 vars,前端会把花括号原样留下 —— 所以每个兜底词都写成完整句子成分,
--   今天读起来也是通顺的,全文不出现光秃秃的 {name}。
--
-- ★ 发布边界(自己把闸走通,字段值 = 能过闸的理由):
--   ① 免费自玩可领(今天就能开走):
--      CmsRegistrationServiceImpl#createSelfPlayPassInternal → CommercialTopicPolicy#isFreeTopic
--      要求 lifecycle=DRAFT(0)、del_flag≠2、且 evaluate() 无任何商业事实;
--      → assertFreeTopicOpen/assertFreeTopicOnShelf 要求 status=1 && user_status=1 && 未截止,
--      并要求 self_play=1 且 self_play_price 恰为 0(>0 会被「免费判定与计价不一致」拒绝)。
--      商业事实在本文件里逐条清零:product_type=1(非 FREE_EXPLORE)、无 oms_ticket、
--      recruiter_enabled=0、无 merchant_chapter_offer、self_play_price=0。
--      0 元单由 RegistrationCheckoutServiceImpl#finishOrder → completeZeroAmountPayment
--      当场置为已支付,自玩通行证立即可用。
--   ② 不对公众可见:
--      · 玩家侧所有列表(selectPublicCmsTopicList / selectNearbyTopics /
--        selectNearbyPublicTeams)都写死 `is_template = 0` ⇒ is_template=1 从首页推荐、
--        底部主题页签、搜索、附近主题全部消失;
--      · 公共模板货架 selectTopicTemplateList 要求 cms_topic_template_meta
--        .template_status='VERIFIED';本文件刻意**不写 meta** ⇒ 模板货架也不出;
--      · is_recommend=0,不进首页推荐位;club_id IS NULL,不落任何俱乐部可见范围。
--      · 用户本人仍能开走:selectCmsTopicDetailById 对 status=1 && user_status=1 的
--        主题不做 is_template 过滤(直链 pages/topic/index/index?id=<id> 可进详情),
--        自玩下单与 /api/play/nodes 也不读 is_template。
--   ③ R14 检定要在今天就能玩:
--      TopicRouteRuntimeServiceImpl#requireScope 对 product_type=1 的主题要求
--      published_config_version>0(否则 /api/play/nodes 直接抛「路线尚未发布」),
--      App 内发布是通过 publishRouteSnapshot 递增该值。本文件按发布后的事实写
--      route_mode='LINEAR' + config_version=1 + published_config_version=1
--      (LINEAR 主题 published_route_graph_json 保持 NULL)。
--
-- 幂等:INSERT ... WHERE NOT EXISTS 建行 + UPDATE 等值覆盖(主题 pin (member_id,name)、
--       章节 pin (topic_id,sort_id)、节点 pin (topic_id,name)、模板 pin (member_id,title));
--       重跑不新增、不漂移。纯 DML,不建表、不改表结构。
-- runner 不声明 requires=transaction:本轮以「逐句幂等 + 文件末尾 scalar-true 回读断言」
--       替代事务(与 migration_publish_solo_story_topics_20260827.sql 同路);--transaction
--       模式不接受可执行 SELECT,声明了它反而会让末尾回读断言无法执行。
-- 依赖声明见 ci/migration-dependencies.json: seed_prefab_life_20260918。
-- ============================================================================

-- ======================= 一、主题(在架但玩家侧不可见) =======================

INSERT INTO cms_topic
       (member_id, topic_type, product_type, name, subtitle, description,
        address_name, total_time, total_mileage, location_count, template_count, img_url,
        is_template, status, user_status, merchant_status, self_play, self_play_price,
        self_play_quota, finish_medal_name, lifecycle, del_flag, is_recommend,
        route_mode, config_version, published_config_version, beta_flag,
        club_id, club_member_only, is_draft, create_by, create_time)
SELECT 100000, 1, 1, '预制人生 · 四站试玩版', '从人民广场出发,走完一个被预制的人',
       '你在城市模型里出生,在人民广场站学会说「收到」,在第一百货看见被高楼切开的天,最后走到南京东路尽头那道看不见的边界。4 站 5 个 GPS 点位,只走公共空间:不入店、不消费、不要求任何商家或工作人员参与。本主题是内部试玩版,不进公开列表;持 0 元自玩通行证即可开走。梦与照片留在你自己的记录里。',
       '上海市黄浦区人民广场片区', 7200, 2.00, 5, 5,
       'https://www.chengyinhub.com/prod-api/profile/template-covers/t27-shanghai-prebuilt-life.webp',
       1, 1, 1, 0, 1, 0.00,
       NULL, '预制人生 · 第一次偏离', 0, 0, 0,
       'LINEAR', 1, 1, 0,
       NULL, 0, 0, 'seed_prefab_life_20260918', NOW()
  FROM sys_category anchor
 WHERE anchor.id=3
   AND NOT EXISTS (SELECT 1 FROM cms_topic t
                    WHERE t.member_id=100000 AND t.name='预制人生 · 四站试玩版' AND t.del_flag=0);

-- 重跑等值覆盖:钉住 member_id + name,不改任何其它主题。
UPDATE cms_topic
   SET subtitle='从人民广场出发,走完一个被预制的人',
       description='你在城市模型里出生,在人民广场站学会说「收到」,在第一百货看见被高楼切开的天,最后走到南京东路尽头那道看不见的边界。4 站 5 个 GPS 点位,只走公共空间:不入店、不消费、不要求任何商家或工作人员参与。本主题是内部试玩版,不进公开列表;持 0 元自玩通行证即可开走。梦与照片留在你自己的记录里。',
       address_name='上海市黄浦区人民广场片区',
       total_time=7200, total_mileage=2.00, location_count=5, template_count=5,
       img_url='https://www.chengyinhub.com/prod-api/profile/template-covers/t27-shanghai-prebuilt-life.webp',
       topic_type=1, product_type=1,
       is_template=1, status=1, user_status=1, merchant_status=0,
       self_play=1, self_play_price=0.00, self_play_quota=NULL,
       finish_medal_name='预制人生 · 第一次偏离',
       lifecycle=0, is_recommend=0, beta_flag=0,
       route_mode='LINEAR', config_version=1, published_config_version=1,
       club_id=NULL, club_member_only=0, is_draft=0,
       update_by='seed_prefab_life_20260918', update_time=NOW()
 WHERE member_id=100000 AND name='预制人生 · 四站试玩版' AND del_flag=0;

-- 内容槽归平台:与官方单人剧情种子同口径,不招商、不绑定商户。
INSERT INTO product_role (topic_id, slot, entity_type, entity_id, status)
SELECT t.id, 'CONTENT', 'PLATFORM', NULL, 1
  FROM cms_topic t
 WHERE t.member_id=100000 AND t.del_flag=0 AND t.name='预制人生 · 四站试玩版'
   AND NOT EXISTS (SELECT 1 FROM product_role r
                    WHERE r.topic_id=t.id AND r.slot='CONTENT' AND r.status=1);

-- ======================= 二、4 章 =======================

INSERT INTO cms_topic_chapter
       (topic_id, name, description, sort_id, location_count, template_count,
        required, recruit_enabled, max_merchant, terms_mode, create_by, create_time)
SELECT t.id, seed.chapter_name, seed.chapter_desc, seed.sort_id,
       seed.node_count, seed.node_count, 1, 0, 0, 'TRAFFIC',
       'seed_prefab_life_20260918', NOW()
  FROM cms_topic t
  JOIN (
       SELECT 1 sort_id, '第一章 · 出生' chapter_name, 1 node_count,
              '你在城市模型里醒来。先登记身份,再上第一课:什么叫标准答案。' chapter_desc
       UNION ALL SELECT 2, '第二章 · 工作', 2,
              '人民广场站的换乘通道和会议室,是同一套系统的两个切面。'
       UNION ALL SELECT 3, '第三章 · 命数', 1,
              '第一百货的楼梯间里,小周问你:那又怎么样呢?'
       UNION ALL SELECT 4, '第四章 · 边界', 1,
              '南京东路尽头有一道看不见的墙,上面写着一路被你改掉的话。'
  ) seed
 WHERE t.member_id=100000 AND t.del_flag=0 AND t.name='预制人生 · 四站试玩版'
   AND NOT EXISTS (SELECT 1 FROM cms_topic_chapter c
                    WHERE c.topic_id=t.id AND c.sort_id=seed.sort_id);

-- ======================= 三、5 个节点玩法模板(全套 11 段一次写全) =======================

INSERT INTO cms_member_template
       (member_id, title, description, players, usage_location, required_materials,
        duration, difficulty, is_sync, rule_instructions, validation_method,
        feedback_text, story_text, advanced_config_json, xp_value,
        status, publish_status, draft_status, check_status, del_flag, create_by, create_time)
SELECT 100000, seed.title, seed.description, '1人', seed.usage_location, '手机、定位权限',
       seed.duration, seed.difficulty, 1, seed.rule_instructions, 5,
       seed.feedback_text, seed.story_text, CAST(seed.config_json AS JSON), 20,
       1, 1, 1, 1, 0, 'seed_prefab_life_20260918', NOW()
  FROM (
       SELECT 'PL01-出生登记:写下你叫什么' title,
              '先登记身份,再拍下带「新」字的招牌,最后数一数低头看手机的人。' description,
              '上海城市规划展示馆外部公共区域' usage_location, 20 duration, '入门' difficulty,
              '在允许通行的公共位置完成:①填出生登记;②拍一张带「新」字的招牌;③估一分钟里低头看手机的人数。不进入非开放区域,不为拍照妨碍他人。' rule_instructions,
              '身份已登记。系统给了你一个名字,和一条标准路线。' feedback_text,
              '你在一间摆满城市模型的房间里醒来。系统先让你登记:你叫什么,你做什么,你信不信标准答案。老师敲了敲讲台:「{name|那个人},来,站起来,你来回答一下?」你看向窗外。叶子落了。' story_text,
              '{"schemaVersion":1,"profile":{"enabled":true,"title":"出生登记","lead":"填完这张表,你就有了身份。","avatar":{"enabled":true,"required":false},"questions":[{"key":"name","label":"你叫什么","kind":"text","maxLength":12,"required":true},{"key":"job","label":"你做什么工作","kind":"pick","options":[{"key":"clerk","label":"店员","effects":[{"var":"counter.energy","op":"INC","value":1}]},{"key":"coder","label":"写代码的人"},{"key":"student","label":"还在读书"},{"key":"unsure","label":"说不好","effects":[{"var":"counter.instability","op":"INC","value":1}]}]},{"key":"standard","label":"你相信标准答案吗","kind":"pick","options":[{"key":"yes","label":"信"},{"key":"no","label":"不信"},{"key":"depends","label":"看题目"}]}],"xp":10},"qa":{"enabled":true,"mode":"SHOT","title":"拍下带「新」字的招牌","lead":"沿路找一块带「新」字的招牌,拍下来。拍照没有对错,交上来就算做到。","maxTries":0,"xp":10},"estimate":{"enabled":true,"title":"一分钟里,大约有多少人低头看手机?","question":"一分钟里,大约有多少人低头看手机?","unit":"人","min":0,"max":200,"answer":60,"tolerance":60,"reveal":"系统补了一句:你猜的偏差,就是你和这条街的距离。","xp":10}}' config_json
       UNION ALL SELECT 'PL02-换乘通道:三题与六百步',
              '沿换乘通道走一段,听完广播后回答三个指令词里哪一个最像系统。',
              '人民广场地铁站地面出口及换乘通道公共区域', 15, '入门',
              '只作正常通行,不为任务停留、逆行或拍摄他人。①沿指定方向走约 420 米;②站 30 秒听完广播,再选一个最像系统指令的词。',
              '通道尽头是另一条通道。系统不解释,只重复。',
              '换乘通道里每个人都走得很快,快到你分不清谁在上班、谁在回家。广播每隔一段就念一次,念的都是同一个词。',
              '{"schemaVersion":1,"steps":{"enabled":true,"eyebrow":"沿换乘通道走约 420 米(约 600 步)","goal":600,"xp":10},"qa":{"enabled":true,"mode":"PICK","title":"广播里最后一个词,最像哪种系统指令?","lead":"在换乘通道站 30 秒,听广播,再回答。","maxTries":0,"xp":10,"options":[{"id":"transfer","label":"换乘","fb":"换乘:所有人都在服从同一个动词。","correct":true},{"id":"exit","label":"出口","fb":"出口:系统允许你离开,但只是这一层。","correct":false},{"id":"queue","label":"排队","fb":"排队:秩序不用人管,它自己会排队。","correct":false}]}}' config_json
       UNION ALL SELECT 'PL03-会议出口:hello world',
              '在写字楼外的公共人行道停下,先过老板那道检定,再限时打出第一行代码。',
              '国际饭店前公共人行道', 15, '普通',
              '只在公共人行道活动,不进入楼宇、不妨碍通行。①过检定:把会议室里没说出口的那句话交给运气;②开表后 12 秒内打出 hello world。',
              '检定结束。不管结果如何,会议都继续。',
              '老板把方案推到一边:「这个方案……先放一放。」你听见自己开口。那一秒里,会议室安静得能听见空调的风。',
              '{"schemaVersion":1,"check":{"enabled":true,"checkId":"prefab_boss_meeting","tier":"medium","skill":"心","successText":"你说出了那句不该说的话,会议室安静了一秒。","failText":"咔嚓一声,你的嘴替你说了「收到」。","successEffects":[{"var":"counter.instability","op":"INC","value":1}],"failEffects":[{"var":"counter.instability","op":"INC","value":1}]},"typeIn":{"enabled":true,"title":"打出这行字","target":"hello world","seconds":12,"caseSensitive":false,"tries":0,"xp":10}}' config_json
       UNION ALL SELECT 'PL04-被切开的天:拍一张留一句',
              '在楼梯间窗边拍一条被高楼切开的天,再写一句留给下一个人。',
              '第一百货商业中心外部公共区域及楼梯间窗边', 15, '普通',
              '商场正常营业,可只逛不买。①拍一张被两侧楼体夹在中间的天空;②写一句 40 字以内的话留给后来的人;③面朝那道天缝站直 5 秒。不必消费。',
              '窗边的天只够一条缝,但你把它留给了下一个人。',
              '楼梯间的窗户开得比别处高。天被两侧的楼切成一条,像这个世界留给你的一条窄缝。',
              '{"schemaVersion":1,"photoCheck":{"enabled":true,"title":"拍一条被高楼切开的天","shotNote":"站在楼梯间窗边,把两侧楼体也拍进去。","requirement":"画面里要有一条被两侧建筑物夹在中间的天空,能看到楼体轮廓","minConfidence":60,"maxTries":3,"fallback":"retake","xp":10},"note":{"enabled":true,"title":"写一句留给下一个人","prompt":"你在窗边看到了什么","maxLength":40,"presets":["天被楼切成了一条","有人在楼下等红灯","玻璃上有我的影子"],"showPrevious":3,"xp":10},"quietHold":{"enabled":true,"kicker":"站直 5 秒","seconds":5,"xp":10}}' config_json
       UNION ALL SELECT 'PL05-边界:终章三选一',
              '南京东路尽头那道看不见的墙前,决定你要哪一种结局。',
              '南京东路步行街西段公共街区', 20, '进阶',
              '只在开放步行街完成;客流管控时服从现场安排,不逆行、不阻塞通道。读完墙上的话,做出最终选择。',
              '你选了一个版本。它不一定是标准答案,但它是你的。',
              '南京东路的尽头立着一道看不见的墙。上面写满一路被你改掉的话:{name|那个人},你现在站得直直的,双手贴在裤缝边。',
              '{"schemaVersion":1,"branch":{"enabled":true,"startStepId":"boundary","steps":[{"id":"boundary","title":"看不见的墙","body":"南京东路的尽头立着一道看不见的墙,上面写满一路被你改掉的话。","terminal":false,"options":[{"id":"stand","label":"站回去,双手贴在裤缝边","nextStepId":"standard","score":0},{"id":"boy","label":"走近那个拿着两杯咖啡的男孩","nextStepId":"together","score":20},{"id":"push","label":"伸手推开这道边界","nextStepId":"escape","score":30}]},{"id":"standard","title":"标准版本","body":"你看,我现在站得直直的,双手贴在裤缝边。","terminal":true,"outcomeCode":"PREFAB_STANDARD","outcomeLabel":"标准版本","options":[]},{"id":"together","title":"共处","body":"他把其中一杯递给你。人流从你们两个身边绕过去。","terminal":true,"outcomeCode":"PREFAB_TOGETHER","outcomeLabel":"共处","options":[]},{"id":"escape","title":"出走","body":"画面翻转成一段对话:「你看。我现在站得直直的。」然后它要求你把手机扣下 30 秒。","terminal":true,"outcomeCode":"PREFAB_ESCAPE","outcomeLabel":"出走","options":[]}]}}' config_json
  ) seed
 WHERE NOT EXISTS (SELECT 1 FROM cms_member_template m
                    WHERE m.member_id=100000 AND m.title=seed.title AND m.del_flag=0);

-- ======================= 四、5 个 GPS 节点 =======================

INSERT INTO cms_topic_node
       (topic_id, chapter_id, name, description, address, longitude, latitude,
        template_id, checkin_code, business_time, status, sort_id, xp_value,
        hook_text, card_hook_long, fragment_text, create_by, create_time)
SELECT t.id, c.id, seed.node_name, seed.node_desc, seed.address, seed.lng, seed.lat,
       m.id, REPLACE(UUID(),'-',''), '09:00-22:00', 1, seed.sort_id, 20,
       seed.hook_text, seed.card_hook_long, seed.fragment_text,
       'seed_prefab_life_20260918', NOW()
  FROM (
       SELECT 1 chapter_sort, 1 sort_id,
              '第一站 · 出生登记' node_name,
              '在城市模型前登记身份,拍下带「新」字的招牌,数一数低头看手机的人。' node_desc,
              '上海市黄浦区人民大道100号上海城市规划展示馆外部公共区域' address,
              '121.475621' lng, '31.231299' lat,
              'PL01-出生登记:写下你叫什么' template_title,
              '先有一张出生登记' hook_text,
              '站 1/5:抵达展馆外部公共区域,50 米内自动解锁。只观察建筑与街面,不入馆、不消费。' card_hook_long,
              '档案一｜出生:系统给了你一个名字,和一条所有人都走过的标准路线。' fragment_text
       UNION ALL SELECT 2, 2,
              '第二站 · 换乘通道',
              '沿换乘通道走约 420 米,听 30 秒广播,回答那道三选题。',
              '上海市黄浦区人民广场地铁站地面出口及换乘通道公共区域',
              '121.474539', '31.229381',
              'PL02-换乘通道:三题与六百步',
              '所有人都在说同一个动词',
              '站 2/5:只作正常通行,不为任务停留、逆行或拍摄他人。到点后解锁玩法。',
              '档案二｜系统:换乘、出口、排队——城市用动词管理每一个经过的人。'
       UNION ALL SELECT 2, 3,
              '第三站 · 会议出口',
              '在写字楼外的公共人行道过一道检定,再限时打出 hello world。',
              '上海市黄浦区黄河路28号国际饭店前公共人行道',
              '121.471400', '31.233915',
              'PL03-会议出口:hello world',
              '有些话没说出口',
              '站 3/5:只在人行道完成,不进入楼宇、不妨碍通行。这里没有商家接待,也不需要消费。',
              '档案三｜工作:「先放一放」之后,你听见自己说出了哪个词?'
       UNION ALL SELECT 3, 4,
              '第四站 · 被切开的天',
              '在楼梯间窗边拍一条被高楼切开的天,写一句留给下一个人。',
              '上海市黄浦区南京东路830号第一百货商业中心外部公共区域',
              '121.475239', '31.235298',
              'PL04-被切开的天:拍一张留一句',
              '天被楼切成了一条缝',
              '站 4/5:可只逛不买;拍照请遵守商场规定,不拍摄他人。留言 40 字以内。',
              '档案四｜命数:小周问「那又怎么样呢」,你没有立刻回答。'
       UNION ALL SELECT 4, 5,
              '第五站 · 边界',
              '在南京东路尽头做出最终选择:标准版本、共处,还是推开边界。',
              '上海市黄浦区南京东路与西藏中路交叉口西段公共步行街',
              '121.473903', '31.236111',
              'PL05-边界:终章三选一',
              '墙上写着被你改掉的话',
              '站 5/5:只在开放步行街完成;客流管控时服从现场安排。做完选择,这一局就结束了。',
              '档案五｜边界:世界允许你存在,却不会完全接受你。除非你推开它。'
  ) seed
  JOIN cms_topic t
    ON t.member_id=100000 AND t.del_flag=0 AND t.name='预制人生 · 四站试玩版'
  JOIN cms_topic_chapter c
    ON c.topic_id=t.id AND c.sort_id=seed.chapter_sort
  JOIN cms_member_template m
    ON m.member_id=100000 AND m.title=seed.template_title AND m.del_flag=0
 WHERE NOT EXISTS (SELECT 1 FROM cms_topic_node n
                    WHERE n.topic_id=t.id AND n.name=seed.node_name);

-- ======================= 五、章节故事流 blocks(读 → 玩 → 梦) =======================
-- 文本块带故事变量与兜底;dream 块按契约 §3.2 形状写;第 2 章按「玩 → 读 → 玩 → 梦」。
-- 全部用等值覆盖,重跑不新增、不漂移。

UPDATE cms_topic_chapter ch
   SET ch.blocks = CONCAT(
       '[{"type":"text","content":"你在一间摆满城市模型的房间里醒来。系统先让你登记:你叫什么,你做什么,你信不信标准答案。老师敲了敲讲台:「{name|那个人},来,站起来,你来回答一下?」"},',
       '{"type":"node","nodeId":',
       (SELECT n.id FROM cms_topic_node n
         WHERE n.topic_id=ch.topic_id AND n.status=1 AND n.name='第一站 · 出生登记'),
       '},',
       '{"type":"dream","key":"p1_dream","images":[{"url":"https://www.chengyinhub.com/prod-api/profile/topic-covers/old-movie.webp","line":"梦 · 一:有人把一座城的记忆折成纸船,放进苏州河。"},{"url":"https://www.chengyinhub.com/prod-api/profile/topic-covers/prebuilt-life.webp","line":"梦 · 二:所有窗户同时亮起,像一次整齐的呼吸。"},{"url":"https://www.chengyinhub.com/prod-api/profile/topic-covers/time-pollution.webp","line":"梦 · 三:粉笔在黑板上写下日期,又把它擦掉。"}]}]'),
       ch.update_by='seed_prefab_life_20260918', ch.update_time=NOW()
 WHERE ch.topic_id=(SELECT t.id FROM cms_topic t
                     WHERE t.member_id=100000 AND t.del_flag=0 AND t.name='预制人生 · 四站试玩版')
   AND ch.sort_id=1;

UPDATE cms_topic_chapter ch
   SET ch.blocks = CONCAT(
       '[{"type":"node","nodeId":',
       (SELECT n.id FROM cms_topic_node n
         WHERE n.topic_id=ch.topic_id AND n.status=1 AND n.name='第二站 · 换乘通道'),
       '},',
       '{"type":"text","content":"换乘通道里每个人都走得很快。广播念着同一个动词,人群替你做了选择。无论是{job|上班族}还是别的身份,系统都用同一个动词管理。老板把方案推到一边:「这个方案……先放一放。」{name|那个人},你听见自己在会议室的安静里开口。"},',
       '{"type":"node","nodeId":',
       (SELECT n.id FROM cms_topic_node n
         WHERE n.topic_id=ch.topic_id AND n.status=1 AND n.name='第三站 · 会议出口'),
       '},',
              '{"type":"dream","key":"p2_dream","images":[{"url":"https://www.chengyinhub.com/prod-api/profile/topic-covers/anomaly-coordinate.webp","line":"梦 · 一:一趟列车开进不存在的站台,所有人照常下车。"},{"url":"https://www.chengyinhub.com/prod-api/profile/topic-covers/apocalypse-broadcast.webp","line":"梦 · 二:有人用最后一段广播念了一天的工作安排。"},{"url":"https://www.chengyinhub.com/prod-api/profile/topic-covers/heat-escape.webp","line":"梦 · 三:写字楼的玻璃幕墙把天空折成格子。"}]}]'),
       ch.update_by='seed_prefab_life_20260918', ch.update_time=NOW()
 WHERE ch.topic_id=(SELECT t.id FROM cms_topic t
                     WHERE t.member_id=100000 AND t.del_flag=0 AND t.name='预制人生 · 四站试玩版')
   AND ch.sort_id=2;

UPDATE cms_topic_chapter ch
   SET ch.blocks = CONCAT(
       '[{"type":"text","content":"第一百货的楼梯间里,小周点了一根烟。「那又怎么样呢?」他问,「真的世界里,难道就不用打卡了吗?」你看向他身后那道被高楼切开的天。"},',
       '{"type":"node","nodeId":',
       (SELECT n.id FROM cms_topic_node n
         WHERE n.topic_id=ch.topic_id AND n.status=1 AND n.name='第四站 · 被切开的天'),
       '},',
       '{"type":"dream","key":"p3_dream","images":[{"url":"https://www.chengyinhub.com/prod-api/profile/topic-covers/wildlife-observation.webp","line":"梦 · 一:整座公园安静得像一段没被写下的证词。"},{"url":"https://www.chengyinhub.com/prod-api/profile/topic-covers/flavor-tour.webp","line":"梦 · 二:有人把家常菜做成了只有一个人吃的分量。"},{"url":"https://www.chengyinhub.com/prod-api/profile/topic-covers/midlife-day.webp","line":"梦 · 三:你终于长到能理解父母那代人的沉默。"}]}]'),
       ch.update_by='seed_prefab_life_20260918', ch.update_time=NOW()
 WHERE ch.topic_id=(SELECT t.id FROM cms_topic t
                     WHERE t.member_id=100000 AND t.del_flag=0 AND t.name='预制人生 · 四站试玩版')
   AND ch.sort_id=3;

UPDATE cms_topic_chapter ch
   SET ch.blocks = CONCAT(
       '[{"type":"text","content":"南京东路的尽头立着一道看不见的墙,上面写满一路被你改掉的话。{name|那个人},这是最后一次由你选。"},',
       '{"type":"node","nodeId":',
       (SELECT n.id FROM cms_topic_node n
         WHERE n.topic_id=ch.topic_id AND n.status=1 AND n.name='第五站 · 边界'),
       '},',
       '{"type":"dream","key":"p4_dream","images":[{"url":"https://www.chengyinhub.com/prod-api/profile/topic-covers/time-pollution.webp","line":"梦 · 一:被删掉的那一天,其实一直站在原地等你。"},{"url":"https://www.chengyinhub.com/prod-api/profile/topic-covers/old-movie.webp","line":"梦 · 二:旧电影散场,灯亮起来的时候,座位还是温的。"},{"url":"https://www.chengyinhub.com/prod-api/profile/topic-covers/prebuilt-life.webp","line":"梦 · 三:有人在你写过的句子上,接着写了一句。"}]}]'),
       ch.update_by='seed_prefab_life_20260918', ch.update_time=NOW()
 WHERE ch.topic_id=(SELECT t.id FROM cms_topic t
                     WHERE t.member_id=100000 AND t.del_flag=0 AND t.name='预制人生 · 四站试玩版')
   AND ch.sort_id=4;

-- ======================= 六、逐站前置解锁(跨章线性) =======================

UPDATE cms_topic_node cur
  JOIN cms_topic_node prev
    ON prev.topic_id=cur.topic_id AND prev.sort_id=cur.sort_id-1 AND prev.status=1
   SET cur.unlock_after_node_id=prev.id,
       cur.update_by='seed_prefab_life_20260918', cur.update_time=NOW()
 WHERE cur.topic_id=(SELECT t.id FROM cms_topic t
                      WHERE t.member_id=100000 AND t.del_flag=0 AND t.name='预制人生 · 四站试玩版')
   AND cur.sort_id>1;

-- ======================= 七、事务外 fail-closed 回读 =======================
-- 主题必须仍满足「免费自玩可领 + 不对公众可见」两套事实;任一漂移,断言返回 0,runner 报错。

-- runner: assert=scalar-true
SELECT (
  (SELECT COUNT(*) FROM cms_topic t
    WHERE t.member_id=100000 AND t.name='预制人生 · 四站试玩版' AND t.del_flag=0) = 1
  AND (SELECT COUNT(*) FROM cms_topic t
        WHERE t.member_id=100000 AND t.name='预制人生 · 四站试玩版' AND t.del_flag=0
          AND t.topic_type=1 AND t.product_type=1 AND t.status=1 AND t.user_status=1
          AND t.is_template=1 AND t.is_recommend=0 AND t.lifecycle=0
          AND t.self_play=1 AND t.self_play_price=0.00
          AND t.club_id IS NULL AND coalesce(t.club_member_only,0)=0
          AND t.route_mode='LINEAR' AND t.config_version=1 AND t.published_config_version=1) = 1
  AND (SELECT COUNT(*) FROM cms_topic t
        WHERE t.member_id=100000 AND t.name='预制人生 · 四站试玩版' AND t.del_flag=0
          AND NOT EXISTS (SELECT 1 FROM oms_ticket k
                           WHERE k.owner_type=1 AND k.owner_id=t.id
                             AND coalesce(k.del_flag,0)<>2)) = 1
  AND (SELECT COUNT(*) FROM cms_topic t
        WHERE t.member_id=100000 AND t.name='预制人生 · 四站试玩版' AND t.del_flag=0
          AND NOT EXISTS (SELECT 1 FROM merchant_chapter_offer o WHERE o.topic_id=t.id)) = 1
  AND (SELECT COUNT(*) FROM cms_topic t
        WHERE t.member_id=100000 AND t.name='预制人生 · 四站试玩版' AND t.del_flag=0
          AND NOT EXISTS (SELECT 1 FROM cms_topic_chapter c
                           WHERE c.topic_id=t.id AND c.recruit_enabled=1)) = 1
  AND (SELECT COUNT(*) FROM product_role r
        JOIN cms_topic t ON t.id=r.topic_id
        WHERE t.member_id=100000 AND t.name='预制人生 · 四站试玩版' AND t.del_flag=0
          AND r.slot='CONTENT' AND r.entity_type='PLATFORM' AND r.entity_id IS NULL AND r.status=1) = 1
  AND (SELECT COUNT(*) FROM cms_topic_chapter c
        JOIN cms_topic t ON t.id=c.topic_id
        WHERE t.member_id=100000 AND t.name='预制人生 · 四站试玩版' AND t.del_flag=0
          AND c.required=1 AND c.recruit_enabled=0) = 4
  AND (SELECT COUNT(*) FROM cms_topic_chapter c
        JOIN cms_topic t ON t.id=c.topic_id
        WHERE t.member_id=100000 AND t.name='预制人生 · 四站试玩版' AND t.del_flag=0
          AND c.blocks IS NOT NULL AND JSON_VALID(c.blocks)=1
          AND LOCATE('"type":"text"',c.blocks)>0
          AND LOCATE('"type":"node"',c.blocks)>0
          AND LOCATE('"type":"dream"',c.blocks)>0) = 4
  AND (SELECT COUNT(*) FROM cms_topic_chapter c
        JOIN cms_topic t ON t.id=c.topic_id
        WHERE t.member_id=100000 AND t.name='预制人生 · 四站试玩版' AND t.del_flag=0
          AND (c.blocks LIKE '%{name}%' OR c.blocks LIKE '%{job}%')) = 0
  AND (SELECT COUNT(*) FROM cms_topic_node n
        JOIN cms_topic t ON t.id=n.topic_id
        WHERE t.member_id=100000 AND t.name='预制人生 · 四站试玩版' AND t.del_flag=0
          AND n.status=1 AND n.longitude IS NOT NULL AND n.latitude IS NOT NULL) = 5
  AND (SELECT COUNT(*) FROM cms_topic_node n
        JOIN cms_topic t ON t.id=n.topic_id
        JOIN cms_member_template m ON m.id=n.template_id AND m.del_flag=0
        WHERE t.member_id=100000 AND t.name='预制人生 · 四站试玩版' AND t.del_flag=0
          AND n.status=1 AND m.validation_method=5) = 5
  AND (SELECT COUNT(*) FROM cms_topic_node n
        JOIN cms_topic t ON t.id=n.topic_id
        JOIN cms_topic_node p ON p.topic_id=n.topic_id AND p.sort_id=n.sort_id-1 AND p.status=1
        WHERE t.member_id=100000 AND t.name='预制人生 · 四站试玩版' AND t.del_flag=0
          AND n.status=1 AND n.sort_id>1 AND n.unlock_after_node_id=p.id) = 4
  AND (SELECT COUNT(*) FROM cms_member_template m
        WHERE m.member_id=100000 AND m.del_flag=0 AND m.status=1 AND m.validation_method=5
          AND m.title IN ('PL01-出生登记:写下你叫什么','PL02-换乘通道:三题与六百步',
                          'PL03-会议出口:hello world','PL04-被切开的天:拍一张留一句',
                          'PL05-边界:终章三选一')) = 5
  AND (SELECT COUNT(*) FROM cms_member_template m
        WHERE m.member_id=100000 AND m.del_flag=0
          AND JSON_VALID(m.advanced_config_json)=1
          AND JSON_UNQUOTE(JSON_EXTRACT(m.advanced_config_json,'$.schemaVersion'))='1'
          AND m.title IN ('PL01-出生登记:写下你叫什么','PL02-换乘通道:三题与六百步',
                          'PL03-会议出口:hello world','PL04-被切开的天:拍一张留一句',
                          'PL05-边界:终章三选一')) = 5
  AND (SELECT COUNT(*) FROM cms_member_template m
        WHERE m.member_id=100000 AND m.del_flag=0
          AND m.title IN ('PL01-出生登记:写下你叫什么','PL02-换乘通道:三题与六百步',
                          'PL03-会议出口:hello world','PL04-被切开的天:拍一张留一句',
                          'PL05-边界:终章三选一')
          AND JSON_UNQUOTE(JSON_EXTRACT(m.advanced_config_json,'$.profile.enabled'))='true') = 1
  AND (SELECT COUNT(*) FROM cms_member_template m
        WHERE m.member_id=100000 AND m.del_flag=0
          AND m.title IN ('PL01-出生登记:写下你叫什么','PL02-换乘通道:三题与六百步',
                          'PL03-会议出口:hello world','PL04-被切开的天:拍一张留一句',
                          'PL05-边界:终章三选一')
          AND JSON_UNQUOTE(JSON_EXTRACT(m.advanced_config_json,'$.photoCheck.enabled'))='true') = 1
  AND (SELECT COUNT(*) FROM cms_member_template m
        WHERE m.member_id=100000 AND m.del_flag=0
          AND m.title IN ('PL01-出生登记:写下你叫什么','PL02-换乘通道:三题与六百步',
                          'PL03-会议出口:hello world','PL04-被切开的天:拍一张留一句',
                          'PL05-边界:终章三选一')
          AND JSON_UNQUOTE(JSON_EXTRACT(m.advanced_config_json,'$.note.enabled'))='true') = 1
  AND (SELECT COUNT(*) FROM cms_member_template m
        WHERE m.member_id=100000 AND m.del_flag=0
          AND m.title IN ('PL01-出生登记:写下你叫什么','PL02-换乘通道:三题与六百步',
                          'PL03-会议出口:hello world','PL04-被切开的天:拍一张留一句',
                          'PL05-边界:终章三选一')
          AND JSON_UNQUOTE(JSON_EXTRACT(m.advanced_config_json,'$.typeIn.enabled'))='true') = 1
  AND (SELECT COUNT(*) FROM cms_member_template m
        WHERE m.member_id=100000 AND m.del_flag=0
          AND m.title IN ('PL01-出生登记:写下你叫什么','PL02-换乘通道:三题与六百步',
                          'PL03-会议出口:hello world','PL04-被切开的天:拍一张留一句',
                          'PL05-边界:终章三选一')
          AND JSON_UNQUOTE(JSON_EXTRACT(m.advanced_config_json,'$.steps.enabled'))='true') = 1
  AND (SELECT COUNT(*) FROM cms_member_template m
        WHERE m.member_id=100000 AND m.del_flag=0
          AND m.title IN ('PL01-出生登记:写下你叫什么','PL02-换乘通道:三题与六百步',
                          'PL03-会议出口:hello world','PL04-被切开的天:拍一张留一句',
                          'PL05-边界:终章三选一')
          AND JSON_UNQUOTE(JSON_EXTRACT(m.advanced_config_json,'$.qa.enabled'))='true') = 2
  AND (SELECT COUNT(*) FROM cms_member_template m
        WHERE m.member_id=100000 AND m.del_flag=0
          AND m.title IN ('PL01-出生登记:写下你叫什么','PL02-换乘通道:三题与六百步',
                          'PL03-会议出口:hello world','PL04-被切开的天:拍一张留一句',
                          'PL05-边界:终章三选一')
          AND JSON_UNQUOTE(JSON_EXTRACT(m.advanced_config_json,'$.estimate.enabled'))='true') = 1
  AND (SELECT COUNT(*) FROM cms_member_template m
        WHERE m.member_id=100000 AND m.del_flag=0
          AND m.title IN ('PL01-出生登记:写下你叫什么','PL02-换乘通道:三题与六百步',
                          'PL03-会议出口:hello world','PL04-被切开的天:拍一张留一句',
                          'PL05-边界:终章三选一')
          AND JSON_UNQUOTE(JSON_EXTRACT(m.advanced_config_json,'$.quietHold.enabled'))='true') = 1
  AND (SELECT COUNT(*) FROM cms_member_template m
        WHERE m.member_id=100000 AND m.del_flag=0
          AND m.title IN ('PL01-出生登记:写下你叫什么','PL02-换乘通道:三题与六百步',
                          'PL03-会议出口:hello world','PL04-被切开的天:拍一张留一句',
                          'PL05-边界:终章三选一')
          AND JSON_UNQUOTE(JSON_EXTRACT(m.advanced_config_json,'$.branch.enabled'))='true') = 1
  AND (SELECT COUNT(*) FROM cms_member_template m
        WHERE m.member_id=100000 AND m.del_flag=0
          AND m.title IN ('PL01-出生登记:写下你叫什么','PL02-换乘通道:三题与六百步',
                          'PL03-会议出口:hello world','PL04-被切开的天:拍一张留一句',
                          'PL05-边界:终章三选一')
          AND JSON_UNQUOTE(JSON_EXTRACT(m.advanced_config_json,'$.check.enabled'))='true') = 1
) AS migration_assertion;

-- ======================= 八、落库后只读回读(执行人手动跑,种子不代跑) =======================
-- 主题 id(直链开走用 pages/topic/index/index?id=<topic_id>):
-- SELECT id, name, is_template, status, user_status, lifecycle, self_play, self_play_price,
--        published_config_version, is_recommend
--   FROM cms_topic WHERE member_id=100000 AND name='预制人生 · 四站试玩版' AND del_flag=0;
-- 节点 id + GPS:
-- SELECT n.id, n.sort_id, n.name, n.longitude, n.latitude, n.unlock_after_node_id, m.title
--   FROM cms_topic_node n
--   JOIN cms_topic t ON t.id=n.topic_id
--   JOIN cms_member_template m ON m.id=n.template_id
--  WHERE t.member_id=100000 AND t.name='预制人生 · 四站试玩版' AND t.del_flag=0
--  ORDER BY n.sort_id;
