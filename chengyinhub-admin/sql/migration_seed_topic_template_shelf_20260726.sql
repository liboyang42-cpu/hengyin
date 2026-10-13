-- ============================================================================
-- 主题模板货架种子数据 ——《生活不掉线｜6分钟补一个15分钟Plan B》
--
-- 内容真源(不是自编示例,逐字段可回溯):
--   ~/Documents/城瘾/文档整理_20260711/04-未开始/
--   Questify_生活不掉线_大众即插即用主题与游戏模板_20260724.md(939 行,V1.2,2026-07-24)
--
-- 字段来源对照:
--   §1.1 一句话承诺        → player_promise
--   §1.4 核心人群 + §1.2 情境 → audience_who / audience_moment / audience_need
--   §2.1 四个固定功能角色   → 四个章节 EAT / WEAR / HOME / MOVE
--   §2.3 角色互补规则       → 章节 exclusivity_*
--   §4.1 玩家标准旅程       → core_loop 六段 + preview_before/during/after
--   §7.2 DRAFT_READY 闸门   → 章节 effort_*
--   §12  安全信任责任边界   → risk_*
--
-- ⚠️ 前置依赖:必须先执行 migration_topic_template_meta_20260726.sql(建两张附表)。
-- ⚠️ 本文件只准备,不执行。生产库写操作由总控在用户点头后执行。
--
-- ★ 幂等:每条 INSERT 均为 INSERT ... SELECT ... WHERE NOT EXISTS,重复执行不插重复行。
--   幂等键 = cms_topic.name = '生活不掉线'。
--
-- ★ 状态刻意标 EXPERIMENTAL / evidence_level = NONE:
--   文档自身写明「文档状态:04-未开始(产品设计稿,尚未完成真实居民与商家试点)」
--   「证据成熟度:E0 / Pre-pilot」。指南第十三节要求未过入库门槛的只能标实验模板,
--   第十四节把「展示确定收益但没有样本量」列为失去商业可信度的做法。
--   ⇒ 换成真实内容之后依然不能自称 VERIFIED。真实试点跑完再由运营手动改这两个字段。
-- ============================================================================


-- ======================= 第一步:只读预检 =======================
-- 1) 前置迁移是否已执行(期望两行):
--   SELECT TABLE_NAME FROM information_schema.TABLES
--    WHERE TABLE_SCHEMA = DATABASE()
--      AND TABLE_NAME IN ('cms_topic_template_meta', 'cms_topic_chapter_template_meta');
--
-- 2) 货架现状(本种子未灌时应查不到这条):
--   SELECT id, name, status, user_status, is_template FROM cms_topic
--    WHERE name = '生活不掉线' AND del_flag = 0;
--
-- 3) ⚠️ 四个角色能不能落到真实受控品类上 —— 决定招商段是否可见。
--    ChapterRecruitmentRules 要求章节 category_id 指向启用中的受控品类,否则整段不下发。
--   SELECT 'EAT' AS role, id, category_name FROM sys_category
--    WHERE del_flag = 0 AND status = 1 AND type = 1 AND (category_name LIKE '%餐%' OR category_name LIKE '%食%')
--   UNION ALL SELECT 'WEAR', id, category_name FROM sys_category
--    WHERE del_flag = 0 AND status = 1 AND type = 1 AND (category_name LIKE '%洗%' OR category_name LIKE '%衣%')
--   UNION ALL SELECT 'HOME', id, category_name FROM sys_category
--    WHERE del_flag = 0 AND status = 1 AND type = 1 AND (category_name LIKE '%家政%' OR category_name LIKE '%维修%' OR category_name LIKE '%居%')
--   UNION ALL SELECT 'MOVE', id, category_name FROM sys_category
--    WHERE del_flag = 0 AND status = 1 AND type = 1 AND (category_name LIKE '%出行%' OR category_name LIKE '%车%' OR category_name LIKE '%交通%');
--   -- ⚠️ 哪个角色查不到品类,下面对应章节的 category_id 就会是 NULL,
--   --    该章节招商段在详情页整段不下发(不报错、静默为空)。
--   --    缺哪个就先去后台配哪个品类,别改闸。


