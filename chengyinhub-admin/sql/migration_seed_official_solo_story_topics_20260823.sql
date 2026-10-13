-- runner: requires=transaction
-- ============================================================================
-- 城瘾官方单人城市剧情活动（上海 MVP 草稿）
--
-- 5 个 CITY_ORIENTEERING 主题 / 每主题 1 章 5 站 / 25 个 GPS 剧情节点。
-- 这些是平台自营主题，不是商家/俱乐部可复制的主题模板：is_template=0。
--
-- 发布边界：
--   · status=1 代表内容记录有效；user_status=0 且 lifecycle=0(DRAFT)代表仍未公开上架；
--   · self_play=1、首发价 19.90 元、每主题 100 份，但 user_status=0 仍禁止购买；
--   · 公开前须完成 25 点 GCJ-02 双机实走、内容审核与功能开关回读；
--   · 所有点位都要求现有 50m GPS 围栏，商家、场馆和工作人员零配置、零接待；
--   · 封面复用仓库内已完成来源记录的真实上海摄影图，不生成 AI 图片；
--   · 前四站用现有分支运行时呈现现场档案，最终站提供三选一结局；不新增页面或交互协议。
--
-- 幂等键：cms_topic(member_id=100000,name)、cms_member_template(member_id=100000,title)、
--         cms_topic_node(topic_id,name)。纯 DML，不创建/修改表结构。
-- ============================================================================

-- ======================= 一、5 个平台自营主题草稿 =======================

DROP TEMPORARY TABLE IF EXISTS seed_official_solo_story_topics;
CREATE TEMPORARY TABLE seed_official_solo_story_topics (
  name VARCHAR(80) NOT NULL PRIMARY KEY,
  subtitle VARCHAR(120) NOT NULL,
  description TEXT NOT NULL,
  address_name VARCHAR(120) NOT NULL,
  total_time INT NOT NULL,
  total_mileage DECIMAL(6,2) NOT NULL,
  img_url VARCHAR(255) NOT NULL,
  medal_name VARCHAR(40) NOT NULL
) ENGINE=InnoDB;

INSERT INTO seed_official_solo_story_topics
       (name, subtitle, description, address_name, total_time, total_mileage, img_url, medal_name)
SELECT '城市藏宝猎人' name, '外滩失落档案' subtitle,
              '你收到一份被删去署名的城市档案。沿苏州河与外滩依次找到五处建筑密码，收集时间、河流、钢架、石墙与钟声五枚证据，决定这份档案最后交给谁。全程只使用公共空间和建筑外观，不要求任何商家或工作人员参与。首批仅限 09:00–18:00 出发；雷暴、台风、红色高温预警或点位临时封闭时应停止并延期。建议穿步行鞋并保留 50% 以上电量。封面摄影：Chainwit. / Wikimedia Commons / CC BY 4.0。' description,
              '上海市虹口区、黄浦区' address_name, 5400 total_time, 1.8 total_mileage,
              'https://www.chengyinhub.com/prod-api/profile/topic-covers/old-movie.webp' img_url,
              '失落档案回收员' medal_name
       UNION ALL SELECT '野生邻居观察局', '中山公园异常生态档案',
              '城市生态观察局收到五份互相矛盾的记录。你需要在不投喂、不追逐、不接触动物的前提下，依次完成树冠、草地、水边、灌木与归档站观察，判断所谓“异常”究竟来自动物，还是来自人的观看方式。首批仅限 09:00–18:00 出发；恶劣天气或公园管制时应停止并延期。全程公共空间，无需进店、入馆或工作人员；建议穿步行鞋并保留 50% 以上电量。封面摄影：Oleg Prachuk / Pexels。',
              '上海市长宁区中山公园', 4500, 1.5,
              'https://www.chengyinhub.com/prod-api/profile/topic-covers/wildlife-observation.webp', '低干扰观察员'
       UNION ALL SELECT '异常事务局：不存在的坐标', '徐汇滨江 A-00 收容行动',
              '城瘾异常事务局在徐汇滨江检测到一个地图不存在、却会重复出现在照片里的坐标。五个现实地点组成一条收容链；玩家必须按顺序恢复编号、边界、回声、容器和最终坐标，并在终点选择收容、公开或删除。故事完全虚构，不使用 SCP 名称或既有设定。首批仅限 09:00–18:00 出发；滨江强风、雷暴、台风或点位临时封闭时应停止并延期。全程公共空间，无需进店、入馆或工作人员；建议穿步行鞋并保留 50% 以上电量。封面摄影：Supanut Arunoprayote / Wikimedia Commons / CC BY 4.0。',
              '上海市徐汇区西岸', 5400, 3.0,
              'https://www.chengyinhub.com/prod-api/profile/topic-covers/anomaly-coordinate.webp', 'A-00 临时调查员'
       UNION ALL SELECT '末日广播：最后一座中继站', '陆家嘴最后广播链',
              '一次虚构的城市级断联事件发生，陆家嘴只剩最后一条广播链。你将依次恢复交通、水源、能源、避难与发射五个频道，并决定最后一段广播应该优先发给谁。这不是应急指南，不进入、不触碰任何真实基础设施。首批仅限 09:00–18:00 出发；滨江雷暴、台风、红色高温预警时应停止并延期。全程公共空间，无需进店、入馆或工作人员；建议穿步行鞋并保留 50% 以上电量。封面摄影：Garrison Gao / Pexels。',
              '上海市浦东新区陆家嘴', 5400, 2.0,
              'https://www.chengyinhub.com/prod-api/profile/topic-covers/apocalypse-broadcast.webp', '最后中继员'
       UNION ALL SELECT '时间污染：被删除的今天', '衡复街区时间修复任务',
              '城市里有一天从所有人的记忆中消失，只留下五个互相冲突的时间锚点。沿淮海中路与复兴公园收集书页、倒影、乐声、树影和门牌碎片，重排事件顺序，最后决定恢复原始时间线还是保留被改写的今天。首批仅限 10:00–18:00 出发；恶劣天气或点位临时封闭时应停止并延期。全程公共空间，无需进店、入馆或工作人员；建议穿步行鞋并保留 50% 以上电量。封面摄影：Yudi Ding / Pexels。',
              '上海市徐汇区、黄浦区', 7800, 4.2,
              'https://www.chengyinhub.com/prod-api/profile/topic-covers/time-pollution.webp', '时间修复员';

INSERT INTO cms_topic
       (member_id, topic_type, product_type, name, subtitle, description,
        address_name, total_time, total_mileage, location_count, template_count, img_url,
        is_template, status, user_status, merchant_status, self_play, self_play_price,
        self_play_quota, finish_medal_name, lifecycle, del_flag, create_by, create_time)
