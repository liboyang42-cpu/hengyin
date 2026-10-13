-- runner: requires=transaction
-- ============================================================================
-- 上海单人自由探索主题内容种子（5 主题 / 5 章节 / 5 轻玩法 / 20 固定地点）
--
-- 产品边界：
--   · 只增加现有主题模板数据，不修改模板发布页、主题详情页或复制流程；
--   · product_type=2（FREE_EXPLORE），玩家自己出发，地点和商家均无需入驻、配置、
--     接待、扫码或跨店协调；
--   · 每个主题提供 4 个可选地点，完成逻辑由现有“到点即完成”玩法承载；
--   · 状态固定 EXPERIMENTAL / evidence_level=NONE。未经过真实试走，不得伪装为 VERIFIED；
--     因现有公共货架只展示 VERIFIED，本种子落库后仍需运营试走和晋级才能公开出现。
--
-- 图片：本文件落库时用的是仓库实拍图（来源与许可见
--   docs/image-sources/shanghai-solo-theme-covers-20260822.md）。
--   ⚠️ 2026-08-25 起这 5 张封面已由 migration_20260822_template_cover_images.sql
--   换成自绘场景 key art，并同批删掉 description 末尾的「封面摄影：…」署名。
--   本文件是历史种子（NOT EXISTS 幂等），重跑不会把实拍封面写回来。
--
-- 幂等键：cms_topic(member_id=100000,name)；其余实体以主题 + 名称判断。
-- 纯 DML，不在本文件创建或修改表结构。
-- ============================================================================

-- ======================= 一、5 个主题 =======================

INSERT INTO cms_topic
       (member_id, topic_type, product_type, name, subtitle, description,
        address_name, total_time, location_count, template_count, img_url,
        is_template, status, user_status, merchant_status, lifecycle, del_flag,
        create_by, create_time)
SELECT 100000, 1, 2, '风味巡游', '上海老味道的四种证词',
       '用街巷、食品货架、点心与熟食认识上海味道。四处任选三处，不要求一天吃四顿；每处只需观察、拍照或认真尝一口。地点按普通访客规则进入，商家无需配置或接待。封面摄影：Thiago Hirai，Wikimedia Commons，CC BY 2.0。',
       '上海市黄浦区', 14400, 4, 1,
       'https://www.chengyinhub.com/prod-api/profile/topic-covers/flavor-tour.webp',
       1, 1, 0, 0, 0, 0, 'seed_shanghai_solo_20260822', NOW()
  FROM sys_category anchor
 WHERE anchor.id=3
   AND NOT EXISTS (SELECT 1 FROM cms_topic t
                    WHERE t.member_id=100000 AND t.name='风味巡游' AND t.del_flag=0);

INSERT INTO cms_topic
       (member_id, topic_type, product_type, name, subtitle, description,
        address_name, total_time, location_count, template_count, img_url,
        is_template, status, user_status, merchant_status, lifecycle, del_flag,
        create_by, create_time)
SELECT 100000, 1, 2, '不惑之年', '把一天还给现在的自己',
       '主要写给38—49岁，也欢迎任何想暂时从工作与家庭角色里退后一步的人。不讲中年危机，只看书、走路、拍下现在的自己；四处任选三处，随时可以停下。封面摄影：纳瓦拉的亨利，Wikimedia Commons，CC BY-SA 4.0。',
       '上海市徐汇区、黄浦区', 14400, 4, 1,
       'https://www.chengyinhub.com/prod-api/profile/topic-covers/midlife-day.webp',
       1, 1, 0, 0, 0, 0, 'seed_shanghai_solo_20260822', NOW()
  FROM sys_category anchor
 WHERE anchor.id=3
   AND NOT EXISTS (SELECT 1 FROM cms_topic t
                    WHERE t.member_id=100000 AND t.name='不惑之年' AND t.del_flag=0);

INSERT INTO cms_topic
       (member_id, topic_type, product_type, name, subtitle, description,
        address_name, total_time, location_count, template_count, img_url,
        is_template, status, user_status, merchant_status, lifecycle, del_flag,
        create_by, create_time)