-- ======================= 第二步:种子数据 =======================

-- --- 2.1 主题本体 -----------------------------------------------------------
-- product_type = 2(FREE_EXPLORE 自由探索):文档头部「产品模式:主题自由探索」。
-- 章节招商只对自由探索开放,这个值也是招商段可见的前提。
-- member_id = 100000 沿用平台自营主题的既有约定;真正表达「官方内容」的是 2.2 的 CONTENT 槽。
INSERT INTO cms_topic (member_id, topic_type, product_type, name, subtitle, description,
                       address_name, total_time, location_count, is_template,
                       status, user_status, del_flag, create_by, create_time)
SELECT 100000, 1, 2,
       '生活不掉线',
       '6分钟补一个15分钟Plan B',
       '帮助本地生活者在几分钟内,为一个常用生活锚点的吃饭、衣物、居住和出行各留下一个有时效、可解释、可直接行动的备用答案,让日常小问题发生时不再从零搜索。首局只从当前一个问题进入,完成一个角色加一次「首选失效」演练即可得到独立可用的Plan B,另外三类以后按需补齐。',
       '本地生活圈(按玩家选择的片区,不要求精确住址)',
       360, 4, 1,
       1, 1, 0, 'seed_life_backed_up_20260726', NOW()
  FROM DUAL
 WHERE NOT EXISTS (
       SELECT 1 FROM cms_topic WHERE name = '生活不掉线' AND del_flag = 0);
-- total_time = 360 秒 = 6 分钟:§4.1 第7步「目标总时长不超过6分钟」。
-- location_count = 4:四个功能角色,不是四个必须到访的点位(§4.4 不要求逐店到访)。


-- --- 2.2 内容槽:声明归平台 -------------------------------------------------
-- 缺这一行,货架/广场卡片的「来源」栏就是空的(历史待归属),不会显示「官方」。
-- entity_type='PLATFORM' 时 entity_id 必须为 NULL(库里 ck_role_entity_id 有 CHECK)。
INSERT INTO product_role (topic_id, slot, entity_type, entity_id, status)
SELECT t.id, 'CONTENT', 'PLATFORM', NULL, 1
  FROM cms_topic t
 WHERE t.name = '生活不掉线' AND t.del_flag = 0
   AND NOT EXISTS (
       SELECT 1 FROM product_role r
        WHERE r.topic_id = t.id AND r.slot = 'CONTENT' AND r.status = 1);


-- --- 2.3 主题级模板元数据 ---------------------------------------------------
INSERT INTO cms_topic_template_meta (
        topic_id, template_status, version, applicable_roles,
        audience_who, audience_moment, audience_need,
        player_promise, core_loop, evidence_level,
        risk_tags, risk_qualifications, risk_cancellation, risk_liability, risk_safety_notice,
        preview_before, preview_during, preview_after, author)