SELECT 100000, 1, 1, seed.name, seed.subtitle, seed.description,
       seed.address_name, seed.total_time, seed.total_mileage, 5, 5, seed.img_url,
       0, 1, 0, 0, 1, 19.90, 100, seed.medal_name, 0, 0,
       'seed_official_solo_story_20260823', NOW()
  FROM seed_official_solo_story_topics seed
 WHERE NOT EXISTS (
       SELECT 1 FROM cms_topic t
        WHERE t.member_id=100000 AND t.name=seed.name AND t.del_flag=0
 );

-- 平台内容槽：不招商、不绑定商户。
INSERT INTO product_role (topic_id, slot, entity_type, entity_id, status)
SELECT t.id, 'CONTENT', 'PLATFORM', NULL, 1
  FROM cms_topic t
 WHERE t.member_id=100000 AND t.del_flag=0
   AND t.name IN ('城市藏宝猎人','野生邻居观察局','异常事务局：不存在的坐标',
                  '末日广播：最后一座中继站','时间污染：被删除的今天')
   AND NOT EXISTS (
       SELECT 1 FROM product_role r
        WHERE r.topic_id=t.id AND r.slot='CONTENT' AND r.status=1
   );

-- ======================= 二、每主题一章 =======================

INSERT INTO cms_topic_chapter
       (topic_id, name, description, sort_id, location_count, template_count,
        required, recruit_enabled, max_merchant, terms_mode, create_by, create_time)
SELECT t.id,
       CASE t.name
         WHEN '城市藏宝猎人' THEN '任务 01 · 回收失落档案'
         WHEN '野生邻居观察局' THEN '任务 02 · 低干扰观察'
         WHEN '异常事务局：不存在的坐标' THEN '任务 03 · A-00 收容链'
         WHEN '末日广播：最后一座中继站' THEN '任务 04 · 恢复五段频道'
         ELSE '任务 05 · 修复时间锚点' END,
       CASE t.name
         WHEN '城市藏宝猎人' THEN '五站必须按顺序完成。每抵达一站，系统回收一枚城市证据并解锁下一坐标。'
         WHEN '野生邻居观察局' THEN '只观察，不投喂、不追逐、不触摸。未看到动物也可以完成：环境本身就是证据。'
         WHEN '异常事务局：不存在的坐标' THEN '不要寻找现实中的危险或进入封闭区域；异常只存在于城瘾剧情与数字档案中。'
         WHEN '末日广播：最后一座中继站' THEN '这是虚构任务，不是应急演练。只在公共空间行走，不接触真实设备。'
         ELSE '五个锚点顺序固定；每站恢复一段记忆，最终选择会生成不同结局。' END,
       1, 5, 5, 1, 0, 0, 'TRAFFIC', 'seed_official_solo_story_20260823', NOW()
  FROM cms_topic t
 WHERE t.member_id=100000 AND t.del_flag=0
   AND t.name IN ('城市藏宝猎人','野生邻居观察局','异常事务局：不存在的坐标',
                  '末日广播：最后一座中继站','时间污染：被删除的今天')
   AND NOT EXISTS (
       SELECT 1 FROM cms_topic_chapter c
        WHERE c.topic_id=t.id AND c.sort_id=1
   );

-- ======================= 三、25 个零商家 GPS 剧情玩法 =======================

DROP TEMPORARY TABLE IF EXISTS seed_official_solo_story_games;
CREATE TEMPORARY TABLE seed_official_solo_story_games (
  title VARCHAR(120) NOT NULL PRIMARY KEY,
  description VARCHAR(500) NOT NULL,
  usage_location VARCHAR(255) NOT NULL,
  duration INT NOT NULL,
  difficulty VARCHAR(24) NOT NULL,
  rule_instructions TEXT NOT NULL,
  feedback_text TEXT NOT NULL,
  story_text TEXT NOT NULL,
  advanced_config_json JSON NULL
) ENGINE=InnoDB;

INSERT INTO seed_official_solo_story_games
       (title, description, usage_location, duration, difficulty, rule_instructions,
        feedback_text, story_text, advanced_config_json)
