-- runner: requires=transaction
-- ============================================================================
-- 主题模板货架种子 ——《今天值得庆祝｜一件小事的3小时城市仪式》(后于游戏种子)
--
-- 内容真源(逐字段可回溯):
--   ~/Documents/城瘾/文档整理_20260711/05-参考资料/归档_20260803/状态终审_原04/
--   Questify_今天值得庆祝_一件小事的3小时城市仪式_即插即用自由探索模板_20260724.md
--
-- ⚠️ 前置依赖:migration_seed_celebrate_games_20260821.sql 已落库(G0—G7 八个玩法模板)。
--
-- 字段来源对照:
--   §1.1 一句话承诺 → player_promise;§1.2 触发时刻 + §1.3 玩家范围 → audience_*;
--   §4.1 首局流程 → core_loop 六段 + preview_*;§2 角色表 → 四章;
--   §2.1/§9.2 排他互补 → 章节 exclusivity;§0.3/§7 商家合同 → 章节 effort_*;
--   §8.3 资料TTL + §12 安全隐私 → risk_*。
--
-- 品类映射(单品类模型的取舍,记录在案):
--   先聚一下(START)=美食烹饪(咖啡/面包自然适配,§2「只有自然适配才显示」,required=0)
--   一起做一件事(TOGETHER)=娱乐达人(手作/桌游/密室/展览最近类;保龄攀岩在运动健身,
--     单品类模型下取主类,跨类商家走后台改章品类)  坐下来庆祝(LAND)=美食烹饪
--   留下记号(MARK)=礼品纪念(本文件新增:照相亭/花店/书店小物/即时打印)
--
-- ★ 状态刻意 EXPERIMENTAL / evidence_level=NONE:文档自身 E0、无试点;换真实内容也不得
--   自称 VERIFIED,真实试点跑完由运营手动改。
-- ★ 幂等:幂等键 = cms_topic.name='今天值得庆祝' / 品类(type=1,name) / (chapter_id,node name)。
-- ★ 纯 DML,InnoDB,单表锚点/单 JOIN 形状,必须带 --transaction 执行。
-- ============================================================================


-- ======================= 一、受控品类:礼品纪念 =======================
INSERT INTO sys_category (parent_id, category_name, type, level, sort_id, icon,
                          status, del_flag, create_by, create_time)
SELECT 0, '礼品纪念', 1, 0, 8,
       'https://xianghuioss.oss-cn-shanghai.aliyuncs.com/chenyin/c1.png',
       1, 0, 'seed_celebrate_topic_20260821', NOW()
  FROM sys_category anchor
 WHERE anchor.id = 3
   AND NOT EXISTS (SELECT 1 FROM sys_category c
        WHERE c.type = 1 AND c.category_name = '礼品纪念' AND c.del_flag = 0);


-- ======================= 二、主题本体 =======================
-- product_type=2(FREE_EXPLORE):§4.2「在线计划完成即完成,不组织统一活动日」;
-- 章节招商只对自由探索开放。total_time=480 秒:§5.1「首局 5—8 分钟生成 READY_TO_CHECK」。
INSERT INTO cms_topic (member_id, topic_type, product_type, name, subtitle, description,
                       address_name, total_time, location_count, is_template,
                       status, user_status, del_flag, create_by, create_time)
SELECT 100000, 1, 2,
       '今天值得庆祝',
       '一件小事的3小时城市仪式',
       '一件小事终于完成了,大家觉得「应该庆祝一下」,却不知道怎样把它变成一个不费力、不过度消费、真的能共同记住的城市仪式。玩家先用一句私人标签认领今天为什么值得庆祝,再把同行人的时间、预算、步行量、气氛偏好和不能接受的条件放到同一块仪式板上;系统用本地商家真实的营业、价格、时长、人数、预约和位置条件,拼出一套静态条件可检查的主方案和失效备选。在线生成方案即完成,不要求当天逐店到访、消费、扫码或让店员确认。',
       '本地生活圈(按玩家选择的片区,不要求精确住址)',
       480, 4, 1,
       1, 1, 0, 'seed_celebrate_topic_20260821', NOW()
  FROM sys_category anchor
 WHERE anchor.id = 3
   AND NOT EXISTS (SELECT 1 FROM cms_topic WHERE name = '今天值得庆祝' AND del_flag = 0);


-- ======================= 三、内容槽:声明归平台 =======================
INSERT INTO product_role (topic_id, slot, entity_type, entity_id, status)
SELECT t.id, 'CONTENT', 'PLATFORM', NULL, 1
  FROM cms_topic t
 WHERE t.name = '今天值得庆祝' AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM product_role r
        WHERE r.topic_id = t.id AND r.slot = 'CONTENT' AND r.status = 1);