SELECT t.id,
       'EXPERIMENTAL',
       'v1.2',
       -- §0 产品模式「城市代理人可复制」+ §7 商家承接 ⇒ 商家与代理人。
       -- 刻意不含 CLUB:全文没有俱乐部承接这条链路。
       'MERCHANT,AGENT',
       -- §1.4
       '在本地稳定居住的居民;每周在同一片区工作或学习的人;家庭、独居者、合租者、学生、上班族和活跃银发人群;不熟悉附近小店、或只依赖大型平台临时搜索的人。不要求新人、宠物主人、健身爱好者、准新人或游客等任何特殊身份。',
       -- §1.2 四个真实断线情境
       '日常小故障发生的那一刻:午休只剩40分钟却不知道去哪吃;第二天要穿的衣服突然需要处理;门锁或水龙头出现问题;通勤工具在早上失灵。',
       -- §1.2 需求侧 + §0.2 差异点
       '问题发生后临时搜索时信息多、真假难辨、时间紧,难以判断营业时间、服务范围、价格、处理时长是否适合自己。需要的是围绕一个生活锚点、一种出行方式、一个时间窗提前备好、可在问题发生时直接调用的Plan B,而不是多收藏四家店。',
       -- §1.1 一句话承诺(原文)
       '帮助本地生活者在几分钟内,为一个常用生活锚点的吃饭、衣物、居住和出行各留下一个有时效、可解释、可直接行动的备用答案。',
       -- §4.1 标准旅程压缩成六段;顺序即阶段顺序,不可排序、不可丢段。
       -- ⚠️ 第六段刻意只写「按需补齐/真实需要时再打开」而不写「邀请同伴」——
       --    §4.4 明令「不要求关注、加群或公开分享」,写邀请会与模板自身的边界冲突。
       CONCAT_WS(CHAR(10),
           '选定情境与约束:G0先问此刻最想解决哪个小问题,再选生活锚点、出行方式和时间窗',
           '只打开当前一个角色:系统不要求先浏览完整商家目录',
           '真实条件改变候选:只放2—4个满足硬条件的答案进微挑战,营业、路线、价格和服务边界会改变结局',
           '看懂原因并保存首选:在「适合／有条件适合／不适合」中看到为什么,先存一个首选',
           'G6失效演练:系统立即模拟首选暂停、闭店或不可达,玩家要在30秒内切到备选',
           '拿到已备份能力章:生成本角色首选/备选与一键行动入口,其余三类作为空位按需补齐'),
       -- 文档头「证据成熟度:E0 / Pre-pilot」;§15 试点尚未开展。
       'NONE',
       -- §12.2 高风险排除项 + §12.1 资质
       '上门服务,食品与过敏原,骑行与出行维修,资质时效,精确位置隐私',
       -- §12.1 + §12.5
       '商家身份、地址、电话和经营状态必须可核实;食品、维修、上门和出行相关资质按当地规则处理;商家自报、平台核验和政府/第三方资质分别标识。配钥匙、开锁、入户维修和上门服务只按国家/城市准入矩阵开放。每个城市实例发布前必须明确食品披露规则、上门/开锁/维修资质、地图与公共交通数据许可、广告披露、投诉入口、精确位置保存期限和未成年人规则;缺少当地责任人或依据时,对应高风险服务类型不开放。',
       -- §12.1 状态过期降级 + §7.3 一键暂停 + §1.3 商家状态变化自动提醒替换
       '商家可一键暂停;营业与接单状态过期自动降级,不再作为有效答案下发。玩家结果长期保存,商家状态变化后自动提醒替换同角色实例(§2.3:商家退出只替换同角色实例,不改变主题结构)。',
       -- §12.1 + §12.3
       '「平台收录」不等于质量、安全或履约担保。起步价、计价单位、常见区间、包含/不含项和更新时间同时展示,责任方与数据源由平台标明。未真实交易前只能私人记录「是否适合我」,公开履约评价必须来自可验证订单或服务。',
       -- §12.2,对玩家公开的必要项
       '医疗、燃气、电力、消防、结构安全和人身紧急情况不进入本主题;遇到高风险关键词只提供当地官方紧急入口,不推荐普通商家代替。本主题不做维修、食品、健康或出行安全的专业诊断:WEAR不保证所有污渍与材质可修复,EAT不提供营养治疗与过敏医疗建议,MOVE不提供远程维修判断和道路安全保证。',
       -- §4.1 步骤 1—3
       '看到四个轻情境,选出此刻最不想发生的那一种,并定下常活动片区、可达时间和首要考虑;此时还看不到完整商家目录,也不需要注册或定位到精确住址。',
       -- §4.1 步骤 4—6
       '系统只打开当前一个角色,把2—4个满足硬条件的候选放进一个1—3分钟的微挑战;商家真实的营业、路线、价格和服务边界会改变结局,玩家在「适合／有条件适合／不适合」中看懂原因,先保存一个首选,随后被强制演练首选失效。',
       -- §4.1 步骤 7—9 + §4.3
       '拿到「本角色已备份」能力章、一个首选与一个备选、为什么适合自己的可解释理由,以及营业/价格/时长/取送边界和一键导航或原下单页入口;可以立即关闭,其余三类作为空位按需补齐,真实需要发生时直接从备用卡调用。',
       '平台内容组'
  FROM cms_topic t
 WHERE t.name = '生活不掉线' AND t.del_flag = 0
   AND NOT EXISTS (
       SELECT 1 FROM cms_topic_template_meta m WHERE m.topic_id = t.id);