SELECT 100000, 1, 2, '预制人生', '今天偏离标准答案一次',
       '把通勤、城市模型、百货和步行街重新看一遍。不是批评标准化生活，而是主动选择一次今天的版本；四处任选三处，不购物也可以完成。封面摄影：钉钉，Wikimedia Commons，CC BY-SA 4.0。',
       '上海市人民广场片区', 10800, 4, 1,
       'https://www.chengyinhub.com/prod-api/profile/topic-covers/prebuilt-life.webp',
       1, 1, 0, 0, 0, 0, 'seed_shanghai_solo_20260822', NOW()
  FROM sys_category anchor
 WHERE anchor.id=3
   AND NOT EXISTS (SELECT 1 FROM cms_topic t
                    WHERE t.member_id=100000 AND t.name='预制人生' AND t.del_flag=0);

INSERT INTO cms_topic
       (member_id, topic_type, product_type, name, subtitle, description,
        address_name, total_time, location_count, template_count, img_url,
        is_template, status, user_status, merchant_status, lifecycle, del_flag,
        create_by, create_time)
SELECT 100000, 1, 2, '高温末日逃生', '浦东四座室内方舟',
       '用一点末日幽默，把科技、文明、知识和城市全景装进四座室内方舟。四处任选三处，建议跨天完成；它不是应急避难或降温保证，极端天气请遵从官方提示。封面摄影：Empire Du Milieu，Wikimedia Commons，CC0 1.0。',
       '上海市浦东新区', 21600, 4, 1,
       'https://www.chengyinhub.com/prod-api/profile/topic-covers/heat-escape.webp',
       1, 1, 0, 0, 0, 0, 'seed_shanghai_solo_20260822', NOW()
  FROM sys_category anchor
 WHERE anchor.id=3
   AND NOT EXISTS (SELECT 1 FROM cms_topic t
                    WHERE t.member_id=100000 AND t.name='高温末日逃生' AND t.del_flag=0);

INSERT INTO cms_topic
       (member_id, topic_type, product_type, name, subtitle, description,
        address_name, total_time, location_count, template_count, img_url,
        is_template, status, user_status, merchant_status, lifecycle, del_flag,
        create_by, create_time)
SELECT 100000, 1, 2, '上海像一部旧电影', '沿苏州河寻找四个镜头',
       '老屠宰场、邮政大楼、仓库和纺织厂房组成四个镜头。不是历史考试，只需要认真看一眼城市留下的结构；四行仓库节点保持庄重，不做娱乐化摆拍。封面摄影：Chainwit.，Wikimedia Commons，CC BY 4.0。',
       '上海市苏州河沿线', 18000, 4, 1,
       'https://www.chengyinhub.com/prod-api/profile/topic-covers/old-movie.webp',
       1, 1, 0, 0, 0, 0, 'seed_shanghai_solo_20260822', NOW()
  FROM sys_category anchor
 WHERE anchor.id=3
   AND NOT EXISTS (SELECT 1 FROM cms_topic t
                    WHERE t.member_id=100000 AND t.name='上海像一部旧电影' AND t.del_flag=0);

-- 内容槽始终归平台：这些是平台选择并维护的固定城市地点，不招募商家占位。
INSERT INTO product_role (topic_id, slot, entity_type, entity_id, status)
SELECT t.id, 'CONTENT', 'PLATFORM', NULL, 1
  FROM cms_topic t
 WHERE t.member_id=100000 AND t.del_flag=0
   AND t.name IN ('风味巡游','不惑之年','预制人生','高温末日逃生','上海像一部旧电影')
   AND NOT EXISTS (SELECT 1 FROM product_role r
                    WHERE r.topic_id=t.id AND r.slot='CONTENT' AND r.status=1);

-- ======================= 二、模板成熟度元数据 =======================

INSERT INTO cms_topic_template_meta
       (topic_id, template_status, version, applicable_roles,
        audience_who, audience_moment, audience_need, player_promise, core_loop,
        evidence_level, risk_tags, risk_qualifications, risk_cancellation,
        risk_liability, risk_safety_notice, preview_before, preview_during,
        preview_after, author)