-- ======================= 四、主题级模板元数据 =======================
INSERT INTO cms_topic_template_meta (
        topic_id, template_status, version, applicable_roles,
        audience_who, audience_moment, audience_need,
        player_promise, core_loop, evidence_level,
        risk_tags, risk_qualifications, risk_cancellation, risk_liability, risk_safety_notice,
        preview_before, preview_during, preview_after, author)
SELECT t.id,
       'EXPERIMENTAL',
       'v1.0',
       -- §7 商家承接 + §2.2 俱乐部准入(常规开放时段可进 TOGETHER)+ 城市代理人可复制
       'MERCHANT,AGENT,CLUB',
       -- §1.3
       '已经认识的朋友、同事、伴侣或家人;适用于两人、3—4人和5—8人熟人小组,也可以一个人庆祝。不要求婚礼、亲子、宠物、健身或某种兴趣身份;不匹配陌生人,不要求公开社交身份。',
       -- §1.2 触发时刻
       '完成一个拖了很久的小目标;第一次做成以前不敢做的事;熬过一段辛苦的工作或学习;好友久别重逢;搬家、入职、项目结束、考试完成或康复阶段结束;不是生日和节日,但今天就是想认真说一句「做得不错」。',
       -- §1.4
       '从「一句改天庆祝+很多商家链接+没人真正安排下来」,变成「一个被认真命名的小完成+一个主方案+角色备选+清楚的时间/预算/路程+可以直接行动的仪式卡」。',
       -- §1.1 一句话承诺(原文)
       '帮助已经认识的朋友、同事、伴侣或家人,在几分钟内把「一件小事值得庆祝」变成一套三小时内静态条件可检查、能够继续取得同行人确认和外部预约的城市仪式方案。',
       -- §4.1 首局流程压缩成六段
       CONCAT_WS(CHAR(10),
           'G0认领庆祝:从六类普通小完成中选「今天为什么值得庆祝」,写一个不含隐私的私人标签',
           '定下约束:一个人还是一起、片区、120—210分钟时段、每人预算和一个最重要限制;G1收集每人一个必须有和一个不能有',
           '打开一个角色:系统只放2—4个满足硬条件的候选,时间、预算、容量或路线冲突直接显示原因',
           '另一角色接力:读取已有选择,只展示仍能组合的候选;G3时间板/G4预算/G5路线逐条检验',
           'G6主选掉线演练:关闭或挪走一个主选,30秒内用备选修复计划;静态条件通过进入READY_TO_CHECK',
           'G7共同落款:60秒生成私密纪念卡与可编辑邀请;分享、全员确认、预约、消费和到店均非通关条件'),
       'NONE',
       -- §12.2 + §12.3
       '高风险运动年龄能力边界,食品过敏披露,酒精与未成年人隔离,多人匿名表态隐私,精确位置隐私',
       -- §12.3 本地准入矩阵 + §2.2
       '每个城市实例发布前必须明确:活动/运动资质、食品披露、酒精年龄、未成年人规则、消费者投诉、地图与天气数据许可、广告披露、位置和群组数据保存期限;缺少当地依据或责任人时,对应角色或服务类型不开放。俱乐部只有使用既有常规开放时段/公开课程/自助预约、玩家沿用现有报名流程、完成不依赖教练领队实时回复时才能进入。',
       -- §8.3 资料TTL + §8.4 故障降级
       '营业/开放/时长/容量30天复核,60天未确认退出静态可检查候选;价格30天未确认只显示待确认不参与精确预算;预约/电话链接失效即隐藏行动按钮。商家暂停即换同角色备选并重算时间板;同角色不足两个有效候选该角色不公开发布。',
       -- §3.4 + §8.3
       '「营业」不能冒充实时有位,「公开价格」不能冒充最终报价,「可预约」不能冒充已经预留;READY_TO_CHECK 只代表静态条件可组合,BOOKED 只在玩家提供外部预约凭证后出现,Questify 不担保履约、退款或服务质量。',
       -- §12
       '高风险运动必须显示年龄、能力、装备、教练和免责边界;未成年人、酒精和夜间项目按当地规则隔离;食品商家未明确披露交叉接触控制时不显示「过敏安全」;恶劣天气、交通中断和紧急事件只显示官方入口。同行人的联系方式、关系标签和个体否决项不提供给商家,商家默认看不到 WIN_LABEL 原文。',
       -- §4.1 步骤1—3
       '先从六类普通小完成里认领「今天为什么值得庆祝」,选好片区、时段、每人预算和最重要的限制;可以独立代填同行人偏好,也可以生成匿名一键选择链接——邀请不是完成条件。',
       -- §4.1 步骤4—7
       '系统先打开 TOGETHER 或 LAND 任一角色,只放2—4个满足硬条件的候选;把真实商家卡放进三小时时间板,营业、时长、预算、容量或路线冲突会直接显示原因;另一必需角色只展示仍能组合的候选;终局演练会突然关掉一个主选,要在30秒内用备选把计划修回来。',
       -- §4.1 步骤8—10 + §3.3
       '静态条件通过后进入 READY_TO_CHECK,生成 CELEBRATION CARD:和谁、什么时候、在哪片区、每段时间与人均价格、为什么适合全组、任一商家失效怎么替换、一键行动入口;60秒共同落款为这件小事留下平台内私密纪念。',
       '平台内容组'
  FROM cms_topic t
 WHERE t.name = '今天值得庆祝' AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_template_meta m WHERE m.topic_id = t.id);