-- ⚠️ main_skeleton_template_id 刻意留 NULL:G0—G6 是插件规格,不是 cms_member_template 里的
--    玩法行,库里没有对应 id。硬填一个会造出悬空外键。详见文件末「未落位内容清单」第 1 条。


-- --- 2.4 四个功能角色章节(§2.1) --------------------------------------------
-- required 的取值有据可依,不是随手填:
--   §2.3「EAT、WEAR、HOME找不到每角色至少两家合格商户时,不公开发布完整主题」⇒ 三者 required=1
--   §2.5「MOVE 是平台公共能力层」,公共方案(步行/公交)本身就是合格答案,不依赖商家供给 ⇒ required=0
-- terms_mode 全部 TRAFFIC(引流档):§7.3「零折扣」、§0.2「不开发新品、不专门备货、不提供免费样品」,
--   商家不付出额外权益,只暴露既有服务 ⇒ 不是 PERK 权益档,perk_min_value 必须为 NULL。
-- category_id 用按名匹配的子查询;匹配不到则为 NULL,该章招商段整段不下发(见预检第3条)。
INSERT INTO cms_topic_chapter (topic_id, name, description, sort_id,
                               category_id, required, recruit_enabled,
                               terms_mode, max_merchant, create_by, create_time)
SELECT t.id, r.chapter_name, r.chapter_desc, r.sort_id,
       (SELECT c.id FROM sys_category c
         WHERE c.del_flag = 0 AND c.status = 1 AND c.type = 1
           AND c.category_name REGEXP r.category_pattern
         ORDER BY c.id LIMIT 1),
       r.required, 1, 'TRAFFIC', r.max_merchant,
       'seed_life_backed_up_20260726', NOW()
  FROM cms_topic t
  JOIN (
        SELECT '吃饭不掉线' AS chapter_name, 1 AS sort_id, 1 AS required, 6 AS max_merchant,
               '餐|食|饭|烘焙|熟食|轻食' AS category_pattern,
               '解决时间有限、预算、饮食偏好与营业时段的问题。适配社区餐厅、早餐、轻食、烘焙、熟食、地方小吃;读取菜单、价格、营业、取餐和饮食说明,写出某时段某预算下可用的首选与备选。' AS chapter_desc
  UNION ALL SELECT '衣物不掉线', 2, 1, 4,
               '洗|衣|裁缝|鞋|修补',
               '解决清洗、修改、修补、鞋包护理的问题。适配洗衣、改衣、裁缝、鞋包修理与护理;读取材质范围、服务项目、价格、时长、取送,写出适用服务、准备清单和处理方式。'
  UNION ALL SELECT '家里不掉线', 3, 1, 4,
               '家政|维修|居|五金|清洁|钥匙',
               '解决清洁、小型维护、配钥匙、日常居住补给的问题。适配家政清洁、五金、配钥匙、家居维修、社区生活服务;读取服务范围、上门区域、价格、预约与明确不服务项,写出可处理／需咨询／不适合与替代入口。'
  UNION ALL SELECT '出行不掉线', 4, 0, 4,
               '出行|车|交通|骑行|停车',
               '解决惯常路线或工具不可用时的安全替代。平台先用公共交通与步行方案给出基础答案,自行车/电动车维修、租赁、充电、停车与本地接驳等商家只作为满足硬条件时的商业候选接入;公共答案无付费能力也必须保留。'
       ) r
 WHERE t.name = '生活不掉线' AND t.del_flag = 0
   AND NOT EXISTS (
       SELECT 1 FROM cms_topic_chapter ch
        WHERE ch.topic_id = t.id AND ch.name = r.chapter_name);