SELECT t.id, 'EXPERIMENTAL', 'mvp-20260822', 'AGENT',
       CASE t.name
         WHEN '风味巡游' THEN '喜欢城市味道、老字号和一个人探店的人'
         WHEN '不惑之年' THEN '38—49岁或任何想暂时独处、重新看见自己的人'
         WHEN '预制人生' THEN '对通勤、算法推荐和标准化生活有共鸣的人'
         WHEN '高温末日逃生' THEN '夏季希望减少户外停留、喜欢室内文化空间的人'
         ELSE '喜欢建筑、摄影和上海城市记忆的人' END,
       CASE t.name
         WHEN '风味巡游' THEN '想用半天或多个零碎时段认识上海味道时'
         WHEN '不惑之年' THEN '需要从责任和日程中给自己留出半天时'
         WHEN '预制人生' THEN '感觉每天都在走标准路线，想主动偏离一次时'
         WHEN '高温末日逃生' THEN '炎热季节仍想独自探索大型室内空间时'
         ELSE '想用镜头理解上海旧建筑与当代生活时' END,
       '不用组队、不等组织者、不要求商家配合，也能获得清楚、有主题感的城市探索清单。',
       '从4个固定地点任选3个，以普通访客身份完成观察、拍照、选择、短写或正常消费中的一个轻动作。',
       CONCAT_WS(CHAR(10),'选择主题并查看4个地点','自行决定顺序与日期','到点后完成一个轻动作','任意完成3处即结束'),
       'NONE',
       '开放时间变化,临时闭馆或售罄,票务与预约,天气与客流,公共空间拍摄边界',
       '地点均按普通访客规则进入；不要求任何商家、场馆或员工事先知情、接待或验证。',
       '临时关闭、约满、售罄或排队超过个人上限时直接跳过该点，改选主题内其他地点；出发前查看场地方当日公告。',
       'Questify 不担保开放、票务、库存、服务质量或实时客流；购票、预约、消费与退款均按场地方规则处理。',
       CASE t.name
         WHEN '高温末日逃生' THEN '这不是应急避难服务。高温预警或官方建议减少非必要外出时不要专程参加；地点之间自行选择合适交通。'
         WHEN '上海像一部旧电影' THEN '四行仓库为纪念空间，保持庄重；只拍允许的建筑与公共空间，不拍其他访客，不做娱乐化摆拍。'
         WHEN '预制人生' THEN '人民广场地铁站只作正常通行节点；不为任务停留、逆行或拍摄他人。'
         ELSE '只在现场允许时拍摄，不拍工作人员和其他访客；不方便拍摄时改为留一个关键词。' END,
       '先确认日期、天气、开放、票务与预约规则；4处只需任选3处，顺序不限，可跨天。',
       '以普通访客身份完成节点文字中的一个轻动作；不向员工出示任务，不索取额外服务。',
       '完成3处后结束；分享、消费、公开发布照片均不是完成条件。',
       '平台内容组'
  FROM cms_topic t
 WHERE t.member_id=100000 AND t.del_flag=0
   AND t.name IN ('风味巡游','不惑之年','预制人生','高温末日逃生','上海像一部旧电影')
   AND NOT EXISTS (SELECT 1 FROM cms_topic_template_meta m WHERE m.topic_id=t.id);

-- ======================= 三、每个主题 1 个章节 =======================

INSERT INTO cms_topic_chapter
       (topic_id, name, description, sort_id, location_count, template_count,
        required, recruit_enabled, max_merchant, terms_mode, create_by, create_time)
SELECT t.id, '上海自由探索',
       '4个固定地点任选3个。地点不招募、不绑定商户、不要求工作人员确认；玩家按自己的日期、顺序和节奏完成。',
       1, 4, 1, 1, 0, 0, 'TRAFFIC', 'seed_shanghai_solo_20260822', NOW()
  FROM cms_topic t
 WHERE t.member_id=100000 AND t.del_flag=0
   AND t.name IN ('风味巡游','不惑之年','预制人生','高温末日逃生','上海像一部旧电影')
   AND NOT EXISTS (SELECT 1 FROM cms_topic_chapter c
                    WHERE c.topic_id=t.id AND c.name='上海自由探索');