SELECT 'OS01-档案回收：时间戳' title, '抵达邮政大楼外部，回收第一枚时间证据。' description,
              '上海邮政博物馆外部公共区域' usage_location, 8 duration, '入门' difficulty,
              '在允许通行的公共位置观察钟楼与建筑轮廓；进入 50 米范围后由 GPS 完成，不进入非开放区域。' rule_instructions,
              '第一枚证据已归档：城市用钟表证明它没有停止。' feedback_text,
              '匿名档案的第一页只有一句话：先找到一座仍在替全城报时的建筑。钟面不是答案，它只是证明“失踪的时间”曾经存在。' story_text,
              NULL advanced_config_json
       UNION ALL SELECT 'OS01-档案回收：河流交汇', '在乍浦路桥确认两条城市水系的交汇视角。',
              '乍浦路桥公共步行区域', 8, '入门',
              '在人行区域停留，观察苏州河汇入黄浦江的方向；不要走入机动车道或为拍照倚靠护栏。',
              '第二枚证据已归档：水流记得地图删掉的方向。',
              '第二页被水浸过。纸上只剩两条线，一条细，一条宽；它们在桥下汇合，像两段互相校正的证词。', NULL
       UNION ALL SELECT 'OS01-档案回收：钢架编号', '抵达外白渡桥，确认第三枚结构证据。',
              '外白渡桥公共步行区域', 8, '普通',
              '只在开放人行道观察钢桁架的重复结构；不攀爬、不触碰设施。',
              '第三枚证据已归档：重复的钢架组成了可验证的序列。',
              '第三页没有文字，只有连续的三角形。档案员相信，城市会把秘密藏在最公开、最重复的结构里。', NULL
       UNION ALL SELECT 'OS01-档案回收：石墙拓印', '沿圆明园路回收第四枚建筑表面证据。',
              '圆明园路公共街区', 8, '普通',
              '观察一处允许拍摄的建筑表面，记住颜色、材质或窗洞形状；不进入限制区域，不触摸文保构件。',
              '第四枚证据已归档：石墙保存了比署名更久的笔迹。',
              '第四页写着：“不要找门牌，找门牌背后的墙。”当建筑的名字变化，材料仍保留最初的语气。', NULL
       UNION ALL SELECT 'OS01-最终处置：钟声档案', '在海关大楼钟楼视线范围内完成最终档案处置。',
              '外滩海关大楼外部公共区域', 12, '普通',
              '抵达外滩公共步行区域，完成屏幕中的最终选择后，再用 GPS 确认现场；不进入办公区域。',
              '五枚证据已闭合，你的处置决定已写入个人城市档案。',
              '钟声响起时，档案终于补全署名：它属于每一个认真看过城市的人。现在，事务局要求你决定这份档案的去向。',
              CAST('{"schemaVersion":1,"branch":{"enabled":true,"startStepId":"decision","steps":[{"id":"decision","title":"失落档案最终处置","body":"五枚证据已经互相印证。你准备如何处理这份无署名档案？","terminal":false,"options":[{"id":"return","label":"交还城市公共档案","nextStepId":"public","score":20},{"id":"copy","label":"保留副本继续调查","nextStepId":"keeper","score":10},{"id":"seal","label":"按原命令永久封存","nextStepId":"sealed","score":0}]},{"id":"public","title":"公开档案结局","body":"档案恢复公开，失去署名的人重新回到城市记忆。","terminal":true,"options":[]},{"id":"keeper","title":"守档人结局","body":"你保留一份副本，成为下一位城市藏宝猎人。","terminal":true,"options":[]},{"id":"sealed","title":"封存结局","body":"档案被重新封存，但钟声提醒你：城市并没有忘记。","terminal":true,"options":[]}]}}' AS JSON)

       UNION ALL SELECT 'OS02-观察记录：树冠', '在中山公园入口附近建立低干扰观察基线。',
              '中山公园公共开放区域', 8, '入门',
              '先看环境再找动物：记录树冠、天气和人流。不要播放诱鸟声、投喂或追逐。',
              '基线建立：没有发现异常也是有效观察。',
              '第一份异常报告说“树上什么都没有”。观察局把它退回，因为真正的记录必须先写环境，再写结论。', NULL
       UNION ALL SELECT 'OS02-观察记录：草地', '在草地区域记录人类活动与动物距离。',
              '中山公园大草坪周边开放区域', 8, '入门',
              '停在步道或允许停留处观察；不进入养护区域。记录动物如何避让人群，不靠近巢穴。',
              '第二份记录已归档：距离本身就是行为证据。',
              '第二份报告里，所有动物都朝同一个方向移动。调查员后来发现，那不是异常，只是人群从另一侧靠近。', NULL
       UNION ALL SELECT 'OS02-观察记录：水边', '在水边记录反光、声音与活动边界。',
              '中山公园水体周边开放步道', 8, '普通',
              '在护栏或步道内观察，不下水、不跨越围挡、不向水中投喂。',
              '第三份记录已归档：水面会放大误判，也会保留细节。',
              '第三份报告把倒影当成了第二只动物。观察局提醒你：异常结论至少需要两个独立证据。', NULL
       UNION ALL SELECT 'OS02-观察记录：灌木边界', '记录小型生物可能使用的隐蔽边界。',
              '中山公园灌木与林缘开放步道', 8, '普通',
              '只从步道观察落叶、洞口、羽毛或移动痕迹；不翻动、不采集、不伸手进入灌木。',
              '第四份记录已归档：不打扰，才有机会看到真实行为。',
              '第四份报告终于没有照片。调查员写道：“我放下手机后，它才从叶子后面出来。”', NULL
       UNION ALL SELECT 'OS02-观察结论：谁才异常', '在终点提交观察局结论。',
              '中山公园公共开放区域终点', 12, '普通',
              '完成屏幕中的结论选择，再在开放区域用 GPS 确认归档；不拍摄可识别的陌生人。',
              '观察档案完成。你的结论不会给真实物种贴上危险标签。',
              '五份记录放在一起后，所谓异常逐渐消失。也许需要被校正的从来不是动物，而是观察者急于下结论的方式。',
              CAST('{"schemaVersion":1,"branch":{"enabled":true,"startStepId":"decision","steps":[{"id":"decision","title":"观察局最终结论","body":"你要如何标记本次“异常生态事件”？","terminal":false,"options":[{"id":"disturbance","label":"人类干扰导致的行为变化","nextStepId":"guardian","score":20},{"id":"unknown","label":"证据不足，保持未知","nextStepId":"observer","score":20},{"id":"monster","label":"确认存在危险异常生物","nextStepId":"rejected","score":-10}]},{"id":"guardian","title":"守护者结局","body":"档案被归为低干扰保护建议，你获得观察局守护者权限。","terminal":true,"options":[]},{"id":"observer","title":"观察者结局","body":"你拒绝过度解释，档案保留未知状态等待长期记录。","terminal":true,"options":[]},{"id":"rejected","title":"退回重审","body":"没有证据支持危险结论。观察局要求你重新学习如何与野生邻居保持距离。","terminal":true,"options":[]}]}}' AS JSON)

       UNION ALL SELECT 'OS03-A00：编号恢复', '在龙美术馆西岸馆外部恢复异常编号。',
              '龙美术馆西岸馆外部公共区域', 8, '普通',
              '观察工业遗存与建筑体量，在开放区域完成 GPS 确认；不进入闭馆或施工区域。',
              'A-00/1 已恢复：编号曾被覆盖，但载体仍在。',
              '监测画面里，煤漏斗每隔七秒就少一条边。现场却一切正常。异常事务局把这里标为 A-00 的第一处稳定锚。', NULL
       UNION ALL SELECT 'OS03-A00：边界校准', '在西岸美术馆外部校准异常边界。',
              '西岸美术馆外部公共区域', 8, '普通',
              '沿开放步道观察建筑与道路边界，不进入活动封闭区。',
              'A-00/2 已恢复：边界只在屏幕里偏移。',
              '第二个坐标总比现实向东偏移十一米。你站在边界上时，系统第一次承认：地图可能不是唯一真相。', NULL
       UNION ALL SELECT 'OS03-A00：回声采样', '在西岸艺术中心外部采集第三段回声。',
              '西岸艺术中心外部公共区域', 8, '普通',
              '只在公共空间观察厂房结构与回声感，不敲击设施、不进入活动封闭区。',
              'A-00/3 已恢复：回声比原声晚了一次不存在的呼吸。',
              '第三段录音没有人声，却在结尾多出一句：“你已经来过。”系统检查时间戳，发现录音来自明天。', NULL
       UNION ALL SELECT 'OS03-A00：容器确认', '在油罐艺术中心公共外部确认异常容器。',
              '上海油罐艺术中心外部公共区域', 8, '普通',
              '只在公共空间观察圆形罐体与滨江关系；按现场开放规则通行。',
              'A-00/4 已恢复：容器并不保存物体，它保存坐标。',
              '第四份档案认为整个建筑是一只容器。可当你靠近，容器里装的不是展品，而是一个不断重写自己的位置。', NULL
       UNION ALL SELECT 'OS03-A00：最终收容', '在油罐艺术公园江岸上层步道完成 A-00 最终处置。',
              '油罐艺术公园江岸上层公共步道', 12, '进阶',
              '远离水边危险位置，在开放步道完成最终选择与 GPS 确认；恶劣天气停止任务。',
              'A-00 处置完成。系统已把你的选择写入本次收容记录。',
              '五个坐标重叠在江面上。A-00 并非一个地点，而是每次有人认真确认现实后产生的误差。事务局等待你的处置。',
              CAST('{"schemaVersion":1,"branch":{"enabled":true,"startStepId":"decision","steps":[{"id":"decision","title":"A-00 最终处置","body":"坐标已稳定 60 秒。选择处置协议。","terminal":false,"options":[{"id":"contain","label":"收容并持续监测","nextStepId":"contained","score":20},{"id":"disclose","label":"向公众公开异常","nextStepId":"public","score":10},{"id":"erase","label":"删除所有坐标记录","nextStepId":"erased","score":0}]},{"id":"contained","title":"收容结局","body":"A-00 被锁定在你的路线记录中。你获得临时调查员权限。","terminal":true,"options":[]},{"id":"public","title":"公开结局","body":"异常进入公共档案。越来越多人开始报告同一个不存在的坐标。","terminal":true,"options":[]},{"id":"erased","title":"删除结局","body":"所有记录归零。退出页面前，系统又短暂显示了一次 A-00。","terminal":true,"options":[]}]}}' AS JSON)

       UNION ALL SELECT 'OS04-中继恢复：交通频道', '在陆家嘴站 2 号口地面公共区域恢复交通频道。',
              '陆家嘴地铁站 2 号口地面公共区域', 8, '入门',
              '只在地面开放区域完成，不在闸机、站台或人流通道停留。',
              '交通频道恢复：路线仍可被城市识别。',
              '断联后的第一条广播只有列车进站提示。没有目的地，但它证明城市的路径系统仍在运行。', NULL
       UNION ALL SELECT 'OS04-中继恢复：水源频道', '在陆家嘴中心绿地恢复水源频道。',
              '陆家嘴中心绿地公共步道', 8, '入门',
              '沿开放步道活动，不进入水体或养护区域。',
              '水源频道恢复：环境数据重新上线。',
              '第二条广播反复播报湿度。你在绿地里找到稳定信号，系统却把它标成“海平面以下的雨”。', NULL
       UNION ALL SELECT 'OS04-中继恢复：能源频道', '在东方明珠塔外部公共广场恢复能源频道。',
              '东方明珠塔外部公共广场', 8, '普通',
              '只在公共广场与允许通行区域完成，不接触设备、不妨碍排队。',
              '能源频道恢复：城市轮廓重新点亮。',
              '第三条广播没有声音，只有不断上升的信号柱。塔身进入视野后，黑屏上第一次出现了光。', NULL
       UNION ALL SELECT 'OS04-中继恢复：避难频道', '在滨江大道开放区域恢复避难频道。',
              '陆家嘴滨江大道公共步道', 8, '普通',
              '在安全步道行走，远离湿滑或临水危险区域；恶劣天气立即停止。',
              '避难频道恢复：系统确认公共空间仍可抵达。',
              '第四条广播来自许多陌生人：有人报平安，有人只留下背景里的江水声。你必须决定哪些声音能进入最后一段广播。', NULL
       UNION ALL SELECT 'OS04-中继恢复：最后广播', '在浦东美术馆外部公共区域发送最后广播。',
              '浦东美术馆外部公共区域', 12, '进阶',
              '完成屏幕中的广播选择后，再以 GPS 确认；不进入闭馆区域，不冒充真实应急信息。',
              '最后广播已发送。本次结局仅属于虚构故事。',
              '五个频道只够支撑一次完整发送。每个选择都意味着另一些人会更晚收到消息。中继站把最后的决定交给你。',
              CAST('{"schemaVersion":1,"branch":{"enabled":true,"startStepId":"decision","steps":[{"id":"decision","title":"最后一次广播","body":"剩余带宽只够一条消息。你优先发送什么？","terminal":false,"options":[{"id":"route","label":"安全路线与集合点","nextStepId":"navigator","score":20},{"id":"family","label":"让所有人先报平安","nextStepId":"witness","score":15},{"id":"truth","label":"公开断联的真实原因","nextStepId":"truth","score":10}]},{"id":"navigator","title":"导航员结局","body":"城市重新获得方向。你的广播成为最先恢复的公共频道。","terminal":true,"options":[]},{"id":"witness","title":"守夜人结局","body":"成千上万条“我还在”接入网络，城市先恢复了彼此。","terminal":true,"options":[]},{"id":"truth","title":"揭露者结局","body":"真相被发送，但信号在最后一秒中断。你留下了唯一完整副本。","terminal":true,"options":[]}]}}' AS JSON)

       UNION ALL SELECT 'OS05-时间锚点：书页', '在上海图书馆淮海路馆外部恢复第一段日期。',
              '上海图书馆淮海路馆外部公共区域', 8, '入门',
              '按开放规则通行；可在外部完成，不要求入馆或预约。',
              '书页锚点恢复：被删除的日期重新出现一位数字。',
              '所有书都少了一页，只有目录保留空白位置。时间修复局判断：缺失的不是文字，而是整整一天。', NULL
       UNION ALL SELECT 'OS05-时间锚点：倒影', '在黑石 M+ 公共园区恢复第二段影像。',
              '黑石 M+ 公共园区', 8, '普通',
              '只在公共园区活动，不进入住宅或限制空间；不拍摄可识别的陌生人。',
              '倒影锚点恢复：影像中的天空与当前时间重新同步。',
              '旧照片里的窗户反射着夜空，拍摄时间却写着正午。你抬头时，那扇窗短暂映出一个不存在的傍晚。', NULL
       UNION ALL SELECT 'OS05-时间锚点：乐声', '在上海交响音乐厅外部恢复第三段声音。',
              '上海交响音乐厅外部公共区域', 8, '普通',
              '在公共空间完成，不播放外放音频、不妨碍演出与观众通行；下一站约需步行 25–30 分钟。',
              '乐声锚点恢复：缺失小节回到城市时间线。',
              '录音里每一分钟都完整，唯独同一小节被所有乐器同时跳过。时间污染不是静音，而是全城达成的一次遗忘。', NULL
       UNION ALL SELECT 'OS05-时间锚点：树影', '在复兴公园恢复第四段日照记录。',
              '复兴公园公共开放区域', 8, '普通',
              '遵守公园开放时间，在步道或允许停留处观察树影；不进入养护区域。',
              '树影锚点恢复：太阳重新承认那一天存在过。',
              '第四个锚点没有设备，只有树影。系统比对后发现：那一天的影子全部朝着不可能的方向。', NULL
       UNION ALL SELECT 'OS05-时间锚点：门牌', '在思南公馆公共街区修复最终时间线。',
              '思南公馆公共街区', 12, '进阶',
              '只在开放街区完成最终选择与 GPS 确认；室内空间按各自规则进入。',
              '时间线已写回。你选择的版本成为本次个人结局。',
              '五个锚点拼成了被删除的一天：那天城市没有发生灾难，只是所有人都做出了与原来不同的选择。你可以恢复旧版本，也可以留下它。',
              CAST('{"schemaVersion":1,"branch":{"enabled":true,"startStepId":"decision","steps":[{"id":"decision","title":"选择时间线","body":"两条时间线都稳定，但只能保留一条。","terminal":false,"options":[{"id":"restore","label":"恢复原始的今天","nextStepId":"original","score":20},{"id":"keep","label":"保留被改写的今天","nextStepId":"changed","score":15},{"id":"merge","label":"让两条时间线共同存在","nextStepId":"double","score":5}]},{"id":"original","title":"原始时间线结局","body":"城市回到熟悉的版本，只有你记得那一天曾被删除。","terminal":true,"options":[]},{"id":"changed","title":"改写时间线结局","body":"新的今天被保留下来，细小选择开始改变之后的城市。","terminal":true,"options":[]},{"id":"double","title":"双重时间线结局","body":"两种记忆同时存在。此后每个地点都可能有两个答案。","terminal":true,"options":[]}]}}' AS JSON);