-- --- 2.5 章节级模板元数据(§2.3 排他 + §7.2 商家投入) -----------------------
-- effort_staff_minutes 全部为 0 —— §0.2「店员执行:游戏运行工时为0」、§5.1「人工判分/解释:0」。
-- effort_prep_hours = 0.25(15分钟)—— §0.2 商家收到 DRAFT_READY 预填邀请后 P90≤15分钟;
--   这是「商家核对预填草稿」的时间,不含平台导入与代理核实(文档明确不许拿它冒充总工时)。
INSERT INTO cms_topic_chapter_template_meta (
        chapter_id, topic_id, role_need,
        exclusivity_mode, exclusivity_scope, exclusivity_period, exclusivity_reason,
        effort_prep_hours, effort_staff_minutes, effort_venue, effort_materials,
        effort_max_concurrent, effort_available_slots, effort_needs_booking,
        effort_cancel_cost, effort_qualifications)
SELECT ch.id, ch.topic_id, r.role_need,
       'HARD', '同一生活圈实例内的同一功能角色', '该生活圈实例存续期间',
       '一家商家在同一实例只认领一个角色;综合生活店、商场和大型平台不能同时占据多个核心角色;跨界商家只展示其认领角色中的服务,其他服务不参与主题推荐。同角色商家互为替代项,不强迫玩家连续选择两个同类商家。付费商业位不能改变是否满足用户时间、路线和服务条件的自然判断。',
       0.25, 0, r.venue, r.materials, NULL, r.slots, r.needs_booking,
       '商家可一键暂停;资料过期或营业状态失效将自动降级,不再作为有效答案下发,不产生违约金。',
       r.qualifications
  FROM cms_topic_chapter ch
  JOIN cms_topic t ON t.id = ch.topic_id
  JOIN (
        SELECT '吃饭不掉线' AS chapter_name,
               '只需确认平台预填的菜单/服务项、价格、营业与截单时间、取餐方式和已披露的过敏原信息是否仍有效,并标出不确定项。零必填自由文本、零新套餐、零折扣。' AS role_need,
               '本店现有堂食或取餐区域,无需新增场地' AS venue,
               '无需新增物料;不开发新品、不专门备货、不提供免费样品' AS materials,
               '按本店真实营业与截单时段' AS slots,
               0 AS needs_booking,
               '按当地规则持有效食品经营许可;未明确披露交叉接触控制时,平台不得显示「过敏安全」' AS qualifications
  UNION ALL SELECT '衣物不掉线',
               '只需确认平台预填的材质范围、服务项目、起步价、处理时长和取送条件,标明可做／不可做与时效。不隔空判断污渍或材质,不承诺一定修复。',
               '本店现有收件与作业区域,无需新增场地',
               '无需新增物料',
               '按本店真实营业与收件时段',
               0,
               '按当地规则持有效经营资质;不得要求玩家上传隐私照片'
  UNION ALL SELECT '家里不掉线',
               '只需确认平台预填的服务区域、低风险项目、起步价、预约方式,以及当地要求的经营与人员资质;必须标明明确不服务的项目与风险边界。',
               '上门服务,按本店真实服务半径',
               '无需新增物料',
               '按本店真实接单与上门时段',
               1,
               '按国家/城市准入矩阵持有相应经营与人员资质;配钥匙、开锁、入户维修需核验身份或授权的环节沿用商家合规流程,不进入游戏。燃气、电力、消防与结构安全不在本角色范围内'
  UNION ALL SELECT '出行不掉线',
               '只需确认平台预填的维修/租赁/接驳范围、营业时间、处理时长和安全边界,标明当前可提供范围。⚠️本角色的基础答案由平台公共交通与步行方案提供,商业服务是插件:有赞助也不能越过资格过滤,更不得为招商隐藏更合适的步行或公交方案。',
               '本店现有维修或租赁场地,无需新增',
               '无需新增物料',
               '按本店真实营业时段',
               0,
               '按当地规则持有效经营资质;不提供远程维修判断,不对道路安全作出保证'
       ) r ON r.chapter_name = ch.name
 WHERE t.name = '生活不掉线' AND t.del_flag = 0
   AND NOT EXISTS (
       SELECT 1 FROM cms_topic_chapter_template_meta cm WHERE cm.chapter_id = ch.id);