-- ======================= 五、四个角色章节(§2) =======================
-- required:TOGETHER/LAND=1(两必需角色),START/MARK=0(公共缓冲/可选纪念)。
-- terms_mode 全 TRAFFIC:§0.3「不开发专属套餐、不提供折扣」⇒ 引流档,perk_min_value=NULL。

INSERT INTO cms_topic_chapter (topic_id, name, description, sort_id,
                               category_id, required, recruit_enabled,
                               terms_mode, max_merchant, create_by, create_time)
SELECT t.id, '先聚一下',
       '人没到齐时在哪里等,不让仪式从催促开始。优先公共点:交通节点、公共空间;只有自然适配时才显示普通咖啡/面包店,只沿用正常营业,不得要求留位、接待或免费服务。写入15—30分钟缓冲和集合入口,不强制消费。',
       1,
       (SELECT c.id FROM sys_category c
         WHERE c.del_flag = 0 AND c.status = 1 AND c.type = 1 AND c.category_name = '美食烹饪'
         ORDER BY c.id LIMIT 1),
       0, 1, 'TRAFFIC', 4, 'seed_celebrate_topic_20260821', NOW()
  FROM cms_topic t
 WHERE t.name = '今天值得庆祝' AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_chapter ch
        WHERE ch.topic_id = t.id AND ch.name = '先聚一下');

INSERT INTO cms_topic_chapter (topic_id, name, description, sort_id,
                               category_id, required, recruit_enabled,
                               terms_mode, max_merchant, create_by, create_time)
SELECT t.id, '一起做一件事',
       '用什么共同体验承接这件小事。适配手作、桌游、保龄、攀岩、舞蹈体验、陶艺、密室、展览、影院、常规开放俱乐部;读取项目内容、人数、时长、价格、年龄/能力要求、预约与开放时段,写入仪式核心动作、开始/结束时间、每人预算、准备和备选。',
       2,
       (SELECT c.id FROM sys_category c
         WHERE c.del_flag = 0 AND c.status = 1 AND c.type = 1 AND c.category_name = '娱乐达人'
         ORDER BY c.id LIMIT 1),
       1, 1, 'TRAFFIC', 6, 'seed_celebrate_topic_20260821', NOW()
  FROM cms_topic t
 WHERE t.name = '今天值得庆祝' AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_chapter ch
        WHERE ch.topic_id = t.id AND ch.name = '一起做一件事');

INSERT INTO cms_topic_chapter (topic_id, name, description, sort_id,
                               category_id, required, recruit_enabled,
                               terms_mode, max_merchant, create_by, create_time)
SELECT t.id, '坐下来庆祝',
       '在哪里把「做得不错」真正说出口。适配正餐、地方菜、轻食、素食友好餐厅、茶馆和社区餐饮;读取营业/截单、用餐时长、价格、人数组合、预约与饮食披露,写入落席时段、价格、饮食适配、行动入口和备选。',
       3,
       (SELECT c.id FROM sys_category c
         WHERE c.del_flag = 0 AND c.status = 1 AND c.type = 1 AND c.category_name = '美食烹饪'
         ORDER BY c.id LIMIT 1),
       1, 1, 'TRAFFIC', 6, 'seed_celebrate_topic_20260821', NOW()
  FROM cms_topic t
 WHERE t.name = '今天值得庆祝' AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_chapter ch
        WHERE ch.topic_id = t.id AND ch.name = '坐下来庆祝');

INSERT INTO cms_topic_chapter (topic_id, name, description, sort_id,
                               category_id, required, recruit_enabled,
                               terms_mode, max_merchant, create_by, create_time)