-- GPS 节点本身不打开任务页；给前四站挂一张轻量剧情档案卡，玩家确认阅读后再走现有 GPS 到达。
-- 最终站已有三选一分支，保留原配置不覆盖。
UPDATE seed_official_solo_story_games
SET advanced_config_json=JSON_OBJECT(
      'schemaVersion', 1,
      'branch', JSON_OBJECT(
        'enabled', TRUE,
        'startStepId', 'briefing',
        'steps', JSON_ARRAY(
          JSON_OBJECT(
            'id', 'briefing', 'title', title, 'body', story_text, 'terminal', FALSE,
            'options', JSON_ARRAY(JSON_OBJECT(
              'id', 'record', 'label', '记录这条现场证据', 'nextStepId', 'logged', 'score', 5
            ))
          ),
          JSON_OBJECT(
            'id', 'logged', 'title', '证据已写入个人档案', 'body', feedback_text,
            'terminal', TRUE, 'options', JSON_ARRAY()
          )
        )
      )
    )
WHERE advanced_config_json IS NULL;

INSERT INTO cms_member_template
       (member_id, title, description, players, usage_location, required_materials,
        duration, difficulty, is_sync, rule_instructions, validation_method,
        feedback_text, story_text, advanced_config_json, xp_value,
        status, publish_status, draft_status, check_status, del_flag, create_by, create_time)