-- ======================= 第三步:落库后回读验证 =======================
-- ⚠️「回执 ≠ 观测」:执行没报错不代表货架真有东西。下面从库里读回事实。
--
-- 1) 幂等证据 —— 重复执行本文件后这三个数字都不能变:
--   SELECT (SELECT COUNT(*) FROM cms_topic WHERE name='生活不掉线' AND del_flag=0)      AS topics,
--          (SELECT COUNT(*) FROM cms_topic_chapter ch JOIN cms_topic t ON t.id=ch.topic_id
--            WHERE t.name='生活不掉线')                                                  AS chapters,
--          (SELECT COUNT(*) FROM cms_topic_chapter_template_meta cm
--             JOIN cms_topic t ON t.id=cm.topic_id WHERE t.name='生活不掉线')            AS chapter_metas;
--   -- 期望:1 / 4 / 4。第二次执行后仍是 1 / 4 / 4。任一变大即幂等失效,立刻停下排查。
--
-- 2) 来源必须是官方:
--   SELECT r.entity_type, r.entity_id, r.status FROM product_role r
--     JOIN cms_topic t ON t.id = r.topic_id
--    WHERE t.name='生活不掉线' AND r.slot='CONTENT' AND r.status=1;
--   -- 期望:PLATFORM / NULL / 1
--
-- 3) 六段核心循环必须正好六段(不足或超出都会让详情页渲染错):
--   SELECT template_status, evidence_level, version,
--          CHAR_LENGTH(player_promise) AS promise_len,
--          CHAR_LENGTH(core_loop) - CHAR_LENGTH(REPLACE(core_loop, CHAR(10), '')) + 1 AS loop_segments
--     FROM cms_topic_template_meta m JOIN cms_topic t ON t.id = m.topic_id
--    WHERE t.name = '生活不掉线';
--   -- 期望:EXPERIMENTAL / NONE / v1.2 / promise_len 在 40—80 之间 / loop_segments = 6
--
-- 4) ⚠️ 四个角色各自落到哪个品类 —— NULL 的那个,招商段在详情页静默不下发:
--   SELECT ch.name, ch.required, ch.terms_mode, ch.category_id, c.category_name
--     FROM cms_topic_chapter ch JOIN cms_topic t ON t.id = ch.topic_id
--     LEFT JOIN sys_category c ON c.id = ch.category_id
--    WHERE t.name = '生活不掉线' ORDER BY ch.sort_id;
--   -- 期望:四行,category_id 全非空。哪行为空就去后台补配该品类,别改闸。
--
-- 5) 端到端观测(唯一算数的判据):
--   POST /api/template/topic-template/list ⇒ 含「生活不掉线」,
--        templateStatus='EXPERIMENTAL'、evidenceLevel='NONE'
--   POST /api/template/topic-template/info ⇒ mode='FREE_EXPLORE',
--        targetAudience 三段齐、coreLoop 六段、risk 五项、playerPreview 三段均非空,
--        chapters 四条且各带 roleNeed 与 exclusivity
--   ⚠️ 已知 games[] 会是空数组 —— 见下方「未落位内容清单」第 1 条,这是预期而非故障。