SELECT t.id, '留下记号',
       '是否还需要一个能带走或保存的记号(可选,不为凑商家强迫购买)。适配自助照相亭、即时打印、花店、书店小物、生活方式礼物;甜品仅在不与落席直接竞争时进入。只使用剩余时间和预算,写入纪念方式、最晚结束时间、预算和备选。',
       4,
       (SELECT c.id FROM sys_category c
         WHERE c.del_flag = 0 AND c.status = 1 AND c.type = 1 AND c.category_name = '礼品纪念'
         ORDER BY c.id LIMIT 1),
       0, 1, 'TRAFFIC', 4, 'seed_celebrate_topic_20260821', NOW()
  FROM cms_topic t
 WHERE t.name = '今天值得庆祝' AND t.del_flag = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_chapter ch
        WHERE ch.topic_id = t.id AND ch.name = '留下记号');


-- ======================= 六、章节级模板元数据(§0.3/§7/§2.1) =======================
-- effort:P90≤15分钟确认预填(0.25h),店员游戏工时0,零新物料零新套餐零跨店协调。

INSERT INTO cms_topic_chapter_template_meta (
        chapter_id, topic_id, role_need,
        exclusivity_mode, exclusivity_scope, exclusivity_period, exclusivity_reason,
        effort_prep_hours, effort_staff_minutes, effort_venue, effort_materials,
        effort_max_concurrent, effort_available_slots, effort_needs_booking,
        effort_cancel_cost, effort_qualifications)
SELECT ch.id, ch.topic_id,
       '只需确认平台预填的正常营业时段与集合适配性(是否欢迎短暂停留)。零必填长文、零留位承诺、零接待义务;公共集合点由平台维护,不占商家角色。',
       'HARD', '同一城市实例内的同一功能角色', '该城市实例存续期间',
       '一家商家在同一城市实例只能认领一个角色;同角色竞争者互为备选,不被安排在同一条计划连续消费;公共方案无商业付费能力,也不能被赞助商家隐藏。',
       0.25, 0, '本店现有空间,只沿用正常营业', '无需新增物料', NULL,
       '按本店真实营业时段', 0,
       '商家可一键暂停;资料过期自动降级退出候选,不产生违约金。',
       '按当地规则持有效经营资质'
  FROM cms_topic_chapter ch
  JOIN cms_topic t ON t.id = ch.topic_id
 WHERE t.name = '今天值得庆祝' AND t.del_flag = 0 AND ch.name = '先聚一下'
   AND NOT EXISTS (SELECT 1 FROM cms_topic_chapter_template_meta cm WHERE cm.chapter_id = ch.id);

INSERT INTO cms_topic_chapter_template_meta (
        chapter_id, topic_id, role_need,
        exclusivity_mode, exclusivity_scope, exclusivity_period, exclusivity_reason,
        effort_prep_hours, effort_staff_minutes, effort_venue, effort_materials,
        effort_max_concurrent, effort_available_slots, effort_needs_booking,
        effort_cancel_cost, effort_qualifications)
SELECT ch.id, ch.topic_id,
       '只需确认平台预填的项目内容、人数、时长、价格、年龄/能力要求、预约方式和开放时段是否仍有效。不写剧情、不出题、不设计路线、不临时开班、不组群、不设起订人数;俱乐部只用既有常规开放时段/公开课程/自助预约。',
       'HARD', '同一城市实例内的同一功能角色', '该城市实例存续期间',
       '一家商家在同一城市实例只能认领一个角色;共同体验的结束时间自动决定落席候选,商家之间不用互相沟通、不共同结算;需要统一集合、领队组织、临时凑团或跨商家排期的内容应建独立活动实例,不得留在本模板。',
       0.25, 0, '本店现有项目场地,无需新增', '无需新增物料;不开发新品、不专门备货', NULL,
       '按本店真实开放/可预约时段', 1,
       '商家可一键暂停;满员未知时保留候选但显示「需确认」,最多进入READY_TO_CHECK。',
       '高风险运动按当地规则持相应资质,必须显示年龄、能力、装备、教练和免责边界;隐藏这些要求即退出候选'
  FROM cms_topic_chapter ch
  JOIN cms_topic t ON t.id = ch.topic_id
 WHERE t.name = '今天值得庆祝' AND t.del_flag = 0 AND ch.name = '一起做一件事'
   AND NOT EXISTS (SELECT 1 FROM cms_topic_chapter_template_meta cm WHERE cm.chapter_id = ch.id);

INSERT INTO cms_topic_chapter_template_meta (
        chapter_id, topic_id, role_need,
        exclusivity_mode, exclusivity_scope, exclusivity_period, exclusivity_reason,
        effort_prep_hours, effort_staff_minutes, effort_venue, effort_materials,
        effort_max_concurrent, effort_available_slots, effort_needs_booking,
        effort_cancel_cost, effort_qualifications)