SELECT 100000, seed.title, seed.description, '1人', seed.usage_location, '手机、前台定位权限',
       seed.duration, seed.difficulty, 1, seed.rule_instructions, 5,
       seed.feedback_text, seed.story_text, seed.advanced_config_json, 20,
       1, 1, 1, 1, 0, 'seed_official_solo_story_20260823', NOW()
  FROM seed_official_solo_story_games seed
 WHERE NOT EXISTS (
       SELECT 1 FROM cms_member_template m
        WHERE m.member_id=100000 AND m.title=seed.title AND m.del_flag=0
 );

-- ======================= 四、25 个上海固定地点节点 =======================

DROP TEMPORARY TABLE IF EXISTS seed_official_solo_story_nodes;
CREATE TEMPORARY TABLE seed_official_solo_story_nodes (
  topic_name VARCHAR(80) NOT NULL,
  sort_id INT NOT NULL,
  node_name VARCHAR(120) NOT NULL,
  node_desc VARCHAR(500) NOT NULL,
  address VARCHAR(255) NOT NULL,
  longitude DECIMAL(10,6) NOT NULL,
  latitude DECIMAL(10,6) NOT NULL,
  template_title VARCHAR(120) NOT NULL,
  hook_text VARCHAR(120) NOT NULL,
  card_hook_long VARCHAR(300) NOT NULL,
  fragment_text VARCHAR(600) NOT NULL,
  PRIMARY KEY (topic_name, sort_id)
) ENGINE=InnoDB;

INSERT INTO seed_official_solo_story_nodes
       (topic_name, sort_id, node_name, node_desc, address, longitude, latitude,
        template_title, hook_text, card_hook_long, fragment_text)