-- ======================= 四、每个主题 1 个零配置轻玩法 =======================

INSERT INTO cms_member_template
       (member_id, title, description, players, usage_location, required_materials,
        duration, difficulty, is_sync, rule_instructions, validation_method,
        status, publish_status, draft_status, check_status, del_flag, create_by, create_time)
SELECT 100000, CONCAT('上海单人探索·', t.name),
       '抵达固定地点后，从节点说明中的轻动作任选一个完成；不需要商家或场馆工作人员配合。',
       '1人', '上海公开地点或正常营业空间', '手机（拍照为可选，不要求公开）',
       10, '简单', 1,
       '玩家动作：出发前确认开放规则→以普通访客身份到点→任选观察、拍照、选择、短写或正常消费中的一个动作。商家配置：无。员工动作：零。临时关闭、约满、售罄或排队过久时直接跳过该点。',
       0, 1, 1, 1, 1, 0, 'seed_shanghai_solo_20260822', NOW()
  FROM cms_topic t
 WHERE t.member_id=100000 AND t.del_flag=0
   AND t.name IN ('风味巡游','不惑之年','预制人生','高温末日逃生','上海像一部旧电影')
   AND NOT EXISTS (SELECT 1 FROM cms_member_template m
                    WHERE m.member_id=100000
                      AND m.title=CONCAT('上海单人探索·', t.name) AND m.del_flag=0);

-- ======================= 五、20 个固定地点节点 =======================

-- 风味巡游
INSERT INTO cms_topic_node
       (topic_id, chapter_id, name, description, address, longitude, latitude,
        template_id, checkin_code, status, sort_id, xp_value, create_by, create_time)
SELECT t.id, c.id, r.node_name, r.node_desc, r.address, r.longitude, r.latitude,
       m.id, REPLACE(UUID(),'-',''), 1, r.sort_id, 10, 'seed_shanghai_solo_20260822', NOW()
  FROM cms_topic t
  JOIN cms_topic_chapter c ON c.topic_id=t.id AND c.name='上海自由探索'
  JOIN cms_member_template m ON m.member_id=100000 AND m.title='上海单人探索·风味巡游' AND m.del_flag=0
  JOIN (
       SELECT 1 sort_id, '春启新巷·老字号新集群' node_name,
              '开放街区，单店营业以当天为准。任选：①在公共街景里找一块最像老上海的招牌；②从正常营业门店任选一味，用鲜、酥、糯、浓中的一个词形容第一口。' node_desc,
              '上海市黄浦区山西南路、福建中路、江西中路南京东路后街片区' address, '121.477072' longitude, '31.234663' latitude
       UNION ALL SELECT 2, '上海第一食品商店',
              '普通商业空间，可只逛不买。任选：①找到一种自己没吃过的上海食品，只记录名字；②选一排最像城市档案的货架，不便拍摄时只记包装主色。',
              '上海市黄浦区南京东路720号', '121.477072', '31.234663'
       UNION ALL SELECT 3, '国际饭店帆声饼屋',
              '普通零售，可能排队或售罄。任选：①正常购买任一现售点心，记下咬下去像哪一种声音；②只看国际饭店外立面，选择今天为了名气、味道，还是决定跳过。',
              '上海市黄浦区黄河路28号', '121.471400', '31.233915'
       UNION ALL SELECT 4, '光明邨大酒家',
              '普通餐饮零售，可跳过长队。任选：①只买一份真正想吃、适合一个人的现售熟食或点心；②看门头、包装或街景，为这段上海味起一个六字以内标题。',
              '上海市黄浦区淮海中路588号', '121.467447', '31.220472'
  ) r
 WHERE t.name='风味巡游' AND t.member_id=100000 AND t.del_flag=0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n
                    WHERE n.topic_id=t.id AND n.name=r.node_name);