SELECT ch.id, ch.topic_id,
       '只需确认平台预填的营业/截单时间、常规用餐时长、价格带、可接待人数组合、预约方式和饮食披露是否仍有效。不要求送蛋糕写字、不默认主持庆祝、不提供免费纪念品、不强制点单。',
       'HARD', '同一城市实例内的同一功能角色', '该城市实例存续期间',
       '一家商家在同一城市实例只能认领一个角色;落席预约或截单冲突由系统换方案,餐厅不用联系体验商家;同角色竞争者互为备选。',
       0.25, 0, '本店现有堂食区域,无需新增场地', '无需新增物料;不开发新品、不专门备货', NULL,
       '按本店真实营业与截单时段', 0,
       '商家可一键暂停;截单未知时不进入目标时段主选。',
       '按当地规则持有效食品经营许可;未明确披露交叉接触控制时,平台不得显示「过敏安全」'
  FROM cms_topic_chapter ch
  JOIN cms_topic t ON t.id = ch.topic_id
 WHERE t.name = '今天值得庆祝' AND t.del_flag = 0 AND ch.name = '坐下来庆祝'
   AND NOT EXISTS (SELECT 1 FROM cms_topic_chapter_template_meta cm WHERE cm.chapter_id = ch.id);

INSERT INTO cms_topic_chapter_template_meta (
        chapter_id, topic_id, role_need,
        exclusivity_mode, exclusivity_scope, exclusivity_period, exclusivity_reason,
        effort_prep_hours, effort_staff_minutes, effort_venue, effort_materials,
        effort_max_concurrent, effort_available_slots, effort_needs_booking,
        effort_cancel_cost, effort_qualifications)
SELECT ch.id, ch.topic_id,
       '只需确认平台预填的营业时间、停留时长、价格、年龄/拍摄限制、是否需预约和现有产品/服务。不为凑第三站强迫消费、不默认酒精、不要求公开拍摄陌生人。',
       'HARD', '同一城市实例内的同一功能角色', '该城市实例存续期间',
       '一家商家在同一城市实例只能认领一个角色;纪念角色只使用剩余时间和预算,不挤占必需角色;不足两个有效候选时整个角色关闭,不凑数。',
       0.25, 0, '本店现有空间,无需新增', '无需新增物料', NULL,
       '按本店真实营业时段', 0,
       '商家可一键暂停;资料过期自动降级退出候选。',
       '按当地规则持有效经营资质;涉拍摄的项目须明示拍摄与肖像边界'
  FROM cms_topic_chapter ch
  JOIN cms_topic t ON t.id = ch.topic_id
 WHERE t.name = '今天值得庆祝' AND t.del_flag = 0 AND ch.name = '留下记号'
   AND NOT EXISTS (SELECT 1 FROM cms_topic_chapter_template_meta cm WHERE cm.chapter_id = ch.id);


-- ======================= 七、章节节点接线(§6 角色×游戏匹配) =======================
-- 先聚一下: G0 入口 + G1 全局硬约束
-- 一起做一件事: G3 主 + G2 可选 + G6 终局
-- 坐下来庆祝: G3 主(复用) + G4 可选 + G6 终局
-- 留下记号: G2 主(复用) + G5 可选 + G6 终局 + G7 全局落款(末位)
-- 无坐标、无打卡码 —— 线上完成;纯线上节点 /arrive 记凭证路径已放行(PR #766)。

INSERT INTO cms_topic_node
       (topic_id, chapter_id, name, description, node_time, template_id,
        hook_text, status, sort_id, create_by, create_time)
SELECT ch.topic_id, ch.id, '入口|30秒今天值得什么',
       '从六类普通小完成中认领今天为什么值得庆祝,选好仪式风格。', 1,
       (SELECT m.id FROM cms_member_template m
         WHERE m.member_id = 100000 AND m.title = 'G0｜30秒今天值得什么' AND m.del_flag = 0
         ORDER BY m.id LIMIT 1),
       '先说清楚:今天为什么值得庆祝?', 1, 0,
       'seed_celebrate_topic_20260821', NOW()
  FROM cms_topic_chapter ch
  JOIN cms_topic t ON t.id = ch.topic_id
 WHERE t.name = '今天值得庆祝' AND t.del_flag = 0 AND ch.name = '先聚一下'
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n
        WHERE n.chapter_id = ch.id AND n.name = '入口|30秒今天值得什么');

INSERT INTO cms_topic_node
       (topic_id, chapter_id, name, description, node_time, template_id,
        hook_text, status, sort_id, create_by, create_time)