SELECT '城市藏宝猎人' topic_name, 1 sort_id, '时间戳 · 上海邮政博物馆' node_name,
              '匿名档案的第一页要求你找到一座仍在报时的建筑。' node_desc,
              '上海市虹口区天潼路395号（南侧外部公共区域）' address, '121.485539' longitude, '31.244509' latitude,
              'OS01-档案回收：时间戳' template_title, '先找仍在报时的建筑' hook_text,
              '档案 1/5：观察钟楼，不需要入馆。系统将在 50 米范围内回收时间证据。' card_hook_long,
              '证据一｜时间：钟面继续运转，证明被删除的档案曾经属于真实的一天。' fragment_text
       UNION ALL SELECT '城市藏宝猎人',2,'河流交汇 · 乍浦路桥','第二页只剩两条相交的水线。',
              '上海市虹口区乍浦路桥公共步行区域','121.487784','31.244184','OS01-档案回收：河流交汇',
              '两条水线正在校正方向','档案 2/5：在安全人行区域确认苏州河与黄浦江的关系。',
              '证据二｜方向：水流不会服从被改写的地图，它仍然记得去向。'
       UNION ALL SELECT '城市藏宝猎人',3,'钢架编号 · 外白渡桥','第三页没有文字，只有重复的三角形。',
              '上海市虹口区外白渡桥东南侧公共步行区域','121.490228','31.243338','OS01-档案回收：钢架编号',
              '重复结构里藏着第三个编号','档案 3/5：只观察钢桁架，不攀爬、不触碰设施。',
              '证据三｜结构：连续钢架把一段被删掉的编号重新排列出来。'
       UNION ALL SELECT '城市藏宝猎人',4,'石墙拓印 · 圆明园路','第四页提醒你：不要找门牌，找门牌背后的墙。',
              '上海市黄浦区圆明园路公共街区','121.488090','31.242572','OS01-档案回收：石墙拓印',
              '名字会变，材料会留下','档案 4/5：观察允许拍摄的建筑表面，不触摸文保构件。',
              '证据四｜材料：当名字被拿走，石墙仍保存着建造者留下的语气。'
       UNION ALL SELECT '城市藏宝猎人',5,'钟声归档 · 海关大楼','五枚证据将在钟声视线范围内闭合。',
              '上海市黄浦区中山东一路13号（外部公共区域）','121.490127','31.236667','OS01-最终处置：钟声档案',
              '最后一页正在等待署名','档案 5/5：先在屏幕完成最终处置，再用 GPS 确认归档。',
              '证据五｜署名：档案属于每一个认真看过城市、并愿意留下证词的人。'

       UNION ALL SELECT '野生邻居观察局',1,'基线站 · 公园入口','先记录环境，不急着寻找动物。',
              '上海市长宁区长宁路780号中山公园入口附近','121.420690','31.224020','OS02-观察记录：树冠',
              '没有发现也是有效记录','观察 1/5：天气、树冠、人流先于物种判断。',
              '记录一｜基线：环境先于结论。今天看到与没看到的，都属于真实城市生态。'
       UNION ALL SELECT '野生邻居观察局',2,'距离站 · 大草坪','观察动物如何回应人的距离。',
              '上海市长宁区中山公园大草坪周边','121.418940','31.223020','OS02-观察记录：草地',
              '距离本身就是行为证据','观察 2/5：留在步道或允许区域，不追逐、不投喂。',
              '记录二｜距离：当人群靠近，移动方向会改变；所谓异常可能只是避让。'
       UNION ALL SELECT '野生邻居观察局',3,'反光站 · 公园水体','水面会制造第二个影像。',
              '上海市长宁区中山公园水体周边开放步道','121.417420','31.222220','OS02-观察记录：水边',
              '一个倒影不能成为两份证据','观察 3/5：不下水、不跨围挡、不投喂。',
              '记录三｜反光：单一影像可能重复自己，异常结论需要独立证据。'
       UNION ALL SELECT '野生邻居观察局',4,'隐蔽站 · 林缘步道','真正的行为常在镜头放下后出现。',
              '上海市长宁区中山公园林缘开放步道','121.415900','31.221380','OS02-观察记录：灌木边界',
              '不打扰，才看得到真实行为','观察 4/5：只从步道观察，不翻动落叶或伸手进入灌木。',
              '记录四｜边界：隐蔽空间不是待打开的盒子，它首先是别的生命的家。'
       UNION ALL SELECT '野生邻居观察局',5,'归档站 · 公园终点','决定这次异常报告应该如何归类。',
              '上海市长宁区中山公园公共开放区域','121.419120','31.219980','OS02-观察结论：谁才异常',
              '也许需要校正的是观察者','观察 5/5：完成结论选择，不给真实物种贴危险标签。',
              '记录五｜结论：保持距离、承认证据不足，本身就是更成熟的城市观察。'

       UNION ALL SELECT '异常事务局：不存在的坐标',1,'A-00/1 · 工业遗存锚','监测画面不断丢失建筑的一条边。',
              '上海市徐汇区龙腾大道3398号龙美术馆西岸馆外部','121.464753','31.183987','OS03-A00：编号恢复',
              '编号曾被覆盖，载体仍在','收容 1/5：在开放区域恢复第一位编号。',
              'A-00 证据一｜编号：数字可以被删除，承载它的结构仍会留下缺口。'
       UNION ALL SELECT '异常事务局：不存在的坐标',2,'A-00/2 · 西岸边界','数字地图持续向东偏移十一米。',
              '上海市徐汇区龙腾大道2600号西岸美术馆外部','121.463037','31.171343','OS03-A00：边界校准',
              '地图不是唯一真相','收容 2/5：沿开放步道确认现实边界。',
              'A-00 证据二｜边界：当数字坐标偏移，脚下的道路成为校准现实的标尺。'
       UNION ALL SELECT '异常事务局：不存在的坐标',3,'A-00/3 · 厂房回声','录音末尾出现来自明天的一次呼吸。',
              '上海市徐汇区龙腾大道2555号西岸艺术中心外部','121.461573','31.167389','OS03-A00：回声采样',
              '你已经来过——但时间戳在明天','收容 3/5：只观察厂房结构，不敲击设施。',
              'A-00 证据三｜回声：原声结束后，多出来的那一秒来自尚未发生的时间。'
       UNION ALL SELECT '异常事务局：不存在的坐标',4,'A-00/4 · 容器外壁','系统认为建筑内部保存着一个坐标。',
              '上海市徐汇区龙腾大道2380号油罐艺术中心外部','121.465553','31.164543','OS03-A00：容器确认',
              '容器保存的不是物体','收容 4/5：按现场开放规则观察建筑外部。',
              'A-00 证据四｜容器：有些空间收藏物品，有些空间收藏一个不断重写的位置。'
       UNION ALL SELECT '异常事务局：不存在的坐标',5,'A-00/5 · 江面最终坐标','五个坐标在水面重叠，等待最终协议。',
              '上海市徐汇区油罐艺术公园江岸上层公共步道','121.466668','31.163976','OS03-A00：最终收容',
              '坐标已稳定 60 秒','收容 5/5：远离危险临水位置，完成处置选择。',
              'A-00 证据五｜误差：异常并非一个地点，而是人确认现实后留下的微小偏差。'

       UNION ALL SELECT '末日广播：最后一座中继站',1,'频道 1 · 陆家嘴入口信号','没有目的地的列车提示仍在播报。',
              '上海市浦东新区陆家嘴地铁站 2 号口地面（明珠广场方向）','121.503465','31.237852','OS04-中继恢复：交通频道',
              '先恢复城市的路径','广播 1/5：只在地面完成，不在通道或站台停留。',
              '频道一｜交通：即使目的地消失，城市仍用路线证明自己能够抵达。'
       UNION ALL SELECT '末日广播：最后一座中继站',2,'频道 2 · 中心绿地环境信号','湿度数据被错误标成海平面以下的雨。',
              '上海市浦东新区陆家嘴环路717号中心绿地','121.503880','31.238170','OS04-中继恢复：水源频道',
              '环境数据重新上线','广播 2/5：留在开放步道，不进入水体或养护区域。',
              '频道二｜环境：水与植物保存的不是物资清单，而是城市仍在呼吸的证据。'
       UNION ALL SELECT '末日广播：最后一座中继站',3,'频道 3 · 东方明珠能源信号','黑屏上第一次出现一条上升的信号柱。',
              '上海市浦东新区世纪大道1号东方明珠外部广场','121.499710','31.239947','OS04-中继恢复：能源频道',
              '城市轮廓正在重新点亮','广播 3/5：只在公共广场活动，不接触设备。',
              '频道三｜能源：亮起的不是一座塔，而是失联城市重新获得的轮廓。'
       UNION ALL SELECT '末日广播：最后一座中继站',4,'频道 4 · 滨江避难信号','许多陌生人只留下江水声和一句“我还在”。',
              '上海市浦东新区滨江大道公共步道','121.497920','31.237020','OS04-中继恢复：避难频道',
              '公共空间仍可抵达','广播 4/5：远离危险临水位置，恶劣天气停止任务。',
              '频道四｜彼此：城市恢复之前，人们先用一句报平安确认对方仍然存在。'
       UNION ALL SELECT '末日广播：最后一座中继站',5,'频道 5 · 最后发射窗口','带宽只够发送最后一条完整消息。',
              '上海市浦东新区滨江大道2777号浦东美术馆外部','121.496433','31.238319','OS04-中继恢复：最后广播',
              '最后一次广播由你决定','广播 5/5：完成剧情选择；这不是现实应急信息。',
              '频道五｜选择：末日故事真正留下的不是恐惧，而是你决定优先让谁被听见。'

       UNION ALL SELECT '时间污染：被删除的今天',1,'锚点 1 · 缺页目录','所有书都少了同一天的一页。',
              '上海市徐汇区淮海中路1555号上海图书馆外部','121.445372','31.208442','OS05-时间锚点：书页',
              '空白位置也能证明内容存在过','修复 1/5：可在外部完成，不要求预约入馆。',
              '锚点一｜书页：目录保存了缺失的位置，空白因此成为最明确的证据。'
       UNION ALL SELECT '时间污染：被删除的今天',2,'锚点 2 · 黑石倒影','照片写着正午，窗里却映出夜空。',
              '上海市徐汇区复兴中路1331号黑石M+公共园区','121.452467','31.211000','OS05-时间锚点：倒影',
              '影像与时间戳正在冲突','修复 2/5：只在公共园区活动，不进入住宅。',
              '锚点二｜倒影：时间戳可以撒谎，但同一扇窗不该同时拥有两个天空。'
       UNION ALL SELECT '时间污染：被删除的今天',3,'锚点 3 · 缺失小节','所有乐器同时跳过了同一分钟。',
              '上海市徐汇区复兴中路1380号上海交响音乐厅外部','121.450000','31.210180','OS05-时间锚点：乐声',
              '全城达成了一次整齐的遗忘','修复 3/5：不外放音频，不妨碍演出通行。',
              '锚点三｜乐声：沉默并非没有声音，而是所有声音在同一刻选择缺席。下一站约需步行 25–30 分钟，可在安全地点短暂休息。'
       UNION ALL SELECT '时间污染：被删除的今天',4,'锚点 4 · 逆向树影','那一天的影子全部朝着不可能的方向。',
              '上海市黄浦区雁荡路105号复兴公园','121.469115','31.216980','OS05-时间锚点：树影',
              '太阳重新承认那一天存在过','修复 4/5：留在步道或允许停留处观察树影。',
              '锚点四｜树影：城市可以改写钟表，却很难让每棵树同时忘记太阳。'
       UNION ALL SELECT '时间污染：被删除的今天',5,'锚点 5 · 双重门牌','两个版本的今天都在同一条街上等待。',
              '上海市黄浦区复兴中路523弄思南公馆公共街区','121.468956','31.214509','OS05-时间锚点：门牌',
              '只能保留一条时间线','修复 5/5：完成最终选择，室内空间按各自规则进入。',
              '锚点五｜选择：被删除的一天没有发生灾难，只发生了另一种可能的人生。';

