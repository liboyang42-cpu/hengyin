-- runner: requires=transaction
-- Questify 第二组五个圈层自由探索主题：5 个主题模板 + 40 个供给角色候选。
-- 前置：migration_circle_theme_batch2_20260821.sql、migration_topic_template_meta_20260726.sql。
--
-- 关键边界：模板原型的8个候选不是8店打卡；城市实例复制后必须8选3—4，
-- 且覆盖主体验、补给/休息、社交/表达三种作用。玩家仍然任意顺序、跨天、自助记录任意2家。
-- 本种子只登记模板定义，不绑定商家、不创造套餐、不承诺折扣或跨店统一档期。

CREATE TEMPORARY TABLE questify_circle_seed_guard (
    guard_name VARCHAR(64) NOT NULL,
    PRIMARY KEY (guard_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TEMPORARY TABLE questify_circle_seed_topic_source (
    theme_code VARCHAR(24) NOT NULL,
    topic_name VARCHAR(100) NOT NULL,
    subtitle VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    PRIMARY KEY (theme_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

INSERT INTO questify_circle_seed_topic_source (
    theme_code, topic_name, subtitle, description
) VALUES
    ('AFTER_WORK', '下班后切换频道',
     '不把疲惫直接带回家，和同事或朋友换一种晚间状态。',
     '面向工作日18:00—21:30仍有真实供给的2—4人轻探索；城市实例从8个候选中选择3—4个，玩家任意顺序、自助记录任意2家。'),
    ('SOLO', '一个人也出发',
     '不用等谁有空，给自己安排一次舒服的小探索。',
     '只提供可由1人正常购买或预约的城市供给，不做相亲、搭子匹配或公开社交；城市实例从8个候选中选择3—4个。'),
    ('PARENT_CHILD', '亲子各自开心',
     '孩子有参与感，大人也不是全程陪跑。',
     '面向1—2名监护人与1—2名6—12岁儿童；商家必须声明适龄、监护、容量、计价、安全和取消事实，平台不代替监护或安全判断。'),
    ('PHOTO', '今天拍点好看的',
     '和朋友一起选风格、逛小店、留下一组真正喜欢的照片。',
     '普通商家仅提供正常商品与消费环境，必须明确个人非商业拍摄规则；不承诺布景、清场、店员拍照或商业摄影许可。'),
    ('NIGHT', '夜猫子城市生活',
     '晚上九点以后，城市还有另一套生活方式。',
     '只聚合21:00后仍按正常经营真实开放的供给；营业事实7天未确认即停止进入新计划，单场活动结束后自动失效。');

-- 生产只允许使用已经确认的五个一级商家分类；缺失或重名时整批拒绝。
-- runner: assert=rowcount-positive
INSERT INTO questify_circle_seed_guard (guard_name)
SELECT 'categories'
WHERE (SELECT COUNT(*)
       FROM sys_category
       WHERE del_flag = 0 AND status = 1 AND type = 1
         AND category_name IN ('美食烹饪', '运动健身', '娱乐达人', '生活美学', '礼品纪念')) = 5;

-- 1. 五个 FREE_EXPLORE 模板原型。
INSERT INTO cms_topic (
    member_id, topic_type, product_type, circle_theme_code,
    name, subtitle, description, address_name,
    total_time, location_count, is_template, status, user_status, del_flag,
    create_by, create_time
)
SELECT 100000, 1, 2, r.theme_code,
       r.topic_name, r.subtitle, r.description, '由城市代理配置的本地商圈',
       0, 3, 1, 1, 1, 0, 'seed_circle_batch2_20260821', NOW()
FROM questify_circle_seed_topic_source r
WHERE NOT EXISTS (
    SELECT 1 FROM cms_topic t
    WHERE t.circle_theme_code = r.theme_code AND t.is_template = 1 AND t.del_flag = 0
);

-- 2. 内容归属明确为平台；城市代理复制后只取得实例配置权。
INSERT INTO product_role (topic_id, slot, entity_type, entity_id, status)
SELECT t.id, 'CONTENT', 'PLATFORM', NULL, 1
FROM cms_topic t
WHERE t.circle_theme_code IN ('AFTER_WORK', 'SOLO', 'PARENT_CHILD', 'PHOTO', 'NIGHT')
  AND t.is_template = 1 AND t.del_flag = 0
  AND NOT EXISTS (
      SELECT 1 FROM product_role r
      WHERE r.topic_id = t.id AND r.slot = 'CONTENT' AND r.status = 1
  );

-- 3. E0 / Pre-pilot 元数据；所有主题在真实试点前只能是 EXPERIMENTAL + NONE。
INSERT INTO cms_topic_template_meta (
    topic_id, template_status, version, applicable_roles,
    audience_who, audience_moment, audience_need,
    player_promise, core_loop, evidence_level,
    risk_tags, risk_qualifications, risk_cancellation, risk_liability, risk_safety_notice,
    preview_before, preview_during, preview_after, author
)
SELECT t.id, 'EXPERIMENTAL', 'v1.0', 'MERCHANT,AGENT',
       CASE t.circle_theme_code
           WHEN 'AFTER_WORK' THEN '25—40岁城市上班族，2—4名同事、朋友或伴侣'
           WHEN 'SOLO' THEN '22—45岁独居者、单身者或需要独处的人'
           WHEN 'PARENT_CHILD' THEN '1—2名监护人与1—2名6—12岁儿童'
           WHEN 'PHOTO' THEN '18—35岁喜欢拍照、穿搭和记录生活的2—4名朋友或2人情侣'
           ELSE '年满18岁且通常在21:00以后见面的2—5名朋友、同事或情侣'
       END,
       CASE t.circle_theme_code
           WHEN 'AFTER_WORK' THEN '工作日18:00—21:30，从工作状态切换到晚间生活'
           WHEN 'SOLO' THEN '想独自出门但不想临时做大量攻略'
           WHEN 'PARENT_CHILD' THEN '周末需要兼顾孩子参与、家长休息与共同记忆'
           WHEN 'PHOTO' THEN '想更新头像、朋友合照或生活记录'
           ELSE '21:00以后仍想获得真实可用的城市供给'
       END,
       '从8个经商家确认的供给角色中看到城市实例的3—4个选择，任意顺序、可跨天、自助记录任意2家。',
       '不要求八店打卡；每家独立预约、购买与退款，完成记录不冒充商家到店、核销或成交。',
       CONCAT_WS(CHAR(10),
           '查看城市实例中3—4个互补供给',
           '按自己的时间和顺序打开商家现有商品、服务或0元规则',
           '各店独立决定是否预约或购买',
           '到店或使用情况由玩家自助记录，平台不冒充商家确认',
           '记录任意2家并完成本主题轻互动',
           '生成私人记录卡，可跨天补充或随时结束'),
       'NONE',
       CASE t.circle_theme_code
           WHEN 'PARENT_CHILD' THEN '未成年人,监护,年龄适配,工具动物热源,过敏原'
           WHEN 'NIGHT' THEN '夜间营业,酒精年龄,最后入场,单场活动失效'
           WHEN 'PHOTO' THEN '个人非商业拍摄许可,隐私,器材与时长'
           ELSE '营业时效,预约边界,价格与供给真实性'
       END,
       '商家身份、经营状态和绑定供给须可核实；普通环境、座位、自然光或店员建议未经确认不属于承诺。',
       '各店沿用自己的预约、取消、改期与退款规则；平台不提供跨店统一预约或退款。',
       '平台只展示和记录自助选择，不承担商家履约、监护、专业判断或现场安全责任。',
       '只绑定正常销售的现成商品、服务、公开活动，或商家明确确认的0元供给；缺少专项事实时不进入实例。',
       '看到主题人群、时间边界、3—4家有效供给及每家独立规则。',
       '玩家任意顺序行动，可跨天；每家行动入口独立，平台不暗示统一档期。',
       '自助记录任意2家并完成轻互动后生成私人记录卡，不生成虚假到店、核销或成交事实。',
       '平台内容组'
FROM cms_topic t
WHERE t.circle_theme_code IN ('AFTER_WORK', 'SOLO', 'PARENT_CHILD', 'PHOTO', 'NIGHT')
  AND t.is_template = 1 AND t.del_flag = 0
  AND NOT EXISTS (SELECT 1 FROM cms_topic_template_meta m WHERE m.topic_id = t.id);

-- 4. 40 个候选供给角色。模板原型全部启用，复制城市实例时服务层统一重置为0。
-- terms_mode=TRAFFIC：只绑定商家现有供给，不要求平台权益、新套餐或折扣。
INSERT INTO cms_topic_chapter (
    topic_id, name, description, sort_id, category_id, required,
    circle_candidate_code, circle_role, circle_policy, circle_enabled, recruit_enabled,
    terms_mode, max_merchant, create_by, create_time
)
SELECT t.id, r.chapter_name, r.chapter_desc, r.sort_id,
       c.id,
       0, r.candidate_code, r.circle_role, r.circle_policy, 1, 1, 'TRAFFIC', 1,
       'seed_circle_batch2_20260821', NOW()
FROM cms_topic t
JOIN (
    SELECT 'AFTER_WORK' AS theme_code, 'A1' AS candidate_code, 1 AS sort_id, '先把工作放下' AS chapter_name, 'REST' AS circle_role, 'WORKDAY_EVENING' AS circle_policy, '美食烹饪' AS category_name, '绑定已有饮品或正常消费，承担集合与状态缓冲。' AS chapter_desc
    UNION ALL SELECT 'AFTER_WORK', 'A2' AS candidate_code, 2, '动一动再回家', 'EXPERIENCE', 'WORKDAY_EVENING', '运动健身', '绑定已有晚间入门课或体验票，承担身体切换。'
    UNION ALL SELECT 'AFTER_WORK', 'A3' AS candidate_code, 3, '换一身心情', 'EXPRESSION', 'WORKDAY_EVENING', '生活美学', '绑定已有商品和正常试穿，承担个人表达。'
    UNION ALL SELECT 'AFTER_WORK', 'A4' AS candidate_code, 4, '整理一下状态', 'EXPRESSION', 'WORKDAY_EVENING', '生活美学', '绑定已有单次服务；多人实例须可并行或允许分开预约。'
    UNION ALL SELECT 'AFTER_WORK', 'A5' AS candidate_code, 5, '做点和工作无关的', 'EXPERIENCE', 'WORKDAY_EVENING', '生活美学', '绑定已有晚间常规体验，进入兴趣状态。'
    UNION ALL SELECT 'AFTER_WORK', 'A6' AS candidate_code, 6, '好好吃顿晚饭', 'REST', 'WORKDAY_EVENING', '美食烹饪', '绑定已有菜品或正常套餐，承担补给与共同聊天。'
    UNION ALL SELECT 'AFTER_WORK', 'A7' AS candidate_code, 7, '听点现场的', 'EXPERIENCE', 'WORKDAY_EVENING', '娱乐达人', '绑定已有公开演出或正常票务，承担晚间主体验。'
    UNION ALL SELECT 'AFTER_WORK', 'A8' AS candidate_code, 8, '喝完这一杯再走', 'EXPRESSION', 'WORKDAY_EVENING', '美食烹饪', '绑定已有饮品或正常桌位，承担收尾与社交。'

    UNION ALL SELECT 'SOLO', 'O1' AS candidate_code, 1, '一个人坐坐', 'EXPRESSION', 'SOLO', '美食烹饪', '绑定已有饮品、单人位或正常消费，用独自选择表达今天的状态。'
    UNION ALL SELECT 'SOLO', 'O2' AS candidate_code, 2, '吃一顿自己的', 'REST', 'SOLO', '美食烹饪', '绑定已有单人菜品或正常点单，解决单人用餐尴尬。'
    UNION ALL SELECT 'SOLO', 'O3' AS candidate_code, 3, '翻一本没计划的书', 'EXPERIENCE', 'SOLO', '生活美学', '绑定已有图书、杂志或公开活动，提供安静探索。'
    UNION ALL SELECT 'SOLO', 'O4' AS candidate_code, 4, '试一件只问自己的', 'EXPRESSION', 'SOLO', '生活美学', '绑定已有商品和正常试穿，承担个人表达与选择。'
    UNION ALL SELECT 'SOLO', 'O5' AS candidate_code, 5, '做一件带得走的', 'EXPERIENCE', 'SOLO', '礼品纪念', '绑定可单人预约的常规体验，形成完成感。'
    UNION ALL SELECT 'SOLO', 'O6' AS candidate_code, 6, '整理一下自己', 'EXPRESSION', 'SOLO', '生活美学', '绑定已有单人服务，承担状态更新。'
    UNION ALL SELECT 'SOLO', 'O7' AS candidate_code, 7, '看一场自己的', 'EXPERIENCE', 'SOLO', '娱乐达人', '绑定已有公开票务，提供完整独处体验。'
    UNION ALL SELECT 'SOLO', 'O8' AS candidate_code, 8, '留一张今天', 'EXPRESSION', 'SOLO', '礼品纪念', '绑定已有单人拍摄服务，记录独自出发。'

    UNION ALL SELECT 'PARENT_CHILD', 'K1' AS candidate_code, 1, '孩子先动起来', 'EXPERIENCE', 'PARENT_CHILD', '运动健身', '绑定已有适龄体验课或单次票，承担主体验与体力释放。'
    UNION ALL SELECT 'PARENT_CHILD', 'K2' AS candidate_code, 2, '一起读一本', 'EXPERIENCE', 'PARENT_CHILD', '生活美学', '绑定已有童书、亲子阅读或公开活动，提供安静共同体验。'
    UNION ALL SELECT 'PARENT_CHILD', 'K3' AS candidate_code, 3, '一起做出来', 'EXPERIENCE', 'PARENT_CHILD', '礼品纪念', '绑定已有适龄亲子体验，共同完成作品。'
    UNION ALL SELECT 'PARENT_CHILD', 'K4' AS candidate_code, 4, '小小烘焙师', 'EXPERIENCE', 'PARENT_CHILD', '美食烹饪', '绑定已有适龄烘焙体验，提供食物与动手体验。'
    UNION ALL SELECT 'PARENT_CHILD', 'K5' AS candidate_code, 5, '吃一顿都方便的', 'REST', 'PARENT_CHILD', '美食烹饪', '绑定已有正常菜品、儿童座椅和清晰规则，承担家庭补给与休息。'
    UNION ALL SELECT 'PARENT_CHILD', 'K6' AS candidate_code, 6, '今天自己选穿搭', 'EXPRESSION', 'PARENT_CHILD', '生活美学', '绑定已有商品和正常试穿，给孩子选择权。'
    UNION ALL SELECT 'PARENT_CHILD', 'K7' AS candidate_code, 7, '留一张全家新照片', 'EXPRESSION', 'PARENT_CHILD', '礼品纪念', '绑定已有家庭或多人拍摄，形成共同记忆。'
    UNION ALL SELECT 'PARENT_CHILD', 'K8' AS candidate_code, 8, '认识一种自然', 'EXPERIENCE', 'PARENT_CHILD', '生活美学', '只绑定商家已有的适龄公开项目，提供轻自然主题。'

    UNION ALL SELECT 'PHOTO', 'H1' AS candidate_code, 1, '先选今天的风格', 'EXPRESSION', 'PHOTO_RULES', '生活美学', '绑定已有商品和正常试穿，确定个人表达。'
    UNION ALL SELECT 'PHOTO', 'H2' AS candidate_code, 2, '整理一下镜头状态', 'EXPRESSION', 'PHOTO_RULES', '生活美学', '绑定已有单次服务，承担状态准备。'
    UNION ALL SELECT 'PHOTO', 'H3' AS candidate_code, 3, '带一点颜色', 'EXPRESSION', 'PHOTO_RULES', '生活美学', '绑定已有花束或小型商品，提供可带走的视觉元素。'
    UNION ALL SELECT 'PHOTO', 'H4' AS candidate_code, 4, '咖啡与自然光', 'REST', 'PHOTO_RULES', '美食烹饪', '绑定已有饮品和正常座位；自然光未经确认不属于承诺。'
    UNION ALL SELECT 'PHOTO', 'H5' AS candidate_code, 5, '甜点也入镜', 'REST', 'PHOTO_RULES', '美食烹饪', '绑定已有商品，提供轻松停留与分享。'
    UNION ALL SELECT 'PHOTO', 'H6' AS candidate_code, 6, '自己掌镜', 'EXPERIENCE', 'PHOTO_SERVICE', '礼品纪念', '绑定已有自助拍摄服务，提供低压力主拍摄。'
    UNION ALL SELECT 'PHOTO', 'H7' AS candidate_code, 7, '认真拍一组', 'EXPERIENCE', 'PHOTO_SERVICE', '礼品纪念', '绑定已有朋友、情侣或个人拍摄，作为专业记录选项。'
    UNION ALL SELECT 'PHOTO', 'H8' AS candidate_code, 8, '夜色收尾', 'REST', 'PHOTO_RULES', '美食烹饪', '绑定已有饮品和正常桌位，承担夜间氛围与收尾。'

    UNION ALL SELECT 'NIGHT', 'N1' AS candidate_code, 1, '深夜咖啡因或无酒精饮品', 'EXPRESSION', 'NIGHT', '美食烹饪', '绑定已有饮品与夜间座位，以饮品选择表达今晚状态并承担集合。'
    UNION ALL SELECT 'NIGHT', 'N2' AS candidate_code, 2, '先吃一顿夜宵', 'REST', 'NIGHT', '美食烹饪', '绑定已有夜间菜品，承担补给与共同消费。'
    UNION ALL SELECT 'NIGHT', 'N3' AS candidate_code, 3, '夜里逛点不一样的', 'EXPRESSION', 'NIGHT', '生活美学', '绑定已有商品和夜间营业时段，承担城市夜间表达。'
    UNION ALL SELECT 'NIGHT', 'N4' AS candidate_code, 4, '再玩一局', 'EXPERIENCE', 'NIGHT', '娱乐达人', '绑定已有夜间小时票或常规项目，承担停留与轻互动。'
    UNION ALL SELECT 'NIGHT', 'N5' AS candidate_code, 5, '唱到尽兴', 'EXPERIENCE', 'NIGHT', '娱乐达人', '绑定已有夜间包间或公开活动，承担情绪释放。'
    UNION ALL SELECT 'NIGHT', 'N6' AS candidate_code, 6, '听一场现场', 'EXPERIENCE', 'NIGHT', '娱乐达人', '绑定固定驻场或公开演出；若为单场活动必须设置结束时间。'
    UNION ALL SELECT 'NIGHT', 'N7' AS candidate_code, 7, '吃点甜的再走', 'REST', 'NIGHT', '美食烹饪', '绑定已有夜间商品，提供低酒精或无酒精选择。'
    UNION ALL SELECT 'NIGHT', 'N8' AS candidate_code, 8, '最后一杯', 'EXPRESSION', 'NIGHT', '美食烹饪', '绑定已有饮品和正常桌位；玩家可随时结束，本项不是完成条件。'
) r ON CONVERT(r.theme_code USING utf8mb4) COLLATE utf8mb4_general_ci = t.circle_theme_code
JOIN sys_category c ON c.category_name = CONVERT(r.category_name USING utf8mb4) COLLATE utf8mb4_general_ci
 AND c.del_flag = 0 AND c.status = 1 AND c.type = 1
WHERE t.is_template = 1 AND t.del_flag = 0
  AND NOT EXISTS (
      SELECT 1 FROM cms_topic_chapter ch
      WHERE ch.topic_id = t.id
        AND ch.circle_candidate_code = CONVERT(r.candidate_code USING utf8mb4) COLLATE utf8mb4_general_ci
  );

-- 5. 候选级合作方说明；0 店员工时、0 新物料、0 平台专供。
INSERT INTO cms_topic_chapter_template_meta (
    chapter_id, topic_id, role_need,
    exclusivity_mode, exclusivity_scope, exclusivity_period, exclusivity_reason,
    effort_prep_hours, effort_staff_minutes, effort_venue, effort_materials,
    effort_max_concurrent, effort_available_slots, effort_needs_booking,
    effort_cancel_cost, effort_qualifications
)
SELECT ch.id, ch.topic_id,
       CONCAT('确认本店正在正常销售的商品、服务、公开活动，或明确确认的0元供给；补齐', ch.circle_policy, '专项事实，不创造新套餐。'),
       'HARD', '同一城市实例内的同一候选角色', '该城市实例存续期间',
       '同一商家在同一城市实例只承担一个主要角色，避免3—4家中出现两家完全相同作用。',
       0.16, 0, '沿用本店正常经营空间', '不新增物料、不强制折扣、不提供平台专供',
       NULL, '按商家真实营业与预约规则', 0,
       '沿用商家自己的取消、改期与退款规则；各店独立处理。',
       '经营身份、供给、价格、行动入口和专项字段可核实；缺失时不得进入城市实例。'
FROM cms_topic_chapter ch
JOIN cms_topic t ON t.id = ch.topic_id
WHERE t.circle_theme_code IN ('AFTER_WORK', 'SOLO', 'PARENT_CHILD', 'PHOTO', 'NIGHT')
  AND t.is_template = 1 AND t.del_flag = 0
  AND NOT EXISTS (
      SELECT 1 FROM cms_topic_chapter_template_meta m WHERE m.chapter_id = ch.id
  );

-- runner: assert=rowcount-positive
INSERT INTO questify_circle_seed_guard (guard_name)
SELECT 'topics'
WHERE (SELECT COUNT(*)
       FROM cms_topic
       WHERE circle_theme_code IN ('AFTER_WORK', 'SOLO', 'PARENT_CHILD', 'PHOTO', 'NIGHT')
         AND is_template = 1 AND del_flag = 0) = 5;

-- runner: assert=rowcount-positive
INSERT INTO questify_circle_seed_guard (guard_name)
SELECT 'content-roles'
WHERE (SELECT COUNT(*)
       FROM product_role r
       JOIN cms_topic t ON t.id = r.topic_id
       WHERE t.circle_theme_code IN ('AFTER_WORK', 'SOLO', 'PARENT_CHILD', 'PHOTO', 'NIGHT')
         AND t.is_template = 1 AND t.del_flag = 0
         AND r.slot = 'CONTENT' AND r.entity_type = 'PLATFORM'
         AND r.entity_id IS NULL AND r.status = 1) = 5;

-- runner: assert=rowcount-positive
INSERT INTO questify_circle_seed_guard (guard_name)
SELECT 'topic-meta'
WHERE (SELECT COUNT(*)
       FROM cms_topic_template_meta m
       JOIN cms_topic t ON t.id = m.topic_id
       WHERE t.circle_theme_code IN ('AFTER_WORK', 'SOLO', 'PARENT_CHILD', 'PHOTO', 'NIGHT')
         AND t.is_template = 1 AND t.del_flag = 0) = 5;

-- runner: assert=rowcount-positive
INSERT INTO questify_circle_seed_guard (guard_name)
SELECT 'candidate-counts'
WHERE (SELECT COUNT(*) FROM cms_topic_chapter ch JOIN cms_topic t ON t.id = ch.topic_id
       WHERE t.circle_theme_code = 'AFTER_WORK' AND t.is_template = 1 AND t.del_flag = 0) = 8
  AND (SELECT COUNT(*) FROM cms_topic_chapter ch JOIN cms_topic t ON t.id = ch.topic_id
       WHERE t.circle_theme_code = 'SOLO' AND t.is_template = 1 AND t.del_flag = 0) = 8
  AND (SELECT COUNT(*) FROM cms_topic_chapter ch JOIN cms_topic t ON t.id = ch.topic_id
       WHERE t.circle_theme_code = 'PARENT_CHILD' AND t.is_template = 1 AND t.del_flag = 0) = 8
  AND (SELECT COUNT(*) FROM cms_topic_chapter ch JOIN cms_topic t ON t.id = ch.topic_id
       WHERE t.circle_theme_code = 'PHOTO' AND t.is_template = 1 AND t.del_flag = 0) = 8
  AND (SELECT COUNT(*) FROM cms_topic_chapter ch JOIN cms_topic t ON t.id = ch.topic_id
       WHERE t.circle_theme_code = 'NIGHT' AND t.is_template = 1 AND t.del_flag = 0) = 8;

-- runner: assert=rowcount-positive
INSERT INTO questify_circle_seed_guard (guard_name)
SELECT 'candidate-categories'
WHERE (SELECT COUNT(*)
       FROM cms_topic_chapter ch
       JOIN cms_topic t ON t.id = ch.topic_id
       JOIN sys_category c ON c.id = ch.category_id
        AND c.del_flag = 0 AND c.status = 1 AND c.type = 1
       WHERE t.circle_theme_code IN ('AFTER_WORK', 'SOLO', 'PARENT_CHILD', 'PHOTO', 'NIGHT')
         AND t.is_template = 1 AND t.del_flag = 0) = 40
  AND (SELECT COUNT(*)
       FROM cms_topic_chapter ch
       JOIN cms_topic t ON t.id = ch.topic_id
       JOIN sys_category c ON c.id = ch.category_id
        AND c.del_flag = 0 AND c.status = 1 AND c.type = 1
       WHERE t.circle_theme_code IN ('AFTER_WORK', 'SOLO', 'PARENT_CHILD', 'PHOTO', 'NIGHT')
         AND t.is_template = 1 AND t.del_flag = 0
         AND c.category_name <> CASE ch.circle_candidate_code
             WHEN 'A1' THEN '美食烹饪' WHEN 'A2' THEN '运动健身'
             WHEN 'A3' THEN '生活美学' WHEN 'A4' THEN '生活美学'
             WHEN 'A5' THEN '生活美学' WHEN 'A6' THEN '美食烹饪'
             WHEN 'A7' THEN '娱乐达人' WHEN 'A8' THEN '美食烹饪'
             WHEN 'O1' THEN '美食烹饪' WHEN 'O2' THEN '美食烹饪'
             WHEN 'O3' THEN '生活美学' WHEN 'O4' THEN '生活美学'
             WHEN 'O5' THEN '礼品纪念' WHEN 'O6' THEN '生活美学'
             WHEN 'O7' THEN '娱乐达人' WHEN 'O8' THEN '礼品纪念'
             WHEN 'K1' THEN '运动健身' WHEN 'K2' THEN '生活美学'
             WHEN 'K3' THEN '礼品纪念' WHEN 'K4' THEN '美食烹饪'
             WHEN 'K5' THEN '美食烹饪' WHEN 'K6' THEN '生活美学'
             WHEN 'K7' THEN '礼品纪念' WHEN 'K8' THEN '生活美学'
             WHEN 'H1' THEN '生活美学' WHEN 'H2' THEN '生活美学'
             WHEN 'H3' THEN '生活美学' WHEN 'H4' THEN '美食烹饪'
             WHEN 'H5' THEN '美食烹饪' WHEN 'H6' THEN '礼品纪念'
             WHEN 'H7' THEN '礼品纪念' WHEN 'H8' THEN '美食烹饪'
             WHEN 'N1' THEN '美食烹饪' WHEN 'N2' THEN '美食烹饪'
             WHEN 'N3' THEN '生活美学' WHEN 'N4' THEN '娱乐达人'
             WHEN 'N5' THEN '娱乐达人' WHEN 'N6' THEN '娱乐达人'
             WHEN 'N7' THEN '美食烹饪' WHEN 'N8' THEN '美食烹饪'
             ELSE '__UNMAPPED__'
         END) = 0;

-- runner: assert=rowcount-positive
INSERT INTO questify_circle_seed_guard (guard_name)
SELECT 'chapter-meta'
WHERE (SELECT COUNT(*)
       FROM cms_topic_chapter_template_meta m
       JOIN cms_topic t ON t.id = m.topic_id
       WHERE t.circle_theme_code IN ('AFTER_WORK', 'SOLO', 'PARENT_CHILD', 'PHOTO', 'NIGHT')
         AND t.is_template = 1 AND t.del_flag = 0) = 40;

DROP TEMPORARY TABLE questify_circle_seed_topic_source;
DROP TEMPORARY TABLE questify_circle_seed_guard;

-- 只读回读（执行后人工核对）：
-- SELECT circle_theme_code, COUNT(*) AS candidate_count
-- FROM cms_topic t JOIN cms_topic_chapter ch ON ch.topic_id = t.id
-- WHERE t.is_template = 1 AND t.circle_theme_code IS NOT NULL AND t.del_flag = 0
-- GROUP BY circle_theme_code;
-- 预期五行且每行 candidate_count=8。
--
-- 负控：将任一城市实例只启用2个候选或启用5个候选，再发布必须由服务层拒绝；
-- 将3—4个候选全部配置成同一 circle_role，也必须拒绝。