SELECT ch.topic_id, ch.id, '硬约束|每人一个不能',
       '每位同行人一个必须有+一个不能有,系统只保留交集,不展示谁否决了什么。', 1,
       (SELECT m.id FROM cms_member_template m
         WHERE m.member_id = 100000 AND m.title = 'G1｜每人一个不能' AND m.del_flag = 0
         ORDER BY m.id LIMIT 1),
       '把每个人的底线放上桌,但不说是谁的。', 1, 1,
       'seed_celebrate_topic_20260821', NOW()
  FROM cms_topic_chapter ch
  JOIN cms_topic t ON t.id = ch.topic_id
 WHERE t.name = '今天值得庆祝' AND t.del_flag = 0 AND ch.name = '先聚一下'
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n
        WHERE n.chapter_id = ch.id AND n.name = '硬约束|每人一个不能');

INSERT INTO cms_topic_node
       (topic_id, chapter_id, name, description, node_time, template_id,
        hook_text, status, sort_id, create_by, create_time)
SELECT ch.topic_id, ch.id, '主游戏|三小时时间方块',
       '把共同体验放入120—210分钟时间板,项目时长、开放时段和预约提前量决定绿黄红。', 3,
       (SELECT m.id FROM cms_member_template m
         WHERE m.member_id = 100000 AND m.title = 'G3｜三小时时间方块' AND m.del_flag = 0
         ORDER BY m.id LIMIT 1),
       '这件事今晚真的排得下吗?', 1, 0,
       'seed_celebrate_topic_20260821', NOW()
  FROM cms_topic_chapter ch
  JOIN cms_topic t ON t.id = ch.topic_id
 WHERE t.name = '今天值得庆祝' AND t.del_flag = 0 AND ch.name = '一起做一件事'
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n
        WHERE n.chapter_id = ch.id AND n.name = '主游戏|三小时时间方块');

INSERT INTO cms_topic_node
       (topic_id, chapter_id, name, description, node_time, template_id,
        hook_text, status, sort_id, create_by, create_time)
SELECT ch.topic_id, ch.id, '可选|气氛卡碰撞',
       '把「热闹、松弛、动手、新鲜、安静」气氛卡与候选真实项目匹配。', 2,
       (SELECT m.id FROM cms_member_template m
         WHERE m.member_id = 100000 AND m.title = 'G2｜气氛卡碰撞' AND m.del_flag = 0
         ORDER BY m.id LIMIT 1),
       '今晚的气氛,和哪个项目真的合拍?', 1, 1,
       'seed_celebrate_topic_20260821', NOW()
  FROM cms_topic_chapter ch
  JOIN cms_topic t ON t.id = ch.topic_id
 WHERE t.name = '今天值得庆祝' AND t.del_flag = 0 AND ch.name = '一起做一件事'
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n
        WHERE n.chapter_id = ch.id AND n.name = '可选|气氛卡碰撞');

INSERT INTO cms_topic_node
       (topic_id, chapter_id, name, description, node_time, template_id,
        hook_text, status, sort_id, create_by, create_time)
SELECT ch.topic_id, ch.id, '终局|主选突然掉线',
       '模拟满员/临时停业/下雨/超时,30秒内用备选修复计划并保持硬条件成立。', 1,
       (SELECT m.id FROM cms_member_template m
         WHERE m.member_id = 100000 AND m.title = 'G6｜主选突然掉线' AND m.del_flag = 0
         ORDER BY m.id LIMIT 1),
       '主选突然满员,你的计划还立得住吗?', 1, 2,
       'seed_celebrate_topic_20260821', NOW()
  FROM cms_topic_chapter ch
  JOIN cms_topic t ON t.id = ch.topic_id
 WHERE t.name = '今天值得庆祝' AND t.del_flag = 0 AND ch.name = '一起做一件事'
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n
        WHERE n.chapter_id = ch.id AND n.name = '终局|主选突然掉线');

INSERT INTO cms_topic_node
       (topic_id, chapter_id, name, description, node_time, template_id,
        hook_text, status, sort_id, create_by, create_time)
SELECT ch.topic_id, ch.id, '主游戏|三小时时间方块',
       '把落席时段放进时间板:营业、截单、用餐时长与前一段的路线共同决定可用性。', 3,
       (SELECT m.id FROM cms_member_template m
         WHERE m.member_id = 100000 AND m.title = 'G3｜三小时时间方块' AND m.del_flag = 0
         ORDER BY m.id LIMIT 1),
       '做完那件事,哪家还来得及坐下来?', 1, 0,
       'seed_celebrate_topic_20260821', NOW()
  FROM cms_topic_chapter ch
  JOIN cms_topic t ON t.id = ch.topic_id
 WHERE t.name = '今天值得庆祝' AND t.del_flag = 0 AND ch.name = '坐下来庆祝'
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n
        WHERE n.chapter_id = ch.id AND n.name = '主游戏|三小时时间方块');