INSERT INTO cms_topic_node
       (topic_id, chapter_id, name, description, address, longitude, latitude,
        template_id, checkin_code, business_time, status, sort_id, xp_value,
        hook_text, card_hook_long, fragment_text, create_by, create_time)
SELECT t.id, c.id, seed.node_name, seed.node_desc, seed.address,
       seed.longitude, seed.latitude, m.id, REPLACE(UUID(),'-',''),
       CASE WHEN seed.topic_name='时间污染：被删除的今天'
            THEN '10:00-18:00' ELSE '09:00-18:00' END, 1,
       seed.sort_id, 20, seed.hook_text, seed.card_hook_long, seed.fragment_text,
       'seed_official_solo_story_20260823', NOW()
  FROM seed_official_solo_story_nodes seed
  JOIN cms_topic t
    ON t.member_id=100000 AND t.name=seed.topic_name AND t.del_flag=0
  JOIN cms_topic_chapter c
    ON c.topic_id=t.id AND c.sort_id=1
  JOIN cms_member_template m
    ON m.member_id=100000 AND m.title=seed.template_title AND m.del_flag=0
 WHERE NOT EXISTS (
       SELECT 1 FROM cms_topic_node n
        WHERE n.topic_id=t.id AND n.name=seed.node_name
 );

-- 明确写入逐站前置；mode=1 自身还会按 sort_id 做线性硬闸，两层保持一致。
DROP TEMPORARY TABLE IF EXISTS seed_official_solo_story_unlocks;
CREATE TEMPORARY TABLE seed_official_solo_story_unlocks (
  node_id BIGINT NOT NULL PRIMARY KEY,
  previous_node_id BIGINT NOT NULL
) ENGINE=InnoDB;

INSERT INTO seed_official_solo_story_unlocks (node_id, previous_node_id)
SELECT current_node.id, previous_node.id
  FROM cms_topic_node current_node
  JOIN cms_topic topic ON topic.id=current_node.topic_id
  JOIN cms_topic_node previous_node
    ON previous_node.topic_id=current_node.topic_id
   AND previous_node.sort_id=current_node.sort_id-1
 WHERE topic.member_id=100000
   AND topic.name IN ('城市藏宝猎人','野生邻居观察局','异常事务局：不存在的坐标',
                      '末日广播：最后一座中继站','时间污染：被删除的今天')
   AND current_node.sort_id>1;

UPDATE cms_topic_node current_node
JOIN seed_official_solo_story_unlocks seed_unlock
  ON seed_unlock.node_id=current_node.id
SET current_node.unlock_after_node_id=seed_unlock.previous_node_id,
    current_node.update_by='seed_official_solo_story_20260823',
    current_node.update_time=NOW()
WHERE current_node.id=seed_unlock.node_id;

DROP TEMPORARY TABLE IF EXISTS seed_official_solo_story_unlocks;

-- ======================= 五、事务内 fail-closed 回读 =======================