-- ======================= ⚠️ 未落位内容清单(必须随本文件一起看) =======================
-- 以下是《生活不掉线》里有、但 07-23 P0 字段字典没有槽位、本次刻意没有硬塞的内容。
-- 不是遗漏,是需要产品决策的缺口:
--
-- 1) §5 七个即插即用游戏 G0—G6 ——【本次完全没落地,影响最大】
--    games[] / optionalGames 的数据链是 cms_topic_node.template_id → cms_member_template,
--    而 G0—G6 在 cms_member_template 里一行都没有;且该表的 interaction_type 是封闭枚举
--    (hidden_menu / city_story_card / photo_spot / qr_checkin),G0—G6 一个都不属于其中。
--    ⇒ 要落地必须新建 7 行 cms_member_template + 对应 cms_topic_node,并扩 interaction_type 枚举。
--    ⚠️ cms_member_template 正是带 questionAnswer/correctAnswer/hint1/hint2 的表(泄题面),
--       灌它需要单独评估,不该混在本轮种子里。⇒ 建议单独立卡。
--    ⇒ 后果:详情页的「可选玩法」区本轮是空的。main_skeleton_template_id 同因留 NULL。
--
-- 2) §3 共享结果 LIFE BACKUP CARD(AREA/RADIUS/TIME_WINDOW/PRIORITY/SERVICE_MODE/
--    BUDGET_BAND/PRIMARY/BACKUP 八字段)—— 这是跨角色的结果物,不属于任何单个角色的权益,
--    字典里既不是「商家权益」也不是「玩家预览」。本次只在 preview_after 里描述了它的产出,
--    没有结构化存储。⇒ 需要一个新的「主题级结果物模型」,字典没有。
--
-- 3) §3.3 跨角色重算(R4 连接):MOVE 确定的移动方式与半径会重算 EAT/WEAR/HOME 的可达商家。
--    这是章节之间的依赖关系,而现有章节模型是平铺的,没有表达章节间依赖的字段。
--
-- 4) §4.2 三种入口(现在就需要／提前准备／商家现有页面)—— 字典无对应字段。
--
-- 5) §2.5「MOVE 是平台公共能力层,商业服务只是插件」—— 这条改变的是该章节的
--    招商语义(公共答案优先于商业答案),但 cms_topic_chapter 只有 required/recruit_enabled,
--    表达不了「本章可以零商家成立」。本次用 required=0 近似,并把原话写进 role_need,
--    但这是描述不是约束 —— 招商闸读不到它。
--
-- 6) §1.5「不做什么」八条 —— 反向约束(不包装成攻略、不用倒计时催促、不按折扣排名、
--    不向多家商户出售联系方式等)。字典没有「反向约束」字段。本次把与玩家安全相关的
--    部分并进了 risk_safety_notice,但运营侧的约束(如排名规则)无处安放。
--
-- 7) §2.2 可选扩展角色(DEVICE/CARE/CONNECT)及其「为什么不进 MVP」的理由 —— 无字段。
--
-- 8) 核心循环第六段的口径冲突:P0 字典要求六段必须含「复玩／邀请」,
--    但 §4.4 明令「不要求关注、加群或公开分享」。本次写成「按需补齐其余角色」(复玩),
--    刻意不写邀请。⇒ 字典这条要么放宽,要么承认它与低打扰型模板不兼容。


-- ======================= 回滚 =======================
-- 倒序删,且只删本种子造的这一条。⚠️ 绝不能按 is_template=1 批量删 —— 会连运营真实录入的一起删。
--   DELETE cm FROM cms_topic_chapter_template_meta cm
--     JOIN cms_topic t ON t.id = cm.topic_id WHERE t.name = '生活不掉线';
--   DELETE ch FROM cms_topic_chapter ch
--     JOIN cms_topic t ON t.id = ch.topic_id WHERE t.name = '生活不掉线';
--   DELETE m FROM cms_topic_template_meta m
--     JOIN cms_topic t ON t.id = m.topic_id WHERE t.name = '生活不掉线';
--   DELETE r FROM product_role r
--     JOIN cms_topic t ON t.id = r.topic_id WHERE t.name = '生活不掉线' AND r.slot = 'CONTENT';
--   DELETE FROM cms_topic WHERE name = '生活不掉线';