INSERT INTO cms_topic_node
       (topic_id, chapter_id, name, description, node_time, template_id,
        hook_text, status, sort_id, create_by, create_time)
SELECT ch.topic_id, ch.id, '可选|每人预算筹码',
       '把总预算分给共同体验、落席和纪念;真实价格带与起订人数决定筹码去向。', 2,
       (SELECT m.id FROM cms_member_template m
         WHERE m.member_id = 100000 AND m.title = 'G4｜每人预算筹码' AND m.del_flag = 0
         ORDER BY m.id LIMIT 1),
       '人均预算怎么分,才不会最后一段吃不起?', 1, 1,
       'seed_celebrate_topic_20260821', NOW()
  FROM cms_topic_chapter ch
  JOIN cms_topic t ON t.id = ch.topic_id
 WHERE t.name = '今天值得庆祝' AND t.del_flag = 0 AND ch.name = '坐下来庆祝'
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n
        WHERE n.chapter_id = ch.id AND n.name = '可选|每人预算筹码');

INSERT INTO cms_topic_node
       (topic_id, chapter_id, name, description, node_time, template_id,
        hook_text, status, sort_id, create_by, create_time)
SELECT ch.topic_id, ch.id, '终局|主选突然掉线',
       '模拟满员/截单/临时停业,30秒内用备选修复落席方案。', 1,
       (SELECT m.id FROM cms_member_template m
         WHERE m.member_id = 100000 AND m.title = 'G6｜主选突然掉线' AND m.del_flag = 0
         ORDER BY m.id LIMIT 1),
       '这家突然截单了,备选还接得住吗?', 1, 2,
       'seed_celebrate_topic_20260821', NOW()
  FROM cms_topic_chapter ch
  JOIN cms_topic t ON t.id = ch.topic_id
 WHERE t.name = '今天值得庆祝' AND t.del_flag = 0 AND ch.name = '坐下来庆祝'
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n
        WHERE n.chapter_id = ch.id AND n.name = '终局|主选突然掉线');

INSERT INTO cms_topic_node
       (topic_id, chapter_id, name, description, node_time, template_id,
        hook_text, status, sort_id, create_by, create_time)
SELECT ch.topic_id, ch.id, '主游戏|气氛卡碰撞',
       '纪念方式与今晚气氛匹配:热闹选即时打印,安静选一支花或一本书。', 2,
       (SELECT m.id FROM cms_member_template m
         WHERE m.member_id = 100000 AND m.title = 'G2｜气氛卡碰撞' AND m.del_flag = 0
         ORDER BY m.id LIMIT 1),
       '要不要带一个记号回家?', 1, 0,
       'seed_celebrate_topic_20260821', NOW()
  FROM cms_topic_chapter ch
  JOIN cms_topic t ON t.id = ch.topic_id
 WHERE t.name = '今天值得庆祝' AND t.del_flag = 0 AND ch.name = '留下记号'
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n
        WHERE n.chapter_id = ch.id AND n.name = '主游戏|气氛卡碰撞');

INSERT INTO cms_topic_node
       (topic_id, chapter_id, name, description, node_time, template_id,
        hook_text, status, sort_id, create_by, create_time)
SELECT ch.topic_id, ch.id, '可选|十分钟步行上限',
       '蒙住商家名字,只看路线时间和无障碍条件先淘汰,再揭晓。', 2,
       (SELECT m.id FROM cms_member_template m
         WHERE m.member_id = 100000 AND m.title = 'G5｜十分钟步行上限' AND m.del_flag = 0
         ORDER BY m.id LIMIT 1),
       '最后一段,别让路程毁了兴致。', 1, 1,
       'seed_celebrate_topic_20260821', NOW()
  FROM cms_topic_chapter ch
  JOIN cms_topic t ON t.id = ch.topic_id
 WHERE t.name = '今天值得庆祝' AND t.del_flag = 0 AND ch.name = '留下记号'
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n
        WHERE n.chapter_id = ch.id AND n.name = '可选|十分钟步行上限');

INSERT INTO cms_topic_node
       (topic_id, chapter_id, name, description, node_time, template_id,
        hook_text, status, sort_id, create_by, create_time)
SELECT ch.topic_id, ch.id, '终局|主选突然掉线',
       '纪念角色失效不救场:正常生成主方案,不凑数。', 1,
       (SELECT m.id FROM cms_member_template m
         WHERE m.member_id = 100000 AND m.title = 'G6｜主选突然掉线' AND m.del_flag = 0
         ORDER BY m.id LIMIT 1),
       '记号没了也不慌,主方案照样成立。', 1, 2,
       'seed_celebrate_topic_20260821', NOW()
  FROM cms_topic_chapter ch
  JOIN cms_topic t ON t.id = ch.topic_id
 WHERE t.name = '今天值得庆祝' AND t.del_flag = 0 AND ch.name = '留下记号'
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n
        WHERE n.chapter_id = ch.id AND n.name = '终局|主选突然掉线');