-- 不惑之年
INSERT INTO cms_topic_node
       (topic_id, chapter_id, name, description, address, longitude, latitude,
        template_id, checkin_code, status, sort_id, xp_value, create_by, create_time)
SELECT t.id, c.id, r.node_name, r.node_desc, r.address, r.longitude, r.latitude,
       m.id, REPLACE(UUID(),'-',''), 1, r.sort_id, 10, 'seed_shanghai_solo_20260822', NOW()
  FROM cms_topic t
  JOIN cms_topic_chapter c ON c.topic_id=t.id AND c.name='上海自由探索'
  JOIN cms_member_template m ON m.member_id=100000 AND m.title='上海单人探索·不惑之年' AND m.del_flag=0
  JOIN (
       SELECT 1 sort_id, '上海图书馆淮海路馆' node_name,
              '按图书馆当日入馆规则进入。任选：①只看书名，选一本最像“现在的我”的书；②在允许停留处安静坐十分钟，结束后只选：保留、放下或开始。' node_desc,
              '上海市徐汇区淮海中路1555号' address, '121.445372' longitude, '31.208442' latitude
       UNION ALL SELECT 2, '黑石M+公共园区',
              '只在公共园区活动，不进入住宅内部。任选：①拍一张不露脸的建筑或影子；②在手机里选一首现在比年轻时更懂的歌，不需要现场播放。',
              '上海市徐汇区复兴中路1331号', '121.452467', '31.211000'
       UNION ALL SELECT 3, '思南公馆公共街区',
              '公共街区可正常通行，室内空间各有规则。任选：①拍一扇门、一段楼梯或一棵树，题名《现在的生活》；②记下一个今天想带走的词。',
              '上海市黄浦区复兴中路523弄一带', '121.468956', '31.214509'
       UNION ALL SELECT 4, '复兴公园',
              '公共公园，出入口以当天公告为准。任选：①拍一张“没有任务的人生”，可只拍椅子、树影或自己的鞋；②坐五分钟，选择愿意给自己的：时间、一顿饭、沉默或好看。',
              '上海市黄浦区雁荡路105号', '121.469115', '31.216980'
  ) r
 WHERE t.name='不惑之年' AND t.member_id=100000 AND t.del_flag=0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n
                    WHERE n.topic_id=t.id AND n.name=r.node_name);

-- 预制人生
INSERT INTO cms_topic_node
       (topic_id, chapter_id, name, description, address, longitude, latitude,
        template_id, checkin_code, status, sort_id, xp_value, create_by, create_time)
SELECT t.id, c.id, r.node_name, r.node_desc, r.address, r.longitude, r.latitude,
       m.id, REPLACE(UUID(),'-',''), 1, r.sort_id, 10, 'seed_shanghai_solo_20260822', NOW()
  FROM cms_topic t
  JOIN cms_topic_chapter c ON c.topic_id=t.id AND c.name='上海自由探索'
  JOIN cms_member_template m ON m.member_id=100000 AND m.title='上海单人探索·预制人生' AND m.del_flag=0
  JOIN (
       SELECT 1 sort_id, '上海城市规划展示馆' node_name,
              '按场馆当日票务与开放规则进入。任选：①在城市模型里找一个从没去过的区域，记成“未加载地图”；②选一个最不像自己日常的城市功能：居住、工作、休闲或自然。' node_desc,
              '上海市黄浦区人民大道100号' address, '121.475621' longitude, '31.231299' latitude
       UNION ALL SELECT 2, '人民广场地铁站',
              '只作正常通行节点，不为任务停留、逆行或拍摄。安全出站后任选：①从换乘、出口、排队、等待里选最像系统指令的词；②从不熟悉的开放出口到地面，记下第一种颜色。',
              '上海市黄浦区人民广场地铁站（1、2、8号线）', '121.474539', '31.229381'
       UNION ALL SELECT 3, '上海第一百货商业中心',
              '普通商业空间，可只逛不买。任选：①观察三件常见商品，选一件最不像算法会推荐给自己的；②如果正好吃饭，给一份一人食起名，不消费也不影响完成。',
              '上海市黄浦区南京东路830号', '121.475239', '31.235298'
       UNION ALL SELECT 4, '南京东路步行街西口观察段',
              '开放步行街，客流管控时服从现场安排。任选：①找三组重复出现的视觉元素，拍下或只记住；②用与平常不同的速度安全走五分钟，最后用一个词结束。',
              '上海市黄浦区南京东路与西藏中路交叉口至南京东路830号一带', '121.473903', '31.236111'
  ) r
 WHERE t.name='预制人生' AND t.member_id=100000 AND t.del_flag=0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n
                    WHERE n.topic_id=t.id AND n.name=r.node_name);