DROP TEMPORARY TABLE IF EXISTS seed_official_solo_story_guard;
CREATE TEMPORARY TABLE seed_official_solo_story_guard (id INT NOT NULL PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO seed_official_solo_story_guard (id) VALUES (1);

-- 任一主题重复、结构缺失、误公开、误存成模板、误绑定商户，都用主键冲突让事务失败。
INSERT INTO seed_official_solo_story_guard (id)
SELECT 1
 WHERE (SELECT COUNT(*)
          FROM cms_topic t
         WHERE t.member_id=100000 AND t.del_flag=0
           AND t.name IN ('城市藏宝猎人','野生邻居观察局','异常事务局：不存在的坐标',
                          '末日广播：最后一座中继站','时间污染：被删除的今天'))<>5;

INSERT INTO seed_official_solo_story_guard (id)
SELECT 1
 WHERE EXISTS (
       SELECT t.name
         FROM cms_topic t
        WHERE t.member_id=100000 AND t.del_flag=0
          AND t.name IN ('城市藏宝猎人','野生邻居观察局','异常事务局：不存在的坐标',
                         '末日广播：最后一座中继站','时间污染：被删除的今天')
        GROUP BY t.name
       HAVING COUNT(*)<>1
 );

INSERT INTO seed_official_solo_story_guard (id)
SELECT 1
 WHERE EXISTS (
       SELECT t.id
         FROM cms_topic t
         LEFT JOIN cms_topic_chapter c ON c.topic_id=t.id
         LEFT JOIN cms_topic_node n ON n.topic_id=t.id AND n.status=1
        WHERE t.member_id=100000 AND t.del_flag=0
          AND t.name IN ('城市藏宝猎人','野生邻居观察局','异常事务局：不存在的坐标',
                         '末日广播：最后一座中继站','时间污染：被删除的今天')
        GROUP BY t.id,t.is_template,t.product_type,t.user_status,t.self_play,t.lifecycle,
                 t.self_play_price,t.self_play_quota,t.img_url
       HAVING COUNT(DISTINCT c.id)<>1 OR COUNT(DISTINCT n.id)<>5
          OR t.is_template<>0 OR t.product_type<>1 OR t.user_status<>0 OR t.lifecycle<>0
          OR t.self_play<>1 OR t.self_play_price<>19.90 OR t.self_play_quota<>100
          OR t.img_url NOT LIKE 'https://www.chengyinhub.com/prod-api/profile/topic-covers/%.webp'
 );

INSERT INTO seed_official_solo_story_guard (id)
SELECT 1
 WHERE EXISTS (
       SELECT t.id
         FROM cms_topic t
         JOIN cms_topic_node n ON n.topic_id=t.id AND n.status=1
         JOIN cms_member_template m ON m.id=n.template_id AND m.del_flag=0
        WHERE t.member_id=100000 AND t.del_flag=0
          AND t.name IN ('城市藏宝猎人','野生邻居观察局','异常事务局：不存在的坐标',
                         '末日广播：最后一座中继站','时间污染：被删除的今天')
       GROUP BY t.id
       HAVING COUNT(DISTINCT m.id)<>5
          OR SUM(CASE WHEN m.validation_method<>5 THEN 1 ELSE 0 END)<>0
          OR SUM(CASE WHEN m.advanced_config_json IS NOT NULL THEN 1 ELSE 0 END)<>5
          OR SUM(CASE WHEN JSON_LENGTH(m.advanced_config_json, '$.branch.steps[0].options')=3
                      THEN 1 ELSE 0 END)<>1
 );

INSERT INTO seed_official_solo_story_guard (id)
SELECT 1
 WHERE EXISTS (
       SELECT t.id
         FROM cms_topic t
         LEFT JOIN product_role r ON r.topic_id=t.id AND r.status=1
        WHERE t.member_id=100000 AND t.del_flag=0
          AND t.name IN ('城市藏宝猎人','野生邻居观察局','异常事务局：不存在的坐标',
                         '末日广播：最后一座中继站','时间污染：被删除的今天')
        GROUP BY t.id
       HAVING COUNT(r.id)<>1
          OR SUM(CASE WHEN r.slot='CONTENT' AND r.entity_type='PLATFORM'
                            AND r.entity_id IS NULL THEN 1 ELSE 0 END)<>1
 );

INSERT INTO seed_official_solo_story_guard (id)
SELECT 1
 WHERE EXISTS (
       SELECT n.id
         FROM cms_topic_node n
         JOIN cms_topic t ON t.id=n.topic_id
        WHERE t.member_id=100000
          AND t.name IN ('城市藏宝猎人','野生邻居观察局','异常事务局：不存在的坐标',
                         '末日广播：最后一座中继站','时间污染：被删除的今天')
          AND (n.merchant_member_id IS NOT NULL
               OR n.longitude IS NULL OR n.latitude IS NULL
               OR n.business_time<>(CASE WHEN t.name='时间污染：被删除的今天'
                                         THEN '10:00-18:00' ELSE '09:00-18:00' END)
               OR (n.sort_id>1 AND n.unlock_after_node_id IS NULL))
 );

-- 幂等回放不得把“行已存在”当成“值正确”：对种子期望值做逐层反向回读。
INSERT INTO seed_official_solo_story_guard (id)
SELECT 1
 WHERE EXISTS (
       SELECT seed.name
         FROM seed_official_solo_story_topics seed
         LEFT JOIN cms_topic t
           ON t.member_id=100000 AND t.name=seed.name AND t.del_flag=0
        WHERE t.id IS NULL
           OR NOT (t.subtitle <=> seed.subtitle)
           OR NOT (t.description <=> seed.description)
           OR NOT (t.address_name <=> seed.address_name)
           OR NOT (t.total_time <=> seed.total_time)
           OR NOT (t.total_mileage <=> seed.total_mileage)
           OR NOT (t.img_url <=> seed.img_url)
           OR NOT (t.finish_medal_name <=> seed.medal_name)
 );

INSERT INTO seed_official_solo_story_guard (id)
SELECT 1
 WHERE EXISTS (
       SELECT seed.title
         FROM seed_official_solo_story_games seed
         LEFT JOIN cms_member_template m
           ON m.member_id=100000 AND m.title=seed.title AND m.del_flag=0
        WHERE m.id IS NULL
           OR NOT (m.description <=> seed.description)
           OR NOT (m.usage_location <=> seed.usage_location)
           OR NOT (m.duration <=> seed.duration)
           OR NOT (m.difficulty <=> seed.difficulty)
           OR NOT (m.rule_instructions <=> seed.rule_instructions)
           OR NOT (m.feedback_text <=> seed.feedback_text)
           OR NOT (m.story_text <=> seed.story_text)
           OR NOT (m.advanced_config_json <=> seed.advanced_config_json)
           OR m.validation_method<>5
 );

INSERT INTO seed_official_solo_story_guard (id)
SELECT 1
 WHERE EXISTS (
       SELECT seed.topic_name, seed.sort_id
         FROM seed_official_solo_story_nodes seed
         JOIN cms_topic t
           ON t.member_id=100000 AND t.name=seed.topic_name AND t.del_flag=0
         LEFT JOIN cms_topic_node n
           ON n.topic_id=t.id AND n.name=seed.node_name AND n.status=1
         LEFT JOIN cms_member_template m
           ON m.id=n.template_id AND m.del_flag=0
         LEFT JOIN cms_topic_node previous_node
           ON previous_node.topic_id=t.id AND previous_node.status=1
          AND previous_node.sort_id=seed.sort_id-1
        WHERE n.id IS NULL
           OR NOT (n.description <=> seed.node_desc)
           OR NOT (n.address <=> seed.address)
           OR NOT (n.longitude <=> seed.longitude)
           OR NOT (n.latitude <=> seed.latitude)
           OR NOT (n.sort_id <=> seed.sort_id)
           OR NOT (n.hook_text <=> seed.hook_text)
           OR NOT (n.card_hook_long <=> seed.card_hook_long)
           OR NOT (n.fragment_text <=> seed.fragment_text)
           OR NOT (m.title <=> seed.template_title)
           OR NOT (n.business_time <=> CASE WHEN seed.topic_name='时间污染：被删除的今天'
                                             THEN '10:00-18:00' ELSE '09:00-18:00' END)
           OR (seed.sort_id=1 AND n.unlock_after_node_id IS NOT NULL)
           OR (seed.sort_id>1 AND (previous_node.id IS NULL
                                   OR NOT (n.unlock_after_node_id <=> previous_node.id)))
 );

DROP TEMPORARY TABLE IF EXISTS seed_official_solo_story_topics;
DROP TEMPORARY TABLE IF EXISTS seed_official_solo_story_games;
DROP TEMPORARY TABLE IF EXISTS seed_official_solo_story_nodes;
DROP TEMPORARY TABLE IF EXISTS seed_official_solo_story_guard;