INSERT INTO cms_topic_node
       (topic_id, chapter_id, name, description, node_time, template_id,
        hook_text, status, sort_id, create_by, create_time)
SELECT ch.topic_id, ch.id, '落款|60秒共同落款',
       '每人选一个提示、一张结构化短句卡,合成私密纪念卡与可编辑邀请;落款不发给商家。', 1,
       (SELECT m.id FROM cms_member_template m
         WHERE m.member_id = 100000 AND m.title = 'G7｜60秒共同落款' AND m.del_flag = 0
         ORDER BY m.id LIMIT 1),
       '最后一分钟,认真说一句「做得不错」。', 1, 3,
       'seed_celebrate_topic_20260821', NOW()
  FROM cms_topic_chapter ch
  JOIN cms_topic t ON t.id = ch.topic_id
 WHERE t.name = '今天值得庆祝' AND t.del_flag = 0 AND ch.name = '留下记号'
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n
        WHERE n.chapter_id = ch.id AND n.name = '落款|60秒共同落款');


-- ======================= 八、主骨架指 G0 =======================
UPDATE cms_topic_template_meta m
  JOIN cms_topic t ON t.id = m.topic_id
   SET m.main_skeleton_template_id = (
       SELECT tpl.id FROM cms_member_template tpl
        WHERE tpl.member_id = 100000 AND tpl.title = 'G0｜30秒今天值得什么' AND tpl.del_flag = 0
        ORDER BY tpl.id LIMIT 1)
 WHERE t.name = '今天值得庆祝' AND t.del_flag = 0
   AND m.main_skeleton_template_id IS NULL;


-- ======================= 落库后回读验证(「回执 ≠ 观测」) =======================
-- 1) 幂等证据 —— 重复执行后都不能变:
--   SELECT (SELECT COUNT(*) FROM cms_topic WHERE name='今天值得庆祝' AND del_flag=0)   AS topics,
--          (SELECT COUNT(*) FROM cms_topic_chapter ch JOIN cms_topic t ON t.id=ch.topic_id
--            WHERE t.name='今天值得庆祝')                                              AS chapters,
--          (SELECT COUNT(*) FROM cms_topic_chapter_template_meta cm
--             JOIN cms_topic t ON t.id=cm.topic_id WHERE t.name='今天值得庆祝')        AS chapter_metas,
--          (SELECT COUNT(*) FROM cms_topic_node n JOIN cms_topic t ON t.id=n.topic_id
--            WHERE t.name='今天值得庆祝')                                              AS nodes,
--          (SELECT COUNT(*) FROM cms_topic_node n JOIN cms_topic t ON t.id=n.topic_id
--            WHERE t.name='今天值得庆祝' AND (n.template_id IS NULL OR n.template_id=0)) AS dangling;
--   -- 期望:1 / 4 / 4 / 12 / 0
-- 2) 四章品类全非空(哪行 NULL 哪章招商段静默不下发):
--   SELECT ch.name, ch.required, ch.category_id, c.category_name FROM cms_topic_chapter ch
--     JOIN cms_topic t ON t.id=ch.topic_id LEFT JOIN sys_category c ON c.id=ch.category_id
--    WHERE t.name='今天值得庆祝' ORDER BY ch.sort_id;
-- 3) 端到端:POST /api/template/topic-template/list ⇒ 含「今天值得庆祝」EXPERIMENTAL。


-- ======================= 回滚 =======================
-- DELETE FROM cms_topic_node WHERE create_by='seed_celebrate_topic_20260821';
-- DELETE cm FROM cms_topic_chapter_template_meta cm
--   JOIN cms_topic t ON t.id=cm.topic_id WHERE t.name='今天值得庆祝';
-- DELETE ch FROM cms_topic_chapter ch JOIN cms_topic t ON t.id=ch.topic_id WHERE t.name='今天值得庆祝';
-- DELETE m FROM cms_topic_template_meta m JOIN cms_topic t ON t.id=m.topic_id WHERE t.name='今天值得庆祝';
-- DELETE r FROM product_role r JOIN cms_topic t ON t.id=r.topic_id WHERE t.name='今天值得庆祝';
-- DELETE FROM cms_topic WHERE name='今天值得庆祝';
-- DELETE FROM sys_category WHERE create_by='seed_celebrate_topic_20260821';