-- 高温末日逃生
INSERT INTO cms_topic_node
       (topic_id, chapter_id, name, description, address, longitude, latitude,
        template_id, checkin_code, status, sort_id, xp_value, create_by, create_time)
SELECT t.id, c.id, r.node_name, r.node_desc, r.address, r.longitude, r.latitude,
       m.id, REPLACE(UUID(),'-',''), 1, r.sort_id, 10, 'seed_shanghai_solo_20260822', NOW()
  FROM cms_topic t
  JOIN cms_topic_chapter c ON c.topic_id=t.id AND c.name='上海自由探索'
  JOIN cms_member_template m ON m.member_id=100000 AND m.title='上海单人探索·高温末日逃生' AND m.del_flag=0
  JOIN (
       SELECT 1 sort_id, '上海科技馆' node_name,
              '实名购票场馆，开放与票务以当天公告为准。任选：①在常设展里选一种想带去未来的技术；②选择今天方舟最需要的：能源、材料、飞行或身体。' node_desc,
              '上海市浦东新区世纪大道2000号' address, '121.544439' longitude, '31.218820' latitude
       UNION ALL SELECT 2, '上海博物馆东馆',
              '普通展厅与专项空间规则不同，以当天公告为准。任选：①选一件文物作为“文明备份”，写十字以内理由；②找一组最冷或最暖的颜色，不便拍摄时只做颜色选择。',
              '上海市浦东新区世纪大道1952号', '121.539847', '31.219460'
       UNION ALL SELECT 3, '上海图书馆东馆',
              '公共图书馆，各区域开放规则不同。任选：①选一本书名作为《末日生存手册》的副标题；②选择方舟最先装入的：知识、故事、地图或音乐。',
              '上海市浦东新区迎春路300号', '121.546553', '31.220728'
       UNION ALL SELECT 4, '上海之巅观光厅',
              '实名购票、高价可选节点，开放与天气影响以官方当天信息为准。任选：①从高处找一条像撤离线的道路，只拍城市景观；②选择城市最后保留的能力：连接、记忆、食物或创造。',
              '上海市浦东新区银城中路501号上海中心大厦', '121.504502', '31.234216'
  ) r
 WHERE t.name='高温末日逃生' AND t.member_id=100000 AND t.del_flag=0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n
                    WHERE n.topic_id=t.id AND n.name=r.node_name);

-- 上海像一部旧电影
INSERT INTO cms_topic_node
       (topic_id, chapter_id, name, description, address, longitude, latitude,
        template_id, checkin_code, status, sort_id, xp_value, create_by, create_time)
SELECT t.id, c.id, r.node_name, r.node_desc, r.address, r.longitude, r.latitude,
       m.id, REPLACE(UUID(),'-',''), 1, r.sort_id, 10, 'seed_shanghai_solo_20260822', NOW()
  FROM cms_topic t
  JOIN cms_topic_chapter c ON c.topic_id=t.id AND c.name='上海自由探索'
  JOIN cms_member_template m ON m.member_id=100000 AND m.title='上海单人探索·上海像一部旧电影' AND m.del_flag=0
  JOIN (
       SELECT 1 sort_id, '1933老场坊' node_name,
              '历史建筑园区，公开时段出发当天再确认。任选：①在允许进入的公共区域拍一张廊桥或几何交错构图；②用黑白模式拍一张，起一部不存在的电影名。' node_desc,
              '上海市虹口区溧阳路611号' address, '121.491561' longitude, '31.254543' latitude
       UNION ALL SELECT 2, '上海邮政博物馆',
              '限定开放日，非开放日可只观察邮政大楼公共外观或改选其他地点。任选：①选一件最想寄给未来的展品，只写收件年份；②选择今天最想留下的：信、包裹、声音或地址。',
              '上海市虹口区天潼路395号2楼', '121.485539', '31.244509'
       UNION ALL SELECT 3, '上海四行仓库抗战纪念馆及外墙',
              '纪念空间，保持庄重，不使用闯关或娱乐化表达。任选：①不影响通行，拍下旧墙与今天城市生活的同框；②只记录一个名字或一句展陈信息，安静读完再离开。',
              '上海市静安区光复路21号', '121.471017', '31.240078'
       UNION ALL SELECT 4, 'M50创意园',
              '艺术园区，各展览空间有自己的开放规则。任选：①拍一张老厂房装进新内容的照片；②任选一处公开展览或公共艺术，只用一个词写下感受。',
              '上海市普陀区莫干山路50号', '121.449072', '31.247132'
  ) r
 WHERE t.name='上海像一部旧电影' AND t.member_id=100000 AND t.del_flag=0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n
                    WHERE n.topic_id=t.id AND n.name=r.node_name);

-- ======================= 六、落库后只读回读 =======================
-- 事务 runner 禁止在 --transaction 文件里混入可执行 SELECT，因此回读需在迁移提交后
-- 由执行人单独运行。主题必须保持 EXPERIMENTAL/NONE；完成真实试走后才可另行晋级。
--
-- SELECT
--   (SELECT COUNT(*) FROM cms_topic
--     WHERE create_by='seed_shanghai_solo_20260822' AND del_flag=0) AS topics,
--   (SELECT COUNT(*) FROM cms_topic_template_meta tm JOIN cms_topic t ON t.id=tm.topic_id
--     WHERE t.create_by='seed_shanghai_solo_20260822' AND t.del_flag=0
--       AND tm.del_flag=0 AND tm.template_status='EXPERIMENTAL'
--       AND tm.evidence_level='NONE') AS experimental_meta,
--   (SELECT COUNT(*) FROM cms_topic_chapter c JOIN cms_topic t ON t.id=c.topic_id
--     WHERE t.create_by='seed_shanghai_solo_20260822' AND t.del_flag=0) AS chapters,
--   (SELECT COUNT(*) FROM cms_member_template
--     WHERE create_by='seed_shanghai_solo_20260822' AND del_flag=0
--       AND validation_method=0) AS games,
--   (SELECT COUNT(*) FROM cms_topic_node n JOIN cms_topic t ON t.id=n.topic_id
--     WHERE t.create_by='seed_shanghai_solo_20260822' AND t.del_flag=0
--       AND n.template_id IS NOT NULL AND n.address IS NOT NULL
--       AND n.longitude IS NOT NULL AND n.latitude IS NOT NULL) AS nodes;
-- 期望：5 / 5 / 5 / 5 / 20；重复执行后数量不增加。

-- ======================= 回滚（仅在明确授权时执行） =======================
-- DELETE n FROM cms_topic_node n JOIN cms_topic t ON t.id=n.topic_id
--  WHERE t.create_by='seed_shanghai_solo_20260822';
-- DELETE FROM cms_member_template WHERE create_by='seed_shanghai_solo_20260822';
-- DELETE c FROM cms_topic_chapter c JOIN cms_topic t ON t.id=c.topic_id
--  WHERE t.create_by='seed_shanghai_solo_20260822';
-- DELETE tm FROM cms_topic_template_meta tm JOIN cms_topic t ON t.id=tm.topic_id
--  WHERE t.create_by='seed_shanghai_solo_20260822';
-- DELETE r FROM product_role r JOIN cms_topic t ON t.id=r.topic_id
--  WHERE t.create_by='seed_shanghai_solo_20260822';
-- DELETE FROM cms_topic WHERE create_by='seed_shanghai_solo_20260822';
